package online.yudream.minecraft.bridge.common.queue;

import online.yudream.minecraft.bridge.common.config.BridgeSettings;
import online.yudream.minecraft.bridge.common.http.HttpResult;
import online.yudream.minecraft.bridge.common.http.YudreamApiClient;
import online.yudream.minecraft.bridge.common.log.LogSink;
import online.yudream.minecraft.bridge.common.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.common.model.PlayerEventType;
import online.yudream.minecraft.bridge.common.model.SubServerRoster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The single asynchronous report pipeline: bounded queue, retries, and an optional journal so an
 * undelivered report survives a proxy restart.
 *
 * <p>Only one instance exists at a time. A config reload shuts the old queue down (draining it) and
 * builds a new one from the same journal, which is also what makes {@code /yudreammc reload} safe
 * while reports are in flight.
 */
public final class ReportQueue {

    /**
     * An extra check applied before anything is queued, on top of the enabled/configured pair.
     *
     * <p>Explicitly declared so a caller can keep a background producer (the AFK tracker, the
     * snapshot task) from bypassing a mode decision it cannot see.
     */
    public interface ReportGate {
        boolean canReport();
    }

    private static final long JOURNAL_FLUSH_INTERVAL_MS = 500L;
    private static final long POLL_INTERVAL_MS = 500L;

    private final LogSink log;
    private final BridgeSettings settings;
    private final YudreamApiClient client;
    private final ReportJournal journal;
    private final ReportGate gate;
    private final BlockingQueue<ReportTask> queue;
    private final Map<String, ReportTask> outstanding = new LinkedHashMap<String, ReportTask>();
    private final Object outstandingLock = new Object();
    private final ExecutorService executor;

    private volatile boolean running = true;
    private volatile boolean dirty;
    private volatile long lastJournalSaveAt;

    public ReportQueue(LogSink log, BridgeSettings settings, YudreamApiClient client, ReportJournal journal) {
        this(log, settings, client, journal, null);
    }

    public ReportQueue(LogSink log, BridgeSettings settings, YudreamApiClient client, ReportJournal journal, ReportGate gate) {
        this.log = log;
        this.settings = settings;
        this.client = client;
        this.journal = journal;
        this.gate = gate;

        List<ReportTask> recovered = journal == null ? new ArrayList<ReportTask>() : journal.load();
        int capacity = Math.max(settings.getQueueCapacity(), recovered.size() + 1);
        this.queue = new ArrayBlockingQueue<ReportTask>(capacity);
        for (ReportTask task : recovered) {
            queue.offer(task);
            outstanding.put(task.id(), task);
        }

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
        if (!canReport()) {
            return;
        }
        enqueue(ReportTask.event(type, payload));
    }

    public void submitSnapshot(Collection<PlayerEventPayload> players, long observedAt, String serverName) {
        if (!canReport()) {
            return;
        }
        enqueue(ReportTask.snapshot(players, observedAt, serverName));
    }

    /**
     * Queues a snapshot carrying one roster per sub-server.
     *
     * <p>The gate cannot judge a single sub-server here, so callers must already have filtered the
     * rosters: only sub-servers whose sensor gate allows reporting belong in the list.
     */
    public void submitGroupedSnapshot(Collection<SubServerRoster> servers, long observedAt) {
        if (servers == null || servers.isEmpty() || !canReport()) {
            return;
        }
        enqueue(ReportTask.groupedSnapshot(servers, observedAt));
    }

    /** Number of reports still waiting to be sent. */
    public int size() {
        return queue.size();
    }

    /** Number of reports that are queued or currently in flight, which is what the journal holds. */
    public int outstandingCount() {
        synchronized (outstandingLock) {
            return outstanding.size();
        }
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
        flushJournal();
    }

    private void enqueue(ReportTask task) {
        if (queue.offer(task)) {
            synchronized (outstandingLock) {
                outstanding.put(task.id(), task);
            }
            dirty = true;
            if (settings.isLogQueued()) {
                log.info("Queued YuDream report: " + describe(task) + ", queueSize=" + queue.size());
            }
            return;
        }
        if (settings.isLogFailures()) {
            log.warn("YuDream report queue is full; dropped " + describe(task));
        }
    }

