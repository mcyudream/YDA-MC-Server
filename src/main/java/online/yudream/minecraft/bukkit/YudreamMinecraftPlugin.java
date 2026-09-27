package online.yudream.minecraft.bukkit;

import online.yudream.minecraft.bukkit.afk.AfkTracker;
import online.yudream.minecraft.bukkit.api.YudreamApiClient;
import online.yudream.minecraft.bukkit.bridge.BridgeMode;
import online.yudream.minecraft.bukkit.bridge.DownstreamSensor;
import online.yudream.minecraft.bukkit.bridge.InboundChatPoller;
import online.yudream.minecraft.bukkit.command.YudreamCommand;
import online.yudream.minecraft.bukkit.compat.AdvancementCompatibility;
import online.yudream.minecraft.bukkit.compat.PaperChatCompatibility;
import online.yudream.minecraft.bukkit.compat.ServerCompatibility;
import online.yudream.minecraft.bukkit.config.YudreamConfig;
import online.yudream.minecraft.bukkit.event.GameEventListener;
import online.yudream.minecraft.bukkit.event.PlayerActivityListener;
import online.yudream.minecraft.bukkit.report.PlayerEventType;
import online.yudream.minecraft.bukkit.report.PlayerEventPayload;
import online.yudream.minecraft.bukkit.report.ReportQueue;
import online.yudream.minecraft.bukkit.util.PlayerSnapshots;
import online.yudream.minecraft.bukkit.util.YamlPatcher;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * YuDream Minecraft bridge for Bukkit, Spigot and Paper.
 *
 * <p>It runs in one of two modes, selected by {@code mode} in {@code config.yml}:
 *
 * <ul>
 *   <li>{@code standalone} — the historical behaviour. This server talks to YuDream Admin itself.</li>
 *   <li>{@code downstream} — the server sits behind a proxy, and this plugin becomes a sensor: it
 *       forwards player activity to the Velocity bridge over the {@code yudream:bridge} plugin message
 *       channel and uploads nothing itself, exactly like the Fabric sensor does for Fabric backends.</li>
 * </ul>
 *
 * <p>Which one is active is a single decision point, {@link #canReport()}, plus a router that every
 * player event goes through.
 */
public final class YudreamMinecraftPlugin extends JavaPlugin {

    private YudreamConfig settings;
    private YudreamApiClient apiClient;
    private ReportQueue reportQueue;
    private AfkTracker afkTracker;
    private DownstreamSensor sensor;
    private InboundChatPoller inboundPoller;
    private int snapshotTaskId = -1;
    private int inboundPollTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info(ServerCompatibility.describe());
        reloadBridge();

        getServer().getPluginManager().registerEvents(new PlayerActivityListener(this), this);
        getServer().getPluginManager().registerEvents(new GameEventListener(this), this);
        if (PaperChatCompatibility.register(this)) {
            getLogger().info("Paper AsyncChatEvent compatibility is active.");
        }
        if (AdvancementCompatibility.register(this)) {
            getLogger().info("Advancement event compatibility is active.");
        }
        PluginCommand command = getCommand("yudreammc");
        if (command != null) {
            YudreamCommand executor = new YudreamCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        if (settings.isSyncOnlineOnEnable()) {
            syncOnlinePlayers();
        }
        snapshotTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(
                this, new Runnable() {
                    @Override
                    public void run() {
                        reportPlayerSnapshot();
                    }
                }, 20L, 20L * 60L);
    }

    @Override
    public void onDisable() {
        if (snapshotTaskId >= 0) {
            getServer().getScheduler().cancelTask(snapshotTaskId);
            snapshotTaskId = -1;
        }
        stopInboundPoller();
        if (sensor != null) {
            sensor.stop();
            sensor = null;
        }
        if (afkTracker != null) {
            afkTracker.stop();
        }
        if (settings != null && settings.isReportQuitOnDisable() && reportQueue != null && canReport()) {
            for (Player player : getServer().getOnlinePlayers()) {
                reportQueue.submit(PlayerEventType.QUIT, PlayerSnapshots.from(player));
            }
        }
        if (reportQueue != null && canReport()) {
            reportQueue.submitSnapshot(Collections.<PlayerEventPayload>emptyList(), System.currentTimeMillis());
        }
        if (reportQueue != null) {
            reportQueue.shutdown(settings == null ? 5000L : settings.getFlushTimeoutMs());
        }
    }

    public synchronized void reloadBridge() {
        reloadConfig();
        settings = YudreamConfig.load(this);
        apiClient = new YudreamApiClient(settings);

        if (reportQueue != null) {
            reportQueue.shutdown(settings.getFlushTimeoutMs());
        }
        // The gate keeps background producers (AFK timer, snapshot task) from uploading directly
        // once downstream mode is active and the proxy is the only uploader.
        reportQueue = new ReportQueue(this, apiClient, settings, new ReportQueue.ReportGate() {
            @Override
            public boolean canReport() {
                return YudreamMinecraftPlugin.this.canReport();
            }
        });

        if (afkTracker != null) {
            afkTracker.stop();
        }
        afkTracker = new AfkTracker(this, reportQueue, settings);
        afkTracker.start();

        // The sensor follows the mode, so switching modes never leaves a stale channel registered.
        if (sensor != null) {
            sensor.stop();
            sensor = null;
        }
        if (settings.isDownstream()) {
            sensor = new DownstreamSensor(this, settings.getDownstream());
            sensor.start();
        }

        startInboundPoller();
        warnAboutConfiguration();
    }

    /** 群服互联入站：随 reload 一起启停，游标跨 reload 保留。 */
    private void startInboundPoller() {
        stopInboundPoller();
        if (!settings.isEnabled() || !settings.getChatBridge().isEnabled() || !settings.getChatBridge().isPollInbound()) {
            String reason = !settings.isEnabled()
                    ? "bridge 总开关关闭"
                    : (!settings.getChatBridge().isEnabled() ? "chat-bridge 未启用" : "poll-inbound 未启用");
            getLogger().info("群服互联入站未启动：" + reason + "。");
            return;
        }
        if (inboundPoller == null) {
            inboundPoller = new InboundChatPoller(this);
        }
        inboundPollTaskId = getServer().getScheduler().runTaskTimerAsynchronously(
                this, (Runnable) () -> inboundPoller.poll(), 20L * 10L, settings.getChatBridge().getPollIntervalTicks()).getTaskId();
        getLogger().info("群服互联入站已启动：server-id=" + settings.getServerId()
                + " -> " + settings.getBaseUrl()
                + "，实时推送优先，轮询兜底（间隔 " + (settings.getChatBridge().getPollIntervalTicks() / 20L) + " 秒）。"
                + "server-id 必须与 Admin 侧配置了群服互联的服务器条目一致，否则只会收到心跳、永远收不到群消息。");
    }

    private void stopInboundPoller() {
        if (inboundPollTaskId >= 0) {
            getServer().getScheduler().cancelTask(inboundPollTaskId);
            inboundPollTaskId = -1;
            getLogger().info("群服互联入站已停止。");
        }
    }

    private void warnAboutConfiguration() {
        if (!settings.isEnabled()) {
            getLogger().warning("YuDream bridge is disabled by config.");
        }
        if (settings.isDownstream()) {
            if (settings.getDownstream().isFallbackToApi() && !settings.isConfigured()) {
                getLogger().warning("downstream.fallback-to-api is enabled but base-url, server-id or api-key"
                        + " is missing, so there is nothing to fall back to.");
            }
            return;
        }
        if (!settings.isConfigured()) {
            getLogger().warning("YuDream bridge is not fully configured. Set base-url, server-id and api-key in config.yml.");
        }
    }

    // ------------------------------------------------------------------ mode

    /**
     * Whether this server uploads to YuDream Admin itself.
     *
     * <p>In standalone mode this is the historical "enabled and configured". In downstream mode it is
     * false unless the operator turned on {@code downstream.fallback-to-api} <em>and</em> the proxy has
     * stopped acknowledging, so the default downstream deployment uploads nothing here.
     */
    public boolean canReport() {
        if (settings == null || !settings.isEnabled() || !settings.isConfigured()) {
            return false;
        }
        if (!settings.isDownstream()) {
            return true;
        }
        return settings.getDownstream().isFallbackToApi() && !isProxyReachable();
    }

    private boolean isProxyReachable() {
        return sensor != null && sensor.isProxyReachable();
    }

    /**
     * Switches mode, persists it to {@code config.yml} and reconfigures everything.
     *
     * <p>The file is patched line by line rather than saved through Bukkit, so the comments in the
     * shipped config survive.
     *
     * @return true when the new mode was written and applied
     */
    public synchronized boolean setMode(BridgeMode mode) {
        File configFile = new File(getDataFolder(), "config.yml");
        try {
            YamlPatcher.setTopLevelValue(configFile, "mode", mode.getId());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not write mode=" + mode.getId() + " to " + configFile, e);
            return false;
        }
        reloadBridge();
        // A mode change alters who is responsible for reporting, so re-announce whoever is already
        // online instead of leaving Admin without them until the next join or snapshot.
        if (settings.isSyncOnlineOnEnable()) {
            syncOnlinePlayers();
        }
        return true;
    }

    // ------------------------------------------------------------------ player events

    /** Every join goes through here so the two modes stay in one place. */
    public void handleJoin(Player player) {
        if (sensor != null) {
            sensor.onPlayerJoin(player);
        }
        if (canReport()) {
            afkTracker.markOnline(player);
            reportQueue.submit(PlayerEventType.JOIN, PlayerSnapshots.from(player));
        }
    }

    /** Every quit goes through here so the two modes stay in one place. */
    public void handleQuit(Player player) {
        if (sensor != null) {
            sensor.onPlayerQuit(player);
        }
        if (canReport()) {
            reportQueue.submit(PlayerEventType.QUIT, PlayerSnapshots.from(player));
            afkTracker.markOffline(player);
        }
    }

    /**
     * One activity signal: {@code chat}, {@code move}, {@code interact} or {@code command}.
     *
     * <p>In downstream mode this is forwarded to the proxy, which owns AFK. In standalone mode it
     * resets the local AFK timer.
     */
    public void handleActivity(Player player, String source) {
        if (sensor != null) {
            sensor.onActivity(player, source);
        }
        if (canReport() && afkTracker != null) {
            afkTracker.markActive(player);
        }
    }

    // ---------------------------------------------------------------- 群服互联（游戏 → 群）

    /** 每条玩家聊天都经过这里；转发与否由 Admin 侧的群服互联配置决定。 */
    public void handleGameChat(Player player, String message) {
        reportGameEvent(PlayerEventType.CHAT, player, message);
    }

    /** 每条死亡消息都经过这里。 */
    public void handleGameDeath(Player player, String deathMessage) {
        reportGameEvent(PlayerEventType.DEATH, player, deathMessage);
    }

    /** 每个达成并会公告的成就都经过这里。 */
    public void handleGameAdvancement(Player player, String title) {
        reportGameEvent(PlayerEventType.ADVANCEMENT, player, title);
    }

    private void reportGameEvent(PlayerEventType type, Player player, String content) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        PlayerEventPayload payload = new PlayerEventPayload(
                player.getUniqueId().toString(), player.getName(), System.currentTimeMillis(), content.trim());
        if (sensor != null) {
            // 下游模式：转发给代理，由代理统一上报（协议与进服/退服信号共用一条通道）。
            sensor.onGameEvent(type, payload);
        }
        if (canReport()) {
            reportQueue.submit(type, payload);
        }
    }

    public void syncOnlinePlayers() {
        if (settings != null && settings.isDownstream()) {
            if (sensor != null) {
                // Ask the proxy to re-announce everyone it believes is on this backend.
                sensor.requestHello();
            }
            return;
        }
        if (!canReport()) {
            return;
        }
        for (Player player : getServer().getOnlinePlayers()) {
            reportQueue.submit(PlayerEventType.JOIN, PlayerSnapshots.from(player));
            afkTracker.markOnline(player);
        }
        reportPlayerSnapshot();
    }

    private void reportPlayerSnapshot() {
        if (!canReport() || reportQueue == null) {
            return;
        }
        long observedAt = System.currentTimeMillis();
        List<PlayerEventPayload> players = new ArrayList<PlayerEventPayload>();
        for (Player player : getServer().getOnlinePlayers()) {
            players.add(PlayerSnapshots.from(player, observedAt));
        }
        reportQueue.submitSnapshot(players, observedAt);
    }

    // ------------------------------------------------------------------ accessors

    public YudreamConfig getSettings() {
        return settings;
    }

    public YudreamApiClient getApiClient() {
        return apiClient;
    }

    public ReportQueue getReportQueue() {
        return reportQueue;
    }

    public AfkTracker getAfkTracker() {
        return afkTracker;
    }

    /** The active downstream sensor, or {@code null} in standalone mode. */
    public DownstreamSensor getSensor() {
        return sensor;
    }

    public boolean isDownstreamMode() {
        return settings != null && settings.isDownstream();
    }
}
