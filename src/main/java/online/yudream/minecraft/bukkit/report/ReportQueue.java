package online.yudream.minecraft.bukkit.report;

import online.yudream.minecraft.bukkit.api.HttpResult;
import online.yudream.minecraft.bukkit.api.YudreamApiClient;
import online.yudream.minecraft.bukkit.config.YudreamConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ReportQueue {

    /**
     * An extra check applied before anything is queued, on top of the enabled/configured pair.
     *
     * <p>Declared explicitly so a background producer — the AFK tracker, the snapshot task, the
     * shutdown path — cannot bypass a mode decision it has no way of seeing. Without it, a server in
     * downstream mode would keep uploading to YuDream Admin through the AFK timer even though the
     * proxy is supposed to be the only uploader.
     */
    public interface ReportGate {
        boolean canReport();
    }

    private final JavaPlugin plugin;
    private final YudreamApiClient client;
    private final YudreamConfig config;
    private final ReportGate gate;
    private final BlockingQueue<ReportTask> queue;
    private final ExecutorService executor;
    private volatile boolean running = true;

    public ReportQueue(JavaPlugin plugin, YudreamApiClient client, YudreamConfig config) {
        this(plugin, client, config, null);
    }

    public ReportQueue(JavaPlugin plugin, YudreamApiClient client, YudreamConfig config, ReportGate gate) {
        this.plugin = plugin;
        this.client = client;
        this.config = config;
        this.gate = gate;
        this.queue = new ArrayBlockingQueue<ReportTask>(config.getQueueCapacity());
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "YuDream-Minecraft-Reporter");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.executor.execute(new Runnable() {
            @Override
            public void run() {
                workerLoop();
            }
        });
    }

    public void submit(PlayerEventType type, PlayerEventPayload payload) {
        if (!canSubmit()) {
            return;
        }
        ReportTask task = ReportTask.event(type, payload);
        enqueue(task);
    }

    public void submitSnapshot(Collection<PlayerEventPayload> players, long observedAt) {
        if (!canSubmit()) {
            return;
        }
        enqueue(ReportTask.snapshot(players, observedAt));
    }

    private boolean canSubmit() {
        if (!config.isEnabled() || !config.isConfigured()) {
            return false;
        }
        return gate == null || gate.canReport();
    }

    private void enqueue(ReportTask task) {
        if (queue.offer(task)) {
            if (config.isLogQueued()) {
                plugin.getLogger().info("Queued YuDream report: " + describe(task) + ", queueSize=" + queue.size());
            }
            return;
        }
        if (config.isLogFailures()) {
            plugin.getLogger().warning("YuDream report queue is full; dropped " + describe(task));
        }
    }

    public int size() {
        return queue.size();
    }

    public void shutdown(long timeoutMs) {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Math.max(timeoutMs, 1L), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void workerLoop() {
        while (running || !queue.isEmpty()) {
            try {
                ReportTask task = queue.poll(500L, TimeUnit.MILLISECONDS);
                if (task != null) {
                    sendWithRetry(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Unexpected YuDream report worker error", t);
            }
        }
    }

    private void sendWithRetry(ReportTask task) throws InterruptedException {
        int attempts = config.getRetryAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long startedAt = System.currentTimeMillis();
            try {
                if (config.isLogAttempts()) {
                    plugin.getLogger().info("Sending YuDream report: " + describe(task)
                            + ", attempt=" + attempt + "/" + attempts);
                }
                HttpResult result = task.snapshot
                        ? client.snapshot(task.players, task.observedAt)
                        : client.report(task.type, task.payload);
                long elapsedMs = System.currentTimeMillis() - startedAt;
                if (result.isSuccess()) {
                    if (config.isLogSuccess()) {
                        plugin.getLogger().info("YuDream report success: " + describe(task)
                                + ", http=" + result.getStatusCode()
                                + ", attempt=" + attempt + "/" + attempts
                                + ", elapsedMs=" + elapsedMs);
                    }
                    return;
                }
                if (config.isLogAttempts() && attempt < attempts) {
                    plugin.getLogger().warning("YuDream report attempt failed: " + describe(task)
                            + ", http=" + result.getStatusCode()
                            + ", attempt=" + attempt + "/" + attempts
                            + ", elapsedMs=" + elapsedMs
                            + ", response=" + trim(result.getBody()));
                }
                if (attempt == attempts && config.isLogFailures()) {
                    plugin.getLogger().warning("YuDream report failed: " + describe(task)
                            + ", http=" + result.getStatusCode()
                            + ", attempts=" + attempts
                            + ", elapsedMs=" + elapsedMs
                            + ", response=" + trim(result.getBody()));
                }
            } catch (Exception e) {
                long elapsedMs = System.currentTimeMillis() - startedAt;
                if (config.isLogAttempts() && attempt < attempts) {
                    plugin.getLogger().warning("YuDream report attempt threw exception: " + describe(task)
                            + ", attempt=" + attempt + "/" + attempts
                            + ", elapsedMs=" + elapsedMs
                            + ", error=" + e.getMessage());
                }
                if (attempt == attempts && config.isLogFailures()) {
                    plugin.getLogger().log(Level.WARNING, "YuDream report failed: " + describe(task)
                            + ", attempts=" + attempts
                            + ", elapsedMs=" + elapsedMs, e);
                }
            }
            if (attempt < attempts) {
                Thread.sleep(config.getRetryDelayMs());
            }
        }
    }

    private String describe(ReportTask task) {
        if (task.snapshot) {
            return "type=SNAPSHOT, endpoint=/players/snapshot, players=" + task.players.size()
                    + (config.isLogPayload() ? ", observedAt=" + task.observedAt : "");
        }
        StringBuilder message = new StringBuilder();
        message.append("type=").append(task.type);
        message.append(", endpoint=/players/").append(task.type.getRemotePath());
        message.append(", player=").append(task.payload.getPlayerName());
        if (config.isLogPayload()) {
            message.append(", playerId=").append(task.payload.getPlayerId());
            message.append(", eventAt=").append(task.payload.getEventAt());
        }
        return message.toString();
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    private static final class ReportTask {
        private final boolean snapshot;
        private final PlayerEventType type;
        private final PlayerEventPayload payload;
        private final List<PlayerEventPayload> players;
        private final long observedAt;

        private ReportTask(boolean snapshot, PlayerEventType type, PlayerEventPayload payload,
                           Collection<PlayerEventPayload> players, long observedAt) {
            this.snapshot = snapshot;
            this.type = type;
            this.payload = payload;
            this.players = players == null ? java.util.Collections.<PlayerEventPayload>emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<PlayerEventPayload>(players));
            this.observedAt = observedAt;
        }

        private static ReportTask event(PlayerEventType type, PlayerEventPayload payload) {
            return new ReportTask(false, type, payload, null, payload.getEventAt());
        }

        private static ReportTask snapshot(Collection<PlayerEventPayload> players, long observedAt) {
            return new ReportTask(true, null, null, players, observedAt);
        }
    }
}