    private void workerLoop() {
        while (running || !queue.isEmpty()) {
            try {
                ReportTask task = queue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (task != null) {
                    sendWithRetry(task);
                    complete(task);
                }
                flushJournalIfDue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                log.warn("Unexpected YuDream report worker error", t);
            }
        }
        flushJournal();
    }

    private void complete(ReportTask task) {
        synchronized (outstandingLock) {
            outstanding.remove(task.id());
        }
        dirty = true;
    }

    private void flushJournalIfDue() {
        if (journal == null || !journal.isEnabled() || !dirty) {
            return;
        }
        if (queue.isEmpty() || System.currentTimeMillis() - lastJournalSaveAt >= JOURNAL_FLUSH_INTERVAL_MS) {
            flushJournal();
        }
    }

    /**
     * Writes the pending set to the journal. Synchronized because the worker thread flushes while
     * draining and {@link #shutdown(long)} flushes again on the caller thread; two concurrent
     * rewrites of the same temp file would interleave.
     */
    private synchronized void flushJournal() {
        if (journal == null || !journal.isEnabled()) {
            return;
        }
        List<ReportTask> snapshot;
        synchronized (outstandingLock) {
            snapshot = new ArrayList<ReportTask>(outstanding.values());
        }
        journal.save(snapshot);
        dirty = false;
        lastJournalSaveAt = System.currentTimeMillis();
    }

    private void sendWithRetry(ReportTask task) throws InterruptedException {
        int attempts = settings.getRetryAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long startedAt = System.currentTimeMillis();
            try {
                if (settings.isLogAttempts()) {
                    log.info("Sending YuDream report: " + describe(task) + ", attempt=" + attempt + "/" + attempts);
                }
                HttpResult result;
                if (!task.snapshot()) {
                    result = client.report(task.type(), task.payload());
                } else if (task.grouped()) {
                    result = client.groupedSnapshot(task.servers(), task.observedAt());
                } else {
                    result = client.snapshot(task.players(), task.observedAt(), task.serverName());
                }
                long elapsedMs = System.currentTimeMillis() - startedAt;
                if (result.isSuccess()) {
                    if (settings.isLogSuccess()) {
                        log.info("YuDream report success: " + describe(task)
                                + ", http=" + result.statusCode()
                                + ", attempt=" + attempt + "/" + attempts
                                + ", elapsedMs=" + elapsedMs);
                    }
                    return;
                }
                if (settings.isLogAttempts() && attempt < attempts) {
                    log.warn("YuDream report attempt failed: " + describe(task)
                            + ", http=" + result.statusCode()
                            + ", attempt=" + attempt + "/" + attempts
                            + ", elapsedMs=" + elapsedMs
                            + ", response=" + result.trimmedBody());
                }
                if (attempt == attempts && settings.isLogFailures()) {
                    log.warn("YuDream report failed: " + describe(task)
                            + ", http=" + result.statusCode()
                            + ", attempts=" + attempts
                            + ", elapsedMs=" + elapsedMs
                            + ", response=" + result.trimmedBody());
                }
            } catch (Exception e) {
                long elapsedMs = System.currentTimeMillis() - startedAt;
                if (settings.isLogAttempts() && attempt < attempts) {
                    log.warn("YuDream report attempt threw exception: " + describe(task)
                            + ", attempt=" + attempt + "/" + attempts
                            + ", elapsedMs=" + elapsedMs
                            + ", error=" + e.getMessage());
                }
                if (attempt == attempts && settings.isLogFailures()) {
                    log.warn("YuDream report failed: " + describe(task)
                            + ", attempts=" + attempts
                            + ", elapsedMs=" + elapsedMs, e);
                }
            }
            if (attempt < attempts) {
                Thread.sleep(settings.getRetryDelayMs());
            }
        }
    }

    private String describe(ReportTask task) {
        return task.describe(settings.isLogPayload());
    }

    private boolean canReport() {
        if (!settings.isEnabled() || !settings.isConfigured()) {
            return false;
        }
        return gate == null || gate.canReport();
    }
}
