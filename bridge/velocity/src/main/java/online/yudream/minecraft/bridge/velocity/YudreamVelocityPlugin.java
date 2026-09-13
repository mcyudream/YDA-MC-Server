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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * <p>{@code target.server} selects which downstream server is reported, so a whole proxy network maps
 * onto one YuDream Admin server id. It can be changed at runtime with {@code /yudreammc target}.
 */
@Plugin(
        id = YudreamVelocityPlugin.PLUGIN_ID,
        name = "YuDream Minecraft Bridge",
        version = YudreamVelocityPlugin.VERSION,
        description = "Reports a selected downstream server's player activity to YuDream Admin.",
        authors = {"YuDream"},
        url = "https://github.com/mcyudream/YDA-MC-Server")
public final class YudreamVelocityPlugin {

    public static final String PLUGIN_ID = "yudream-velocity";
    /** Keep in sync with the {@code version} above; the annotation needs a literal. */
    public static final String VERSION = "1.0.0";

    private static final String CONFIG_FILE_NAME = "config.properties";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final ChannelIdentifier channel = MinecraftChannelIdentifier.create(
            BridgeProtocol.CHANNEL_NAMESPACE, BridgeProtocol.CHANNEL_PATH);

    private final ProxyPresence presence = new ProxyPresence();
    private final SensorRegistry sensors = new SensorRegistry();
    /** Players currently reported to YuDream Admin as online on the target backend. */
    private final Set<UUID> reportedPresent = ConcurrentHashMap.newKeySet();
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
    private boolean warnedAboutTarget;
    private boolean warnedAboutSensor;

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

        logger.info("YuDream Velocity bridge {} started. Channel={}, target downstream='{}'.",
                VERSION, BridgeProtocol.CHANNEL, settings.targetServer().isEmpty() ? "<unset>" : settings.targetServer());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        cancelTasks();
        BridgeSettings bridge = settings.bridge();
        if (bridge.isReportQuitOnDisable() && reportQueue != null) {
            for (UUID uuid : new ArrayList<UUID>(reportedPresent)) {
                PlayerIdentity identity = presence.identityOf(uuid);
                if (identity != null) {
                    reportQueue.submit(PlayerEventType.QUIT, PlayerEventPayload.of(identity, System.currentTimeMillis(), settings.targetServer()));
                }
            }
            reportedPresent.clear();
        }
        if (canReport()) {
            // An empty snapshot tells YuDream Admin this server has nobody online, which is how it
            // reconciles quit events missed during a crash.
            reportQueue.submitSnapshot(Collections.<PlayerEventPayload>emptyList(), System.currentTimeMillis(), settings.targetServer());
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

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        PlayerIdentity identity = identity(player);
        String newServer = event.getServer().getServerInfo().getName();
        String previousServer = event.getPreviousServer()
                .map(registered -> registered.getServerInfo().getName())
                .orElse(null);

        presence.connected(identity, newServer);

        String target = settings.targetServer();
        if (target.isEmpty()) {
            return;
        }
        boolean wasOnTarget = target.equals(previousServer);
        boolean nowOnTarget = target.equals(newServer);
        if (wasOnTarget && !nowOnTarget) {
            reportQuit(identity, previousServer);
        } else if (nowOnTarget) {
            reportJoin(identity, newServer);
        } else if (previousServer != null && !previousServer.equals(newServer)
                && settings.bridge().isServerSwitchEventEnabled()) {
            reportServerSwitch(identity, previousServer, newServer);
        }
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
        if (serverName != null && serverName.equals(settings.targetServer())) {
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
            warnedAboutSensor = false;
            log.info("YuDream sensor confirmed on downstream server '" + serverName
                    + "' (mod " + hello.modVersion() + ", protocol " + hello.protocolVersion()
                    + ", players=" + hello.players().size() + ").");
        }
        if (!serverName.equals(settings.targetServer())) {
            return;
        }
        // Announce everyone the proxy believes is on the target, which covers a proxy restart while
        // players were already online.
        for (PlayerIdentity identity : presence.playersOn(serverName)) {
            reportJoin(identity, serverName);
        }
    }

