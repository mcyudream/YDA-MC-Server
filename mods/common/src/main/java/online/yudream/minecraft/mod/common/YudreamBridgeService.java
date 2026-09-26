package online.yudream.minecraft.mod.common;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class YudreamBridgeService {

    private static final long SNAPSHOT_INTERVAL_MS = 60_000L;

    private final Path configPath;
    private final LogSink logger;
    private final Map<UUID, PlayerActivityState> states = new ConcurrentHashMap<>();
    private BridgeConfig config;
    private YudreamApiClient apiClient;
    private ReportQueue reportQueue;
    /** 群服互联入站广播回调：由平台 mod 设置，负责把文本切回服务器主线程。 */
    private volatile java.util.function.Consumer<String> inboundBroadcast;
    private volatile long inboundCursor;
    private long lastInboundPollAt;
    private long lastAfkCheckAt;
    private long lastSnapshotAt;

    public YudreamBridgeService(Path configPath, LogSink logger) {
        this.configPath = configPath;
        this.logger = logger;
    }

    public synchronized void start() {
        reload();
    }

    public synchronized void reload() {
        if (reportQueue != null) {
            reportQueue.shutdown(currentFlushTimeout());
        }
        config = BridgeConfigLoader.load(configPath, logger);
        apiClient = new YudreamApiClient(config);
        reportQueue = new ReportQueue(logger, apiClient, config);
        lastAfkCheckAt = 0L;
        lastSnapshotAt = 0L;
        if (!config.isConfigured()) {
            logger.warn("YuDream bridge is not fully configured. Edit " + configPath + " and set base-url, server-id and api-key.");
        }
        if (!config.isEnabled()) {
            logger.warn("YuDream bridge is disabled by config.");
        }
    }

    public synchronized void stop(Collection<PlayerIdentity> onlinePlayers) {
        if (canReport()) {
            reportQueue.submitSnapshot(Collections.<PlayerEventPayload>emptyList(), System.currentTimeMillis());
        }
        if (config != null && config.isReportQuitOnStop() && reportQueue != null) {
            for (PlayerIdentity player : onlinePlayers) {
                reportQueue.submit(PlayerEventType.QUIT, player.payload(System.currentTimeMillis()));
            }
        }
        states.clear();
        if (reportQueue != null) {
            reportQueue.shutdown(currentFlushTimeout());
            reportQueue = null;
        }
    }

    public void syncOnline(Collection<PlayerIdentity> onlinePlayers) {
        if (!canReport()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (PlayerIdentity player : onlinePlayers) {
            states.put(player.getUuid(), new PlayerActivityState(now, false));
            reportQueue.submit(PlayerEventType.JOIN, player.payload(now));
        }
        reportSnapshot(onlinePlayers, now);
    }

    public void playerJoined(PlayerIdentity player) {
        if (!canReport()) {
            return;
        }
        long now = System.currentTimeMillis();
        states.put(player.getUuid(), new PlayerActivityState(now, false));
        reportQueue.submit(PlayerEventType.JOIN, player.payload(now));
    }

    public void playerQuit(PlayerIdentity player) {
        if (!canReport()) {
            return;
        }
        reportQueue.submit(PlayerEventType.QUIT, player.payload(System.currentTimeMillis()));
        states.remove(player.getUuid());
    }

    // ---------------------------------------------------------------- 群服互联（游戏 → 群）

    /**
     * 群服互联：上报聊天、死亡或成就事件。转发与否由 Admin 管理端逐服务器配置，
     * 本地 {@code chat-bridge.report-events} 只决定本机是否上报。
     */
    public void reportGameEvent(PlayerEventType type, PlayerIdentity player, String content) {
        if (!canReport() || !config.isChatReportEvents()) {
            return;
        }
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        reportQueue.submit(type, new PlayerEventPayload(
                player.getUuid().toString(), player.getName(), System.currentTimeMillis(), content.trim()));
    }

    // ---------------------------------------------------------------- 群服互联（群 → 游戏）

    /** 设置入站广播回调；实现方必须把调用切回服务器主线程。 */
    public void setInboundBroadcast(java.util.function.Consumer<String> broadcast) {
        this.inboundBroadcast = broadcast;
    }

    public void tickInbound() {
        if (!canReport() || !config.isChatBridgeEnabled() || !config.isChatPollInbound()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastInboundPollAt < config.getChatPollIntervalMs()) {
            return;
        }
        lastInboundPollAt = now;
        CompletableFuture.runAsync(this::pollInbound);
    }

    private void pollInbound() {
        YudreamApiClient client = apiClient;
        BridgeConfig snapshot = config;
        java.util.function.Consumer<String> broadcast = inboundBroadcast;
        if (client == null || snapshot == null || broadcast == null) {
            return;
        }
        try {
            HttpResult result = client.inboundChat(inboundCursor);
            if (!result.isSuccess()) {
                return;
            }
            Map<String, Object> body = MiniJson.parseObject(result.getBody());
            if (body == null) {
                return;
            }
            long latest = MiniJson.longValue(body, "latest", inboundCursor);
            // 首次拉取只快进游标：重启后不重播历史窗口内还留着的群消息
            boolean fastForward = inboundCursor == 0L && latest > 0;
            inboundCursor = Math.max(inboundCursor, latest);
            Object rawMessages = body.get("messages");
            if (!(rawMessages instanceof Collection) || fastForward) {
                return;
            }
            for (Object item : (Collection<?>) rawMessages) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> message = (Map<?, ?>) item;
                Object content = message.get("content");
                if (content == null || String.valueOf(content).trim().isEmpty()) {
                    continue;
                }
                long seq = message.get("seq") instanceof Number ? ((Number) message.get("seq")).longValue() : 0L;
                inboundCursor = Math.max(inboundCursor, seq);
                String line = snapshot.formatChatInbound(
                        String.valueOf(message.get("sender")), String.valueOf(content));
                broadcast.accept(line);
            }
        } catch (Exception e) {
            logger.warn("YuDream inbound chat poll failed: " + e.getMessage());
        }
    }

    public void markActive(PlayerIdentity player) {
        if (!canReport() || !config.isAfkEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        PlayerActivityState state = states.get(player.getUuid());
        if (state == null) {
            states.put(player.getUuid(), new PlayerActivityState(now, false));
            return;
        }
        if (state.isAfk()) {
            reportQueue.submit(PlayerEventType.AFK_END, player.payload(now));
        }
        states.put(player.getUuid(), new PlayerActivityState(now, false));
    }

    public void tick(Collection<PlayerIdentity> onlinePlayers) {
        if (!canReport()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSnapshotAt >= SNAPSHOT_INTERVAL_MS) {
            reportSnapshot(onlinePlayers, now);
        }
        if (!config.isAfkEnabled()) {
            return;
        }
        if (now - lastAfkCheckAt < config.getAfkCheckIntervalMs()) {
            return;
        }
        lastAfkCheckAt = now;
        for (PlayerIdentity player : onlinePlayers) {
            PlayerActivityState state = states.get(player.getUuid());
            if (state == null) {
                states.put(player.getUuid(), new PlayerActivityState(now, false));
                continue;
            }
            if (!state.isAfk() && now - state.getLastActiveAt() >= config.getAfkTimeoutMs()) {
                states.put(player.getUuid(), new PlayerActivityState(state.getLastActiveAt(), true));
                reportQueue.submit(PlayerEventType.AFK_START, player.payload(now));
            }
        }
    }

    private void reportSnapshot(Collection<PlayerIdentity> onlinePlayers, long observedAt) {
        java.util.List<PlayerEventPayload> players = onlinePlayers.stream()
                .map(player -> player.payload(observedAt))
                .collect(java.util.stream.Collectors.toList());
        reportQueue.submitSnapshot(players, observedAt);
        lastSnapshotAt = observedAt;
    }

    public void statusAsync(Consumer<String> callback) {
        BridgeConfig snapshot = config;
        YudreamApiClient client = apiClient;
        if (snapshot == null || !snapshot.isEnabled() || !snapshot.isConfigured() || client == null) {
            callback.accept("YuDream bridge is not configured or disabled.");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                HttpResult result = client.players(1, 100);
                if (result.isSuccess()) {
                    PlayerSummaryParser.Summary summary = PlayerSummaryParser.parse(result.getBody());
                    callback.accept("Remote players: total=" + summary.getTotal()
                            + ", online=" + summary.getOnline()
                            + ", afk=" + summary.getAfk()
                            + ", http=" + result.getStatusCode());
                } else {
                    callback.accept("Remote status failed: HTTP " + result.getStatusCode() + " " + trim(result.getBody()));
                }
            } catch (Exception e) {
                callback.accept("Remote status failed: " + e.getMessage());
            }
        });
    }

    public int queueSize() {
        return reportQueue == null ? 0 : reportQueue.size();
    }

    public BridgeConfig getConfig() {
        return config == null ? BridgeConfig.defaults() : config;
    }

    public boolean shouldSyncOnlineOnStart() {
        return config != null && config.isSyncOnlineOnStart();
    }

    private boolean canReport() {
        return config != null && config.isEnabled() && config.isConfigured() && reportQueue != null;
    }

    private long currentFlushTimeout() {
        return config == null ? 5000L : config.getFlushTimeoutMs();
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 160 ? body : body.substring(0, 160) + "...";
    }
}
