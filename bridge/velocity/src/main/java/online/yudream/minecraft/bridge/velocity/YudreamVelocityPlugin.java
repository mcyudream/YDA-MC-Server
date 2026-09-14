package online.yudream.minecraft.bridge.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import online.yudream.minecraft.bridge.common.afk.AfkTracker;
import online.yudream.minecraft.bridge.common.config.BridgeSettings;
import online.yudream.minecraft.bridge.common.config.ConfigFile;
import online.yudream.minecraft.bridge.common.http.HttpResult;
import online.yudream.minecraft.bridge.common.http.YudreamApiClient;
import online.yudream.minecraft.bridge.common.log.LogSink;
import online.yudream.minecraft.bridge.common.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.common.model.PlayerEventType;
import online.yudream.minecraft.bridge.common.model.PlayerIdentity;
import online.yudream.minecraft.bridge.common.model.SubServerInfo;
import online.yudream.minecraft.bridge.common.model.SubServerRoster;
import online.yudream.minecraft.bridge.common.protocol.BridgeMessage;
import online.yudream.minecraft.bridge.common.protocol.BridgeProtocol;
import online.yudream.minecraft.bridge.common.protocol.ProtocolException;
import online.yudream.minecraft.bridge.common.queue.ReportJournal;
import online.yudream.minecraft.bridge.common.queue.ReportQueue;
import online.yudream.minecraft.bridge.common.summary.PlayerSummary;
import online.yudream.minecraft.bridge.velocity.command.YudreamCommand;
import online.yudream.minecraft.bridge.velocity.config.ConfigTemplate;
import online.yudream.minecraft.bridge.velocity.config.VelocitySettings;
import online.yudream.minecraft.bridge.velocity.presence.ProxyPresence;
import online.yudream.minecraft.bridge.velocity.sensor.SensorRegistry;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The YuDream bridge, running on a Velocity proxy.
 *
 * <p>It is the only component that uploads anything. Two independent inputs are merged here:
 *
 * <ul>
 *   <li><b>Presence</b> comes from the proxy itself
 *       ({@code ServerConnectedEvent} / {@code DisconnectEvent}). This is authoritative, so a
 *       missing or broken backend sensor can never produce a wrong online list.</li>
 *   <li><b>Activity</b> comes from the companion Fabric mod on each backend over the
 *       {@code yudream:bridge} plugin message channel, which is the only way a proxy can learn about
 *       chat, movement and interactions. Activity drives AFK transitions only.</li>
 * </ul>
 *
 * <p><b>Every downstream server is reported</b>, each as its own sub-server: a player moving
 * {@code fabric -> paper} produces a quit on {@code fabric} and a join on {@code paper}, so YuDream
 * Admin keeps one time bucket per sub-server and the total stays their sum. The wire format carries
 * the sub-server name on events and a grouped roster per sub-server in snapshots.
 *
 * <p>{@code target.server} is only the <b>default / login-entry marker</b> now: it is what
 * {@code /yudreammc target} edits and what a login hint reads. It no longer selects, suppresses or
 * enables any reporting. {@code target.require-sensor} is applied <b>per sub-server</b>: a backend
 * whose sensor has not said hello is simply not reported, while every other backend keeps reporting.
 */
@Plugin(
        id = YudreamVelocityPlugin.PLUGIN_ID,
        name = "YuDream Minecraft Bridge",
        version = YudreamVelocityPlugin.VERSION,
        description = "Reports every downstream server's player activity to YuDream Admin, per sub-server.",
        authors = {"YuDream"},
        url = "https://github.com/mcyudream/YDA-MC-Server")
public final class YudreamVelocityPlugin {

    public static final String PLUGIN_ID = "yudream-velocity";
    /** Keep in sync with the {@code version} above; the annotation needs a literal. */
    public static final String VERSION = "1.1.0";

    private static final String CONFIG_FILE_NAME = "config.properties";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final ChannelIdentifier channel = MinecraftChannelIdentifier.create(
            BridgeProtocol.CHANNEL_NAMESPACE, BridgeProtocol.CHANNEL_PATH);