    private void onSensorEvent(String serverName, BridgeMessage.Event event) {
        if (!serverName.equals(settings.targetServer())) {
            return;
        }
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
            if (!canReport() || !serverName.equals(presence.serverOf(player.uuid()))) {
                return;
            }
            afkTracker.markActive(player, serverName, event.at() > 0 ? event.at() : System.currentTimeMillis(), settings.bridge());
        }
    }

    // ------------------------------------------------------------------ reporting

    private void reportJoin(PlayerIdentity identity, String serverName) {
        if (identity == null || !canReport()) {
            return;
        }
        if (!reportedPresent.add(identity.uuid())) {
            return;
        }
        long now = System.currentTimeMillis();
        afkTracker.markOnline(identity, now);
        reportQueue.submit(PlayerEventType.JOIN, PlayerEventPayload.of(identity, now, serverName));
    }

    private void reportQuit(PlayerIdentity identity, String serverName) {
        if (identity == null || reportQueue == null) {
            return;
        }
        if (!reportedPresent.remove(identity.uuid())) {
            return;
        }
        afkTracker.markOffline(identity.uuid());
        reportQueue.submit(PlayerEventType.QUIT, PlayerEventPayload.of(identity, System.currentTimeMillis(), serverName));
    }

    private void reportServerSwitch(PlayerIdentity identity, String fromServer, String toServer) {
        if (!canReport()) {
            return;
        }
        reportQueue.submit(PlayerEventType.SERVER_SWITCH,
                PlayerEventPayload.of(identity, System.currentTimeMillis(), toServer));
        log.debug("Cross-server switch " + identity.name() + ": " + fromServer + " -> " + toServer);
    }

    /** Re-reports joins for everyone already on the target and sends a snapshot. */
    public void syncOnlinePlayers() {
        String target = settings.targetServer();
        if (target.isEmpty()) {
            return;
        }
        for (PlayerIdentity identity : presence.playersOn(target)) {
            reportJoin(identity, target);
        }
        reportSnapshot();
    }

    private void reportSnapshot() {
        if (!canReport()) {
            return;
        }
        String target = settings.targetServer();
        long observedAt = System.currentTimeMillis();
        List<PlayerEventPayload> players = new ArrayList<PlayerEventPayload>();
        for (PlayerIdentity identity : presence.playersOn(target)) {
            players.add(PlayerEventPayload.of(identity, observedAt, target));
        }
        reportQueue.submitSnapshot(players, observedAt, target);
    }

    private void tickAfk() {
        String target = settings.targetServer();
        if (!canReport()) {
            return;
        }
        List<PlayerIdentity> online = presence.playersOn(target);
        // Drop AFK state for anyone the authoritative presence view no longer has on the target, so
        // the tracker cannot grow across target switches.
        List<UUID> tracked = new ArrayList<UUID>(online.size());
        for (PlayerIdentity identity : online) {
            tracked.add(identity.uuid());
        }
        afkTracker.retainOnly(tracked);
        afkTracker.tick(online, target, System.currentTimeMillis(), settings.bridge());
        warnIfSensorStale(online.size());
    }

    private void warnIfSensorStale(int playersOnTarget) {
        String target = settings.targetServer();
        if (target.isEmpty() || !sensors.isConfirmed(target)) {
            return;
        }
        long timeoutMs = settings.sensorTimeoutSeconds() * 1000L;
        if (playersOnTarget > 0 && sensors.isStale(target, timeoutMs, System.currentTimeMillis())) {
            if (!warnedAboutSensor) {
                warnedAboutSensor = true;
                log.warn("No hello from the YuDream sensor on '" + target + "' for over "
                        + settings.sensorTimeoutSeconds() + "s while " + playersOnTarget
                        + " player(s) are online there. The mod may have been removed or is failing;"
                        + " presence reports continue, AFK transitions do not.");
            }
        } else {
            warnedAboutSensor = false;
        }
    }

    /** Asks the target backend's sensors to introduce themselves again. */
    private void probeTarget() {
        String target = settings.targetServer();
        if (target.isEmpty()) {
            return;
        }
        byte[] probe = BridgeProtocol.encode(new BridgeMessage.Probe(VERSION, BridgeProtocol.PROTOCOL_VERSION));
        for (Player player : server.getAllPlayers()) {
            Optional<ServerConnection> connection = player.getCurrentServer();
            if (connection.isEmpty() || !target.equals(connection.get().getServerInfo().getName())) {
                continue;
            }
            // One probe on any player connection reaches the backend once and covers every sensor.
            connection.get().sendPluginMessage(channel, probe);
            return;
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
        probeTarget();
        if (bridge.isSyncOnlineOnEnable()) {
            syncOnlinePlayers();
        }
    }

    private void seedAfkState() {
        String target = settings.targetServer();
        if (target.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (PlayerIdentity identity : presence.playersOn(target)) {
            afkTracker.markOnline(identity, now);
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
                log.warn("No downstream server is selected. Set target.server in " + configPath
                        + " or run /yudreammc target <server>. Nothing will be reported until then.");
            }
        } else {
            warnedAboutTarget = false;
            if (settings.requireSensor() && !sensors.isConfirmed(settings.targetServer())) {
                log.info("Waiting for the YuDream sensor on '" + settings.targetServer()
                        + "' to say hello before reporting anything (target.require-sensor=true).");
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
        probeTask = server.getScheduler().buildTask(this, this::probeTarget)
                .repeat(Duration.ofSeconds(settings.probeIntervalSeconds()))
                .schedule();
    }

    private void cancelTasks() {
        for (ScheduledTask task : new ScheduledTask[]{snapshotTask, afkTask, probeTask}) {
            if (task != null) {
                task.cancel();
            }
        }
        snapshotTask = null;
        afkTask = null;
        probeTask = null;
    }

    /**
     * Switches the reported downstream server, persists it and reconciles YuDream Admin: everybody
     * reported on the previous target is quit, everybody on the new one is joined.
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
            log.warn("Could not persist the new target downstream server to " + configPath + ": " + e.getMessage(), e);
            return previous;
        }
        // Quit the previous target's players *before* the new target is applied. canReport() is
        // evaluated against the target that is still current, so switching to a backend that has no
        // confirmed sensor cannot silently swallow the quits and leave Admin with ghost players.
        for (UUID uuid : new ArrayList<UUID>(reportedPresent)) {
            PlayerIdentity identity = presence.identityOf(uuid);
            reportQuit(identity, previous);
        }
        reportedPresent.clear();

        settings = settings.with(settings.bridge(), newTarget);

        if (!newTarget.isEmpty()) {
            warnedAboutTarget = false;
            for (PlayerIdentity identity : presence.playersOn(newTarget)) {
                reportJoin(identity, newTarget);
            }
            reportSnapshot();
            probeTarget();
        }
        return previous;
    }

    // ------------------------------------------------------------------ accessors used by the command

    public boolean canReport() {
        BridgeSettings bridge = settings.bridge();
        if (!bridge.isEnabled() || !bridge.isConfigured()) {
            return false;
        }
        if (!settings.hasTarget()) {
            return false;
        }
        return !settings.requireSensor() || sensors.isConfirmed(settings.targetServer());
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
        return reportedPresent.size();
    }

    public int afkTrackedPlayers() {
        return afkTracker == null ? 0 : afkTracker.trackedPlayers();
    }

    public Path configPath() {
        return configPath;
    }

    /** Names of every downstream server Velocity knows about, with the players currently on each. */
    public List<String> describeServers() {
        List<String> lines = new ArrayList<String>();
        for (com.velocitypowered.api.proxy.server.RegisteredServer registered : server.getAllServers()) {
            String name = registered.getServerInfo().getName();
            lines.add(name + " (players=" + presence.countOn(name)
                    + (sensors.isConfirmed(name) ? ", sensor=yes" : ", sensor=no") + ")");
        }
        lines.sort(String::compareTo);
        return lines;
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
