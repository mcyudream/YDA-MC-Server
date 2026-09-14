package online.yudream.minecraft.bridge.fabric;

import online.yudream.minecraft.bridge.common.afk.AfkTracker;
import online.yudream.minecraft.bridge.common.config.BridgeSettings;
import online.yudream.minecraft.bridge.common.http.YudreamApiClient;
import online.yudream.minecraft.bridge.common.log.LogSink;
import online.yudream.minecraft.bridge.common.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.common.model.PlayerEventType;
import online.yudream.minecraft.bridge.common.model.PlayerIdentity;
import online.yudream.minecraft.bridge.common.queue.ReportJournal;
import online.yudream.minecraft.bridge.common.queue.ReportQueue;
import online.yudream.minecraft.bridge.fabric.config.FabricSettings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Standalone reporting for a Fabric server that has no proxy.
 *
 * <p>This is the exact counterpart of the Velocity plugin's reporting half: the same
 * {@link ReportQueue}, {@link AfkTracker} and {@link YudreamApiClient} from the shared bridge core,
 * driven by the same events. The only differences are that presence comes from this server's own
 * player list rather than from a proxy, and that every report carries an empty sub-server name — a
 * single server has no sub-server dimension, so Admin files the data under its {@code default} bucket.
 *
 * <p>Deliberately free of Minecraft types so the pipeline can be reasoned about and unit tested
 * without a server; the platform wiring lives in {@link FabricSensor}.
 */
public final class FabricReporter {

    private final Path dataDirectory;
    private final LogSink log;

    private volatile FabricSettings settings;
    private volatile YudreamApiClient apiClient;
    private volatile ReportQueue reportQueue;
    private volatile AfkTracker afkTracker;

    public FabricReporter(FabricSettings settings, Path dataDirectory, LogSink log) {
        this.settings = settings;
        this.dataDirectory = dataDirectory;
        this.log = log;
    }

    /**
     * Builds the pipeline for the current settings, replacing any previous one.
     *
     * <p>Called on start and on every reload, so a config change never leaves a half-updated queue
     * behind: the old queue is flushed and shut down before the new one takes over.
     */
    public synchronized void start(FabricSettings loaded) {
        this.settings = loaded;
        BridgeSettings bridge = loaded.bridge();

        if (reportQueue != null) {
            reportQueue.shutdown(bridge.getFlushTimeoutMs());
        }
        apiClient = new YudreamApiClient(bridge, "YudreamMinecraftServerFabric/" + YudreamFabricMod.VERSION);
        ReportJournal journal = new ReportJournal(
                bridge.isPersistQueue() ? dataDirectory.resolve(bridge.getQueueFile()) : null, log);
        reportQueue = new ReportQueue(log, bridge, apiClient, journal, this::canReport);
        afkTracker = new AfkTracker(reportQueue::submit);

        if (!loaded.isEnabled()) {
            log.warn("YuDream bridge is disabled by config (enabled=false); nothing will be reported.");
        } else if (!bridge.isConfigured()) {
            log.warn("YuDream bridge is not fully configured for standalone mode. Set api.base-url,"
                    + " api.server-id and api.api-key in the Fabric config, or set mode=downstream if this"
                    + " server sits behind a Velocity proxy.");
        } else {
            log.info("YuDream standalone reporting ready: " + describeTarget());
        }
    }

    /** Whether this server may upload right now. Wired into the queue as its gate. */
    public boolean canReport() {
        FabricSettings current = settings;
        return current != null && current.isEnabled() && current.bridge().isConfigured();
    }

    public boolean isConfigured() {
        FabricSettings current = settings;
        return current != null && current.bridge().isConfigured();
    }

    public FabricSettings settings() {
        return settings;
    }

    public int queuedReports() {
        ReportQueue queue = reportQueue;
        return queue == null ? -1 : queue.outstandingCount();
    }