    private final ProxyPresence presence = new ProxyPresence();
    private final SensorRegistry sensors = new SensorRegistry();
    /**
     * Players currently reported to YuDream Admin as online, and on which sub-servers.
     *
     * <p>Keyed by player because the same player can legitimately be reported on more than one
     * sub-server for a moment during a switch, and the two quits must both be delivered.
     */
    private final Map<UUID, Set<String>> reportedPresence = new ConcurrentHashMap<UUID, Set<String>>();
    /** Backends already warned about a protocol mismatch, so a heartbeat cannot spam the log. */
    private final Set<String> warnedProtocolMismatch = ConcurrentHashMap.newKeySet();

    private LogSink log;
    private Path configPath;
    private VelocitySettings settings = VelocitySettings.defaults();
    private YudreamApiClient apiClient;
    private ReportQueue reportQueue;
    private AfkTracker afkTracker;

    private ScheduledTask snapshotTask;
    private ScheduledTask afkTask;
    private ScheduledTask probeTask;
    private ScheduledTask topologyTask;
    private boolean warnedAboutTarget;
    /** Backends already warned about a stale sensor, so one outage logs once per backend. */
    private final Set<String> warnedAboutSensors = ConcurrentHashMap.newKeySet();

    @Inject
    public YudreamVelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    // ------------------------------------------------------------------ lifecycle

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.log = new VelocityLogSink(logger, Boolean.getBoolean("yudream.bridge.debug"));
        this.configPath = dataDirectory.resolve(CONFIG_FILE_NAME);
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            logger.warn("Could not create the YuDream bridge data directory {}", dataDirectory, e);
        }

        server.getChannelRegistrar().register(channel);
        reload();

        CommandMeta meta = server.getCommandManager().metaBuilder("yudreammc")
                .aliases("ymc")
                .plugin(this)
                .build();
        server.getCommandManager().register(meta, new YudreamCommand(this));

        logger.info("YuDream Velocity bridge {} started. Channel={}, default downstream='{}' (all downstream servers are reported).",
                VERSION, BridgeProtocol.CHANNEL, settings.targetServer().isEmpty() ? "<unset>" : settings.targetServer());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        cancelTasks();
        BridgeSettings bridge = settings.bridge();
        long now = System.currentTimeMillis();
        if (bridge.isReportQuitOnDisable() && reportQueue != null) {
            for (Map.Entry<UUID, Set<String>> entry : new ArrayList<Map.Entry<UUID, Set<String>>>(reportedPresence.entrySet())) {
                PlayerIdentity identity = presence.identityOf(entry.getKey());
                if (identity == null) {
                    continue;
                }
                for (String serverName : new ArrayList<String>(entry.getValue())) {
                    reportQueue.submit(PlayerEventType.QUIT, PlayerEventPayload.of(identity, now, serverName));
                }
            }
            reportedPresence.clear();
        }
        if (canReport()) {
            // An empty roster per sub-server tells YuDream Admin nobody is online anywhere, which is
            // how it reconciles quit events missed during a crash.
            reportQueue.submitGroupedSnapshot(emptyRosters(), now);
        }
        if (reportQueue != null) {
            reportQueue.shutdown(bridge.getFlushTimeoutMs());
        }
    }

    // ------------------------------------------------------------------ proxy events

    /**
     * Refuses blocked account names before Velocity authenticates them.
     *
     * <p>This has to happen at pre-login, not at {@code LoginEvent}: Velocity authenticates first, so
     * every rejected attempt would otherwise be one {@code hasJoined} call to the Yggdrasil API. A
     * client stuck in a reconnect loop makes about twenty of those a minute, which is enough to trip
     * the authentication server's per-IP rate limit and lock real players out of the network.
     */
    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        String username = event.getUsername();
        for (String blocked : settings.blockedLoginNames()) {
            if (blocked.equalsIgnoreCase(username)) {
                log.debug("Refused pre-login for blocked name '" + username + "'.");
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        Component.text("This account name is not allowed on this network.")));
                return;
            }
        }
    }

    /**
     * A player moved onto a downstream server.
     *
     * <p>Every backend is reported, so the transition {@code previous -> new} is always a quit on
     * {@code previous} plus a join on {@code new}. A first connection has no previous server and is
     * therefore only a join. This is what makes Admin accumulate one bucket per sub-server instead of
     * treating a switch as a departure.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        PlayerIdentity identity = identity(player);
        String newServer = event.getServer().getServerInfo().getName();
        String previousServer = event.getPreviousServer()
                .map(registered -> registered.getServerInfo().getName())
                .orElse(null);

        presence.connected(identity, newServer);

        if (previousServer != null && !previousServer.equals(newServer)) {
            reportQuit(identity, previousServer);
            if (settings.bridge().isServerSwitchEventEnabled()) {
                reportServerSwitch(identity, previousServer, newServer);
            }
        }
        reportJoin(identity, newServer);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String serverName = presence.serverOf(uuid);
        PlayerIdentity identity = presence.identityOf(uuid);
        presence.disconnect(uuid);
        if (identity == null) {
            identity = identity(player);
        }
        // Quit the sub-server the player was actually on, whatever it is.
        if (serverName != null) {
            reportQuit(identity, serverName);
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!channel.equals(event.getIdentifier())) {
            return;
        }
        // Never let bridge traffic reach a vanilla client or another backend.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return;
        }
        String serverName = connection.getServerInfo().getName();
        BridgeMessage message;
        try {
            message = BridgeProtocol.decode(event.getData());
        } catch (ProtocolException e) {
            log.warn("Ignoring a malformed YuDream bridge message from '" + serverName + "': " + e.getMessage());
            return;
        }
        if (message instanceof BridgeMessage.Hello hello) {
            onSensorHello(serverName, hello);
        } else if (message instanceof BridgeMessage.Event bridgeEvent) {
            onSensorEvent(serverName, bridgeEvent);
        }
    }

    private void onSensorHello(String serverName, BridgeMessage.Hello hello) {
        if (hello.protocolVersion() != BridgeProtocol.PROTOCOL_VERSION) {
            if (warnedProtocolMismatch.add(serverName)) {
                log.warn("Downstream server '" + serverName + "' runs YuDream sensor protocol "
                        + hello.protocolVersion() + " but this proxy speaks " + BridgeProtocol.PROTOCOL_VERSION
                        + ". Update the older side; no reports will be taken from it.");
            }
            return;
        }
        warnedProtocolMismatch.remove(serverName);
        boolean first = sensors.hello(serverName, hello.modVersion(), hello.protocolVersion(), hello.players().size(), System.currentTimeMillis());
        if (first) {
            warnedAboutSensors.remove(serverName);
            log.info("YuDream sensor confirmed on downstream server '" + serverName
                    + "' (mod " + hello.modVersion() + ", protocol " + hello.protocolVersion()
                    + ", players=" + hello.players().size() + ").");
        }
        // Announce everyone the proxy believes is on this backend, which covers a proxy restart while
        // players were already online and is also the moment this backend becomes reportable.
        for (PlayerIdentity identity : presence.playersOn(serverName)) {
            reportJoin(identity, serverName);
        }
    }

    private void onSensorEvent(String serverName, BridgeMessage.Event event) {
        PlayerIdentity player = event.player();
        if (player == null) {
            return;
        }
        String kind = event.kind();
        if (BridgeMessage.KIND_JOIN.equals(kind)) {
            // Presence is the proxy's job, but a join the proxy missed is worth repairing.
            if (!presence.knows(player.uuid())) {
                log.debug("Sensor on '" + serverName + "' reported a join the proxy had not seen for " + player.name() + ".");
                presence.connected(player, serverName);
                reportJoin(player, serverName);
            }
            return;
        }
        if (BridgeMessage.KIND_QUIT.equals(kind)) {
            // The proxy owns quit reporting through DisconnectEvent / ServerConnectedEvent.
            log.debug("Sensor on '" + serverName + "' reported a quit for " + player.name() + ".");
            return;
        }
        if (BridgeMessage.KIND_ACTIVITY.equals(kind)) {
            // Activity is gated per sub-server: only the backend the player is actually on, and only
            // when that backend is reportable at all.
            if (!canReport(serverName) || !serverName.equals(presence.serverOf(player.uuid()))) {
                return;
            }
            afkTracker.markActive(player, serverName, event.at() > 0 ? event.at() : System.currentTimeMillis(), settings.bridge());
        }
    }

    // ------------------------------------------------------------------ reporting

    /**
     * Reports a join on one sub-server.
     *
     * <p>Deduplicated per (player, sub-server), so a sensor hello or a {@code sync} cannot produce a
     * second join for the same stay.
     */
    private void reportJoin(PlayerIdentity identity, String serverName) {
        if (identity == null || serverName == null || serverName.isEmpty() || !canReport(serverName)) {
            return;
        }
        Set<String> servers = reportedPresence.computeIfAbsent(identity.uuid(), key -> ConcurrentHashMap.<String>newKeySet());
        if (!servers.add(serverName)) {
            return;
        }
        long now = System.currentTimeMillis();
        afkTracker.markOnline(identity, now);
        reportQueue.submit(PlayerEventType.JOIN, PlayerEventPayload.of(identity, now, serverName));
    }

    /** Reports a quit on one sub-server; other sub-servers the player is on are left alone. */
    private void reportQuit(PlayerIdentity identity, String serverName) {
        if (identity == null || serverName == null || serverName.isEmpty() || reportQueue == null) {
            return;
        }
        Set<String> servers = reportedPresence.get(identity.uuid());
        if (servers == null || !servers.remove(serverName)) {
            return;
        }
        if (servers.isEmpty()) {
            reportedPresence.remove(identity.uuid());
            afkTracker.markOffline(identity.uuid());
        }
        reportQueue.submit(PlayerEventType.QUIT, PlayerEventPayload.of(identity, System.currentTimeMillis(), serverName));
    }

    private void reportServerSwitch(PlayerIdentity identity, String fromServer, String toServer) {
        if (!canReport(toServer)) {
            return;
        }
        reportQueue.submit(PlayerEventType.SERVER_SWITCH,
                PlayerEventPayload.of(identity, System.currentTimeMillis(), toServer));
        log.debug("Cross-server switch " + identity.name() + ": " + fromServer + " -> " + toServer);
    }

    /** Re-reports joins for everyone online and sends a grouped snapshot covering every sub-server. */
    public void syncOnlinePlayers() {
        for (String serverName : reportableServers()) {
            for (PlayerIdentity identity : presence.playersOn(serverName)) {
                reportJoin(identity, serverName);
            }
        }
        reportSnapshot();
    }

    /**
     * Reports one roster per sub-server.
     *
     * <p>A sub-server with a confirmed sensor is listed even when nobody is on it: that empty roster
     * is what tells Admin to close anyone it still believes is there. Sub-servers the sensor gate
     * excludes are omitted entirely, so the snapshot never claims to know about them.
     */
    private void reportSnapshot() {
        if (!canReport()) {
            return;
        }
        long observedAt = System.currentTimeMillis();
        List<SubServerRoster> rosters = new ArrayList<SubServerRoster>();
        for (String serverName : reportableServers()) {
            List<PlayerEventPayload> players = new ArrayList<PlayerEventPayload>();
            for (PlayerIdentity identity : presence.playersOn(serverName)) {
                players.add(PlayerEventPayload.of(identity, observedAt, serverName));
            }
            rosters.add(new SubServerRoster(serverName, players));
        }
        if (rosters.isEmpty()) {
            return;
        }
        reportQueue.submitGroupedSnapshot(rosters, observedAt);
    }

    /** The same sub-server list as {@link #reportSnapshot()}, each with an empty roster. */
    private List<SubServerRoster> emptyRosters() {
        List<SubServerRoster> rosters = new ArrayList<SubServerRoster>();
        for (String serverName : reportableServers()) {
            rosters.add(SubServerRoster.empty(serverName));
        }
        return rosters;
    }

    /** Every downstream server this bridge is allowed to report right now. */
    private List<String> reportableServers() {
        List<String> names = new ArrayList<String>();
        for (com.velocitypowered.api.proxy.server.RegisteredServer registered : server.getAllServers()) {
            String name = registered.getServerInfo().getName();
            if (canReport(name)) {
                names.add(name);
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    private void tickAfk() {
        if (!canReport()) {
            return;
        }
        long now = System.currentTimeMillis();
        // AFK state is keyed by player, and a player is on exactly one backend at a time, so the
        // tracker only needs the union of the reportable backends' players to stay bounded.
        List<UUID> tracked = new ArrayList<UUID>();
        for (String serverName : reportableServers()) {
            List<PlayerIdentity> online = presence.playersOn(serverName);
            for (PlayerIdentity identity : online) {
                tracked.add(identity.uuid());
            }
            afkTracker.tick(online, serverName, now, settings.bridge());
            warnIfSensorStale(serverName, online.size());
        }
        afkTracker.retainOnly(tracked);
    }

    private void warnIfSensorStale(String serverName, int playersOnServer) {
        if (serverName == null || serverName.isEmpty() || !sensors.isConfirmed(serverName)) {
            return;
        }
        long timeoutMs = settings.sensorTimeoutSeconds() * 1000L;
        if (playersOnServer > 0 && sensors.isStale(serverName, timeoutMs, System.currentTimeMillis())) {
            if (warnedAboutSensors.add(serverName)) {
                log.warn("No hello from the YuDream sensor on '" + serverName + "' for over "
                        + settings.sensorTimeoutSeconds() + "s while " + playersOnServer
                        + " player(s) are online there. The mod may have been removed or is failing;"
                        + " presence reports continue, AFK transitions do not.");
            }
        } else {
            warnedAboutSensors.remove(serverName);
        }
    }

    /**
     * Asks every backend's sensors to introduce themselves again.
     *
     * <p>One probe on any player connection reaches the backend once and covers every sensor there,
     * so this walks the connected players and probes each distinct backend a single time.
     */
    private void probeBackends() {
        byte[] probe = BridgeProtocol.encode(new BridgeMessage.Probe(VERSION, BridgeProtocol.PROTOCOL_VERSION));
        Set<String> probed = new java.util.HashSet<String>();
        for (Player player : server.getAllPlayers()) {
            Optional<ServerConnection> connection = player.getCurrentServer();
            if (connection.isEmpty()) {
                continue;
            }
            String serverName = connection.get().getServerInfo().getName();
            if (!probed.add(serverName)) {
                continue;
            }
            connection.get().sendPluginMessage(channel, probe);
        }
    }

    // ------------------------------------------------------------------ config

    /** Reloads the config file and rebuilds the report pipeline. */
    public synchronized void reload() {
        try {
            ConfigFile file = ConfigFile.loadOrCreate(configPath, ConfigTemplate.render());
            VelocitySettings loaded = VelocitySettings.from(file);
            VelocitySettings.applyDefaults(file, loaded);
            file.save(configPath);
            settings = VelocitySettings.from(file);
        } catch (IOException e) {
            log.warn("Could not read " + configPath + "; keeping the previous settings: " + e.getMessage(), e);
            return;
        }

        BridgeSettings bridge = settings.bridge();
        if (reportQueue != null) {
            reportQueue.shutdown(bridge.getFlushTimeoutMs());
        }
        apiClient = new YudreamApiClient(bridge, "YudreamMinecraftServerVelocity/" + VERSION);
        ReportJournal journal = new ReportJournal(
                bridge.isPersistQueue() ? dataDirectory.resolve(bridge.getQueueFile()) : null, log);
        reportQueue = new ReportQueue(log, bridge, apiClient, journal, this::canReport);
        afkTracker = new AfkTracker(reportQueue::submit);

        seedAfkState();
        reschedule();
        checkConfiguration();
        probeBackends();
        if (bridge.isSyncOnlineOnEnable()) {
            syncOnlinePlayers();
        }
    }

    private void seedAfkState() {
        long now = System.currentTimeMillis();
        for (String serverName : reportableServers()) {
            for (PlayerIdentity identity : presence.playersOn(serverName)) {
                afkTracker.markOnline(identity, now);
            }
        }
    }

    private void checkConfiguration() {
        BridgeSettings bridge = settings.bridge();
        if (!bridge.isEnabled()) {
            log.warn("The YuDream bridge is disabled by config (enabled=false).");
        } else if (!bridge.isConfigured()) {
            log.warn("The YuDream bridge is not fully configured. Set api.base-url, api.server-id and api.api-key in " + configPath);
        }
        if (!settings.hasTarget()) {
            if (!warnedAboutTarget) {
                warnedAboutTarget = true;
                log.info("No default downstream server is marked. Set target.server in " + configPath
                        + " or run /yudreammc target <server> to record which backend is the login entry;"
                        + " every downstream server is reported either way.");
            }
        } else {
            warnedAboutTarget = false;
        }
        if (settings.requireSensor() && bridge.isEnabled() && bridge.isConfigured()) {
            List<String> pending = new ArrayList<String>();
            for (com.velocitypowered.api.proxy.server.RegisteredServer registered : server.getAllServers()) {
                String name = registered.getServerInfo().getName();
                if (!sensors.isConfirmed(name)) {
                    pending.add(name);
                }
            }
            if (!pending.isEmpty()) {
                log.info("Waiting for the YuDream sensor on " + pending
                        + " before reporting those sub-servers (target.require-sensor=true)."
                        + " Every other sub-server keeps reporting.");
            }
        }
    }

    private void reschedule() {
        cancelTasks();
        snapshotTask = server.getScheduler().buildTask(this, this::reportSnapshot)
                .repeat(Duration.ofSeconds(settings.snapshotIntervalSeconds()))
                .schedule();
        long afkSeconds = Math.max(settings.bridge().getAfkCheckIntervalMs() / 1000L, 1L);
        afkTask = server.getScheduler().buildTask(this, this::tickAfk)
                .repeat(Duration.ofSeconds(afkSeconds))
                .schedule();
        probeTask = server.getScheduler().buildTask(this, this::probeBackends)
                .repeat(Duration.ofSeconds(settings.probeIntervalSeconds()))
                .schedule();
        if (settings.topologyEnabled()) {
            // Report once shortly after start, then on the configured cadence.
            topologyTask = server.getScheduler().buildTask(this, this::reportTopology)
                    .delay(Duration.ofSeconds(5))
                    .repeat(Duration.ofSeconds(settings.topologyIntervalSeconds()))
                    .schedule();
        }
    }

    private void cancelTasks() {
        for (ScheduledTask task : new ScheduledTask[]{snapshotTask, afkTask, probeTask, topologyTask}) {
            if (task != null) {
                task.cancel();
            }
        }
        snapshotTask = null;
        afkTask = null;
        probeTask = null;
        topologyTask = null;
    }

    /**
     * Records which downstream server is the login entry, and persists it.
     *
     * <p>This is a marker only. Reporting covers every sub-server, so changing this value must not
     * quit, join or otherwise re-reconcile anything: it used to, back when one target decided what
     * was uploaded, and doing that now would silently suppress a sub-server's data.
     *
     * @return the previous target, or an empty string when none was set
     */
    public synchronized String setTarget(String newTarget) {
        String previous = settings.targetServer();
        if (newTarget.equals(previous)) {
            return previous;
        }
        try {
            ConfigFile file = ConfigFile.load(configPath);
            file.set("target.server", newTarget);
            file.save(configPath);
        } catch (IOException e) {
            log.warn("Could not persist the default downstream server to " + configPath + ": " + e.getMessage(), e);
            return previous;
        }
        settings = settings.with(settings.bridge(), newTarget);
        warnedAboutTarget = false;
        log.info("Default downstream server is now '" + (newTarget.isEmpty() ? "<unset>" : newTarget)
                + "'. Reporting is unaffected: every sub-server is still reported.");
        return previous;
    }

    // ------------------------------------------------------------------ accessors used by the command

    /**
     * Whether this sub-server may be reported right now.
     *
     * <p>The sensor gate is applied here and nowhere else, which is what makes it per sub-server: a
     * backend whose sensor has not said hello is skipped while every other backend keeps reporting.
     */
    public boolean canReport(String serverName) {
        if (serverName == null || serverName.isEmpty() || !isConfiguredForReporting()) {
            return false;
        }
        return !settings.requireSensor() || sensors.isConfirmed(serverName);
    }

    /**
     * Whether <em>anything</em> can be reported right now.
     *
     * <p>Used as the queue's coarse gate and by {@code /yudreammc status}. It deliberately no longer
     * depends on {@code target.server}: with the per-sub-server sensor gate it is true as soon as one
     * known backend is reportable.
     */
    public boolean canReport() {
        if (!isConfiguredForReporting()) {
            return false;
        }
        if (!settings.requireSensor()) {
            return true;
        }
        for (com.velocitypowered.api.proxy.server.RegisteredServer registered : server.getAllServers()) {
            if (sensors.isConfirmed(registered.getServerInfo().getName())) {
                return true;
            }
        }
        return false;
    }

    /** Enabled plus a complete Admin endpoint. Independent of any sub-server or sensor. */
    private boolean isConfiguredForReporting() {
        BridgeSettings bridge = settings.bridge();
        return bridge.isEnabled() && bridge.isConfigured();
    }

    public VelocitySettings settings() {
        return settings;
    }

    public ProxyServer proxy() {
        return server;
    }

    public LogSink logSink() {
        return log;
    }

    public SensorRegistry sensors() {
        return sensors;
    }

    public ProxyPresence presence() {
        return presence;
    }

    public int queueSize() {
        return reportQueue == null ? 0 : reportQueue.size();
    }

    public int outstandingReports() {
        return reportQueue == null ? 0 : reportQueue.outstandingCount();
    }

    public int reportedPlayerCount() {
        return reportedPresence.size();
    }

    public int afkTrackedPlayers() {
        return afkTracker == null ? 0 : afkTracker.trackedPlayers();
    }

    public Path configPath() {
        return configPath;
    }

    /** Names of every downstream server Velocity knows about, with players, sensor and report state. */
    public List<String> describeServers() {
        List<String> lines = new ArrayList<String>();
        for (com.velocitypowered.api.proxy.server.RegisteredServer registered : server.getAllServers()) {
            String name = registered.getServerInfo().getName();
            lines.add(name + " (players=" + presence.countOn(name)
                    + (sensors.isConfirmed(name) ? ", sensor=yes" : ", sensor=no")
                    + ", reporting=" + (canReport(name) ? "yes" : "no") + ")");
        }
        lines.sort(String::compareTo);
        return lines;
    }

    /** Every downstream server Velocity knows about, as the topology report describes them. */
    public List<SubServerInfo> subServers() {
        List<String> tryOrder = new ArrayList<String>();
        try {
            List<String> configured = server.getConfiguration().getAttemptConnectionOrder();
            if (configured != null) {
                tryOrder.addAll(configured);
            }
        } catch (RuntimeException ignored) {
            // Older Velocity builds may not expose the try list; the default flag is only a label.
        }
        List<SubServerInfo> items = new ArrayList<SubServerInfo>();
        for (com.velocitypowered.api.proxy.server.RegisteredServer registered : server.getAllServers()) {
            com.velocitypowered.api.proxy.server.ServerInfo info = registered.getServerInfo();
            String name = info.getName();
            items.add(new SubServerInfo(
                    name,
                    String.valueOf(info.getAddress()),
                    registered.getPlayersConnected().size(),
                    sensors.isConfirmed(name),
                    !tryOrder.isEmpty() && tryOrder.get(0).equals(name)));
        }
        items.sort(Comparator.comparing(SubServerInfo::name));
        return items;
    }

    /**
     * The addresses this report advertises, used by Admin to find the matching server entry.
     *
     * <p>Falls back to Velocity's bind address, which is normally {@code 0.0.0.0} and therefore
     * unmatched — hence {@code topology.addresses}.
     */
    public List<String> advertisedAddresses() {
        if (!settings.topologyAddresses().isEmpty()) {
            return settings.topologyAddresses();
        }
        List<String> fallback = new ArrayList<String>();
        try {
            InetSocketAddress bound = server.getBoundAddress();
            if (bound != null && bound.getHostString() != null && !bound.getHostString().isEmpty()) {
                fallback.add(bound.getHostString() + ":" + bound.getPort());
            }
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }

    /**
     * Reports this proxy's downstream-server list to YuDream Admin.
     *
     * <p>A proxy's Server List Ping describes the proxy and never its backends, so this is the only
     * way Admin can learn what a group server contains. When no server id is configured the report is
     * matched by address instead, which is what lets an operator resolve a group server by installing
     * the bridge and nothing else.
     */
    public void reportTopology() {
        if (!settings.topologyEnabled() || !settings.bridge().hasCredentials()) {
            return;
        }
        final YudreamApiClient client = apiClient;
        if (client == null) {
            return;
        }
        final List<SubServerInfo> servers = subServers();
        final boolean bound = !settings.bridge().getServerId().isEmpty();
        final String body = client.topologyBody("velocity", server.getVersion().getVersion(),
                advertisedAddresses(), servers, System.currentTimeMillis());
        server.getScheduler().buildTask(this, () -> {
            try {
                HttpResult result = bound ? client.reportTopology(body) : client.reportTopologyByAddress(body);
                if (result.isSuccess()) {
                    log.info("Reported " + servers.size() + " downstream server(s) to YuDream Admin (http="
                            + result.statusCode() + ").");
                } else if (result.statusCode() == 404 || result.statusCode() == 400) {
                    log.warn("YuDream Admin could not match the proxy topology (HTTP " + result.statusCode()
                            + " " + result.trimmedBody() + "). Set topology.addresses to the address players"
                            + " connect to, or set api.server-id to bind this report to one Admin server entry.");
                } else {
                    log.warn("Could not report the proxy topology: HTTP " + result.statusCode()
                            + " " + result.trimmedBody() + ".");
                }
            } catch (Exception e) {
                log.warn("Could not report the proxy topology: " + e.getMessage(), e);
            }
        }).schedule();
    }

    /** Queries YuDream Admin asynchronously and hands the result to the callback. */
    public void requestRemoteStatus(Consumer<String> callback) {
        if (!settings.bridge().isConfigured()) {
            callback.accept("YuDream Admin is not configured (api.base-url / api.server-id / api.api-key).");
            return;
        }
        final YudreamApiClient client = apiClient;
        server.getScheduler().buildTask(this, () -> {
            String message;
            try {
                HttpResult result = client.players(1, 100);
                if (result.isSuccess()) {
                    PlayerSummary summary = PlayerSummary.parse(result.body());
                    message = "Remote players: total=" + summary.total()
                            + ", online=" + summary.online()
                            + ", afk=" + summary.afk()
                            + ", http=" + result.statusCode();
                } else if (result.statusCode() == 400 || result.statusCode() == 404 || result.statusCode() == 405) {
                    // Not every Admin deployment exposes the query side of the plugin. The report
                    // endpoints are a different route set and keep working.
                    message = "This YuDream Admin deployment has no players query endpoint"
                            + " (GET .../players returned HTTP " + result.statusCode()
                            + "); event reporting is unaffected.";
                } else {
                    message = "Remote status failed: HTTP " + result.statusCode() + " " + result.trimmedBody();
                }
            } catch (Exception e) {
                message = "Remote status failed: " + e.getMessage();
            }
            callback.accept(message);
        }).schedule();
    }

    private PlayerIdentity identity(Player player) {
        return new PlayerIdentity(player.getUniqueId(), player.getUsername());
    }
}
