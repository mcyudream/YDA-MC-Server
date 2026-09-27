package online.yudream.minecraft.mod.common;

import online.yudream.minecraft.bridge.core.http.HttpResult;
import online.yudream.minecraft.bridge.core.http.YudreamApiClient;
import online.yudream.minecraft.bridge.core.log.LogSink;
import online.yudream.minecraft.bridge.core.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.core.model.PlayerEventType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class ReportQueue {

    private final LogSink logger;
    private final YudreamApiClient client;
    private final BridgeConfig config;
    private final BlockingQueue<ReportTask> queue;
    private final ExecutorService executor;
    private volatile boolean running = true;

    public ReportQueue(LogSink logger, YudreamApiClient client, BridgeConfig config) {
        this.logger = logger;
        this.client = client;
        this.config = config;
        this.queue = new ArrayBlockingQueue<>(config.getQueueCapacity());
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "YuDream-Minecraft-Mod-Reporter");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.executor.execute(this::workerLoop);
    }

    public void submit(PlayerEventType type, PlayerEventPayload payload) {
        if (!config.isEnabled() || !config.isConfigured()) {
            return;
        }
        ReportTask task = ReportTask.event(type, payload);
        enqueue(task);
    }

    public void submitSnapshot(Collection<PlayerEventPayload> players, long observedAt) {
        if (!config.isEnabled() || !config.isConfigured()) {
            return;
        }
        enqueue(ReportTask.snapshot(players, observedAt));
    }

    private void enqueue(ReportTask task) {
        if (queue.offer(task)) {
            if (config.isLogQueued()) {
                logger.info("Queued YuDream report: " + describe(task) + ", queueSize=" + queue.size());
            }
            return;
        }
        if (config.isLogFailures()) {
            logger.warn("YuDream report queue is full; dropped " + describe(task));
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
                logger.warn("Unexpected YuDream report worker error", t);
            }
        }
    }

    private void sendWithRetry(ReportTask task) throws InterruptedException {
        int attempts = config.getRetryAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long startedAt = System.currentTimeMillis();
            try {
                if (config.isLogAttempts()) {
                    logger.info("Sending YuDream report: " + describe(task) + ", attempt=" + attempt + "/" + attempts);
                }
                HttpResult result = task.snapshot
                        ? client.snapshot(task.players, task.observedAt, null)
                        : client.report(task.type, task.payload);
                long elapsedMs = System.currentTimeMillis() - startedAt;
                if (result.isSuccess()) {
                    if (config.isLogSuccess()) {
                        logger.info("YuDream report success: " + describe(task)
                                + ", http=" + result.statusCode()
                                + ", attempt=" + attempt + "/" + attempts
                                + ", elapsedMs=" + elapsedMs);
                    }
                    return;
                }
                if (config.isLogAttempts() && attempt < attempts) {
                    logger.warn("YuDream report attempt failed: " + describe(task)
                            + ", http=" + result.statusCode()
                            + ", attempt=" + attempt + "/" + attempts
                            + ", elapsedMs=" + elapsedMs
                            + ", response=" + trim(result.body()));
                }
                if (attempt == attempts && config.isLogFailures()) {
                    logger.warn("YuDream report failed: " + describe(task)
                            + ", http=" + result.statusCode()
                            + ", attempts=" + attempts
                            + ", elapsedMs=" + elapsedMs
                            + ", response=" + trim(result.body()));
                }
            } catch (Exception e) {
                long elapsedMs = System.currentTimeMillis() - startedAt;
                if (config.isLogAttempts() && attempt < attempts) {
                    logger.warn("YuDream report attempt threw exception: " + describe(task)
                            + ", attempt=" + attempt + "/" + attempts
                            + ", elapsedMs=" + elapsedMs
                            + ", error=" + e.getMessage());
                }
                if (attempt == attempts && config.isLogFailures()) {
                    logger.warn("YuDream report failed: " + describe(task)
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
        message.append(", player=").append(task.payload.playerName());
        if (config.isLogPayload()) {
            message.append(", playerId=").append(task.payload.playerId());
            message.append(", eventAt=").append(task.payload.eventAt());
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
            this.players = players == null ? Collections.<PlayerEventPayload>emptyList()
                    : Collections.unmodifiableList(new ArrayList<PlayerEventPayload>(players));
            this.observedAt = observedAt;
        }

        private static ReportTask event(PlayerEventType type, PlayerEventPayload payload) {
            return new ReportTask(false, type, payload, null, payload.eventAt());
        }

        private static ReportTask snapshot(Collection<PlayerEventPayload> players, long observedAt) {
            return new ReportTask(true, null, null, players, observedAt);
        }
    }
}