    /** One line for {@code /yudreammc status}. */
    public String describeTarget() {
        FabricSettings current = settings;
        BridgeSettings bridge = current == null ? BridgeSettings.defaults() : current.bridge();
        return "Admin " + (bridge.getBaseUrl().isEmpty() ? "<unset>" : bridge.getBaseUrl())
                + ", serverId=" + (bridge.getServerId().isEmpty() ? "<unset>" : bridge.getServerId())
                + ", afk=" + (bridge.isAfkEnabled() ? bridge.getAfkTimeoutMs() / 1000L + "s" : "off")
                + ", snapshot=" + (current == null ? 0 : current.snapshotIntervalSeconds()) + "s"
                + ", queued=" + queuedReports();
    }

    // ------------------------------------------------------------------ player events

    public void join(PlayerIdentity player, long now) {
        if (player == null || !canReport()) {
            return;
        }
        afkTracker.markOnline(player, now);
        submit(PlayerEventType.JOIN, player, now);
    }

    public void quit(PlayerIdentity player, long now) {
        if (player == null || !canReport()) {
            return;
        }
        submit(PlayerEventType.QUIT, player, now);
        afkTracker.markOffline(player.uuid());
    }

    /** A chat, move, interact or command signal; extends a session or ends an AFK spell. */
    public void activity(PlayerIdentity player, long now) {
        if (player == null || !canReport()) {
            return;
        }
        afkTracker.markActive(player, "", now, settings.bridge());
    }

    /** Re-announces everyone already online, which covers a reload or a restart mid-session. */
    public void syncOnline(Collection<PlayerIdentity> online, long now) {
        if (!canReport()) {
            return;
        }
        List<PlayerIdentity> players = new ArrayList<PlayerIdentity>(online);
        for (PlayerIdentity player : players) {
            afkTracker.markOnline(player, now);
            submit(PlayerEventType.JOIN, player, now);
        }
        reportSnapshot(players, now);
    }

    /** Re-reports the whole roster so Admin can reconcile anything it missed. */
    public void reportSnapshot(Collection<PlayerIdentity> online, long now) {
        ReportQueue queue = reportQueue;
        if (!canReport() || queue == null) {
            return;
        }
        List<PlayerEventPayload> players = new ArrayList<PlayerEventPayload>();
        for (PlayerIdentity player : online) {
            players.add(PlayerEventPayload.of(player, now, ""));
        }
        // An empty roster is a real signal: it tells Admin nobody is left here, which is how a quit
        // missed during a crash gets repaired.
        queue.submitSnapshot(players, now, "");
    }

    /** Advances the AFK state machine and forgets anyone no longer online. */
    public void tickAfk(Collection<PlayerIdentity> online, long now) {
        if (!canReport()) {
            return;
        }
        List<PlayerIdentity> players = new ArrayList<PlayerIdentity>(online);
        afkTracker.tick(players, "", now, settings.bridge());
        List<UUID> uuids = new ArrayList<UUID>();
        for (PlayerIdentity player : players) {
            uuids.add(player.uuid());
        }
        afkTracker.retainOnly(uuids);
    }

    /**
     * Closes the roster when this server stops.
     *
     * <p>Quits are only sent when {@code shutdown.report-quit-on-disable} asks for them: a server that
     * is killed outright never reaches this method, which is exactly why the periodic snapshot exists.
     * The closing snapshot is always sent, so Admin never keeps a phantom online player for long.
     */
    public synchronized void shutdown(Collection<PlayerIdentity> online) {
        if (reportQueue == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (canReport() && settings.bridge().isReportQuitOnDisable()) {
            for (PlayerIdentity player : new ArrayList<PlayerIdentity>(online)) {
                submit(PlayerEventType.QUIT, player, now);
            }
        }
        reportSnapshot(online, now);
        reportQueue.shutdown(settings.bridge().getFlushTimeoutMs());
        reportQueue = null;
        afkTracker = null;
    }

    private void submit(PlayerEventType type, PlayerIdentity player, long now) {
        ReportQueue queue = reportQueue;
        if (queue != null) {
            queue.submit(type, PlayerEventPayload.of(player, now, ""));
        }
    }
}
