package online.yudream.minecraft.bukkit.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * One scheduling facade for Bukkit/Spigot, Paper and Folia.
 *
 * <p>Folia refuses to load plugins that do not declare {@code folia-supported: true} and it does not
 * implement {@link org.bukkit.scheduler.BukkitScheduler}: everything that used to be
 * {@code getScheduler().runTask(...)} has to go through the global region scheduler, the async
 * scheduler or the owning entity's scheduler instead. At the same time the same jar still has to run
 * on Spigot 1.8.8+, whose API has none of the Folia classes.
 *
 * <p>This class therefore keeps the Bukkit scheduler for non-Folia servers and reaches the Folia
 * schedulers reflectively. That keeps the plugin compiled against the old Spigot API, keeps the Java 8
 * bytecode, and avoids a hard dependency on {@code folia-api}.
 *
 * <p>Threading rules used by the callers:
 *
 * <ul>
 *   <li>{@link #runGlobal(Runnable)} — global region thread. Safe place for server-wide reads and
 *       broadcasting on Folia.</li>
 *   <li>{@link #runAsync(Runnable)} — a background thread from the server's async pool. Never touch
 *       the world or an entity there.</li>
 *   <li>{@link #runFor(Player, Runnable)} — the thread that owns the player. On Folia this is the
 *       player's region thread; if the caller already runs on it the task executes immediately, so a
 *       quit handler does not lose its player to a retired entity scheduler.</li>
 * </ul>
 */
public final class SchedulerCompat {

    private static final boolean FOLIA = detectFolia();
    private static final Runnable NOOP = new Runnable() {
        @Override
        public void run() {
        }
    };

    private static Object globalScheduler;
    private static Object asyncScheduler;
    private static Method globalExecute;
    private static Method globalRunDelayed;
    private static Method globalRunAtFixedRate;
    private static Method asyncRunNow;
    private static Method asyncRunAtFixedRate;
    private static Method entityExecute;
    private static Method taskCancel;
    private static Method taskIsCancelled;
    private static Method serverOwnsEntity;
    private static boolean reflectionReady;
    private static boolean reflectionFailureLogged;

    private final JavaPlugin plugin;
    private boolean fallbackWarned;

    public SchedulerCompat(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Whether the running server uses Folia's regionised threading. */
    public static boolean isFolia() {
        return FOLIA;
    }

    // ------------------------------------------------------------------ global

    /** Runs on the global region thread (or the main thread on Bukkit). */
    public TaskHandle runGlobal(Runnable task) {
        if (!FOLIA) {
            return new BukkitHandle(plugin.getServer().getScheduler().runTask(plugin, task));
        }
        ensureFoliaReflection();
        Runnable guarded = guard(task);
        try {
            globalExecute.invoke(globalScheduler, plugin, guarded);
            return TaskHandle.NONE;
        } catch (Throwable t) {
            warnFallback("runGlobal", t);
            return TaskHandle.NONE;
        }
    }

    /** Runs on the global region thread after {@code delayTicks}. */
    public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
        if (!FOLIA) {
            return new BukkitIdHandle(plugin, plugin.getServer().getScheduler()
                    .scheduleSyncDelayedTask(plugin, task, delayTicks));
        }
        ensureFoliaReflection();
        try {
            Object scheduled = globalRunDelayed.invoke(globalScheduler, plugin, consumer(task),
                    Long.valueOf(Math.max(delayTicks, 0L)));
            return new FoliaHandle(scheduled);
        } catch (Throwable t) {
            warnFallback("runGlobalLater", t);
            return TaskHandle.NONE;
        }
    }

    /** Repeating global-region task, in ticks. */
    public TaskHandle runGlobalTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        final Runnable guarded = guard(task);
        if (!FOLIA) {
            return new BukkitIdHandle(plugin, plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                    plugin, guarded, Math.max(initialDelayTicks, 0L), Math.max(periodTicks, 1L)));
        }
        ensureFoliaReflection();
        try {
            Object scheduled = globalRunAtFixedRate.invoke(globalScheduler, plugin, consumer(guarded),
                    Long.valueOf(Math.max(initialDelayTicks, 0L)), Long.valueOf(Math.max(periodTicks, 1L)));
            return new FoliaHandle(scheduled);
        } catch (Throwable t) {
            warnFallback("runGlobalTimer", t);
            return TaskHandle.NONE;
        }
    }

    // ------------------------------------------------------------------ async

    /** Runs on the server's asynchronous pool on both implementations. */
    public TaskHandle runAsync(Runnable task) {
        if (!FOLIA) {
            return new BukkitHandle(plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task));
        }
        ensureFoliaReflection();
        try {
            Object scheduled = asyncRunNow.invoke(asyncScheduler, plugin, consumer(guard(task)));
            return new FoliaHandle(scheduled);
        } catch (Throwable t) {
            warnFallback("runAsync", t);
            return TaskHandle.NONE;
        }
    }

    /**
     * Repeating asynchronous task in milliseconds.
     *
     * <p>Bukkit only understands ticks, so the millisecond period is converted for it; Folia takes the
     * values with a {@link TimeUnit} directly.
     */
    public TaskHandle runAsyncTimer(Runnable task, long initialDelayMs, long periodMs) {
        long safePeriodMs = Math.max(periodMs, 50L);
        final Runnable guarded = guard(task);
        if (!FOLIA) {
            long initialTicks = Math.max(initialDelayMs, 0L) / 50L;
            long periodTicks = Math.max(safePeriodMs / 50L, 1L);
            return new BukkitHandle(plugin.getServer().getScheduler()
                    .runTaskTimerAsynchronously(plugin, guarded, initialTicks, periodTicks));
        }
        ensureFoliaReflection();
        try {
            Object scheduled = asyncRunAtFixedRate.invoke(asyncScheduler, plugin, consumer(guarded),
                    Long.valueOf(Math.max(initialDelayMs, 0L)), Long.valueOf(safePeriodMs), TimeUnit.MILLISECONDS);
            return new FoliaHandle(scheduled);
        } catch (Throwable t) {
            warnFallback("runAsyncTimer", t);
            return TaskHandle.NONE;
        }
    }

    // ------------------------------------------------------------------ player

    /**
     * Runs the task on the thread that owns {@code player}.
     *
     * <p>If the current thread already owns the player (the usual case inside a player event) the
     * task runs inline. Otherwise Folia's entity scheduler is used; on Bukkit the task goes to the
     * main thread.
     */
    public void runFor(Player player, Runnable task) {
        if (player == null) {
            return;
        }
        if (!FOLIA) {
            if (Bukkit.isPrimaryThread()) {
                runSafely(task);
            } else {
                runGlobal(task);
            }
            return;
        }
        ensureFoliaReflection();
        if (ownsPlayer(player)) {
            runSafely(task);
            return;
        }
        try {
            Object entityScheduler = player.getClass().getMethod("getScheduler").invoke(player);
            entityExecute.invoke(entityScheduler, plugin, task, NOOP, Long.valueOf(0L));
        } catch (Throwable t) {
            warnFallback("runFor", t);
        }
    }

    /** Broadcasts to the whole server from the global region thread. */
    public void broadcast(final String message) {
        runGlobal(new Runnable() {
            @Override
            public void run() {
                Bukkit.broadcastMessage(message);
            }
        });
    }

    // ------------------------------------------------------------------ internals

    private static boolean detectFolia() {
        // RegionizedServer only exists in the Folia server jar; paper-api also ships the scheduler
        // interfaces, so checking those would misidentify plain Paper.
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (Throwable ignored) {
            // Not Folia. Fall through to the name check below for forks.
        }
        try {
            String name = Bukkit.getName();
            String version = Bukkit.getVersion();
            return containsFolia(name) || containsFolia(version);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsFolia(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("folia");
    }

    private static synchronized void ensureFoliaReflection() {
        if (!reflectionReady) {
            initFoliaReflection();
        }
    }

    private static void initFoliaReflection() {
        if (reflectionReady) {
            return;
        }
        try {
            Object server = Bukkit.getServer();
            Class<?> serverClass = server.getClass();
            globalScheduler = serverClass.getMethod("getGlobalRegionScheduler").invoke(server);
            asyncScheduler = serverClass.getMethod("getAsyncScheduler").invoke(server);

            Class<?> globalClass = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            globalExecute = globalClass.getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class);
            globalRunDelayed = globalClass.getMethod("runDelayed", org.bukkit.plugin.Plugin.class,
                    Consumer.class, long.class);
            globalRunAtFixedRate = globalClass.getMethod("runAtFixedRate", org.bukkit.plugin.Plugin.class,
                    Consumer.class, long.class, long.class);

            Class<?> asyncClass = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            asyncRunNow = asyncClass.getMethod("runNow", org.bukkit.plugin.Plugin.class, Consumer.class);
            asyncRunAtFixedRate = asyncClass.getMethod("runAtFixedRate", org.bukkit.plugin.Plugin.class,
                    Consumer.class, long.class, long.class, TimeUnit.class);

            Class<?> entityClass = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            entityExecute = entityClass.getMethod("execute", org.bukkit.plugin.Plugin.class,
                    Runnable.class, Runnable.class, long.class);

            Class<?> taskClass = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            taskCancel = taskClass.getMethod("cancel");
            taskIsCancelled = taskClass.getMethod("isCancelled");

            serverOwnsEntity = serverClass.getMethod("isOwnedByCurrentRegion",
                    Class.forName("org.bukkit.entity.Entity"));
            reflectionReady = true;
            reflectionFailureLogged = false;
        } catch (Throwable t) {
            reflectionReady = false;
            if (!reflectionFailureLogged) {
                reflectionFailureLogged = true;
                Bukkit.getLogger().log(Level.SEVERE,
                        "YuDream bridge could not resolve the Folia scheduler API; scheduled work will not run", t);
            }
        }
    }

    private static Consumer<Object> consumer(final Runnable task) {
        return new Consumer<Object>() {
            @Override
            public void accept(Object scheduledTask) {
                task.run();
            }
        };
    }

    private static boolean ownsPlayer(Player player) {
        if (serverOwnsEntity == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(serverOwnsEntity.invoke(Bukkit.getServer(), player));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Runnable guard(final Runnable task) {
        return new Runnable() {
            @Override
            public void run() {
                runSafely(task);
            }
        };
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            // On Folia a throwing repeating task is cancelled permanently; keep the timer alive.
            plugin.getLogger().log(Level.WARNING, "Scheduled YuDream bridge task failed", t);
        }
    }

    private void warnFallback(String operation, Throwable error) {
        if (fallbackWarned) {
            plugin.getLogger().log(Level.FINE, "YuDream bridge " + operation + " failed", error);
            return;
        }
        fallbackWarned = true;
        plugin.getLogger().log(Level.WARNING,
                "YuDream bridge could not schedule " + operation + " on Folia; the task was skipped. "
                        + "Check that the server is running a supported Folia build.", error);
    }

    /** A cancellable scheduled task. */
    public interface TaskHandle {

        /** No-op handle for fire-and-forget scheduler calls. */
        TaskHandle NONE = new TaskHandle() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        void cancel();

        boolean isCancelled();
    }

    private static final class FoliaHandle implements TaskHandle {

        private final Object scheduledTask;

        private FoliaHandle(Object scheduledTask) {
            this.scheduledTask = scheduledTask;
        }

        @Override
        public void cancel() {
            if (scheduledTask == null || taskCancel == null) {
                return;
            }
            try {
                taskCancel.invoke(scheduledTask);
            } catch (Throwable ignored) {
                // Already cancelled or the task never started.
            }
        }

        @Override
        public boolean isCancelled() {
            if (scheduledTask == null || taskIsCancelled == null) {
                return false;
            }
            try {
                return Boolean.TRUE.equals(taskIsCancelled.invoke(scheduledTask));
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static final class BukkitHandle implements TaskHandle {

        private final BukkitTask task;

        private BukkitHandle(BukkitTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            if (task != null) {
                task.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return task == null || task.isCancelled();
        }
    }

    private static final class BukkitIdHandle implements TaskHandle {

        private final JavaPlugin plugin;
        private final int taskId;

        private BukkitIdHandle(JavaPlugin plugin, int taskId) {
            this.plugin = plugin;
            this.taskId = taskId;
        }

        @Override
        public void cancel() {
            if (taskId >= 0) {
                plugin.getServer().getScheduler().cancelTask(taskId);
            }
        }

        @Override
        public boolean isCancelled() {
            return taskId < 0
                    || (!plugin.getServer().getScheduler().isQueued(taskId)
                    && !plugin.getServer().getScheduler().isCurrentlyRunning(taskId));
        }
    }
}
