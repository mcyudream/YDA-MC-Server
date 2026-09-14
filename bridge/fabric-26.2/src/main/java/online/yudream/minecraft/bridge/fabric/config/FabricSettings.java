package online.yudream.minecraft.bridge.fabric.config;

import online.yudream.minecraft.bridge.common.config.BridgeMode;
import online.yudream.minecraft.bridge.common.config.BridgeSettings;
import online.yudream.minecraft.bridge.common.config.ConfigFile;

/**
 * Bridge configuration for a Fabric server.
 *
 * <p>{@code mode} decides who uploads. In the default {@code downstream} mode this server is a sensor
 * behind a proxy and holds no credentials at all — the Velocity plugin is the only uploader. In
 * {@code standalone} mode there is no proxy, so the mod owns {@link #bridge()} (Admin endpoint, API
 * key, HTTP, AFK and shutdown behaviour) and reports its players itself.
 */
public final class FabricSettings {

    private final BridgeMode mode;
    private final boolean enabled;
    private final BridgeSettings bridge;
    private final int snapshotIntervalSeconds;
    private final int heartbeatSeconds;
    private final boolean activityChat;
    private final boolean activityMove;
    private final boolean activityInteract;
    private final boolean activityCommand;
    private final double moveMinBlocks;
    private final long activityMinIntervalMs;
    private final boolean debug;

    private FabricSettings(Builder builder) {
        this.mode = builder.mode;
        this.enabled = builder.enabled;
        this.bridge = builder.bridge;
        this.snapshotIntervalSeconds = Math.max(builder.snapshotIntervalSeconds, 5);
        this.heartbeatSeconds = Math.max(builder.heartbeatSeconds, 5);
        this.activityChat = builder.activityChat;
        this.activityMove = builder.activityMove;
        this.activityInteract = builder.activityInteract;
        this.activityCommand = builder.activityCommand;
        this.moveMinBlocks = builder.moveMinBlocks <= 0.0D ? 1.0D : builder.moveMinBlocks;
        this.activityMinIntervalMs = Math.max(builder.activityMinIntervalMs, 0L);
        this.debug = builder.debug;
    }

    public static FabricSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static FabricSettings from(ConfigFile config) {
        FabricSettings fallback = defaults();
        double moveMinBlocks = fallback.moveMinBlocks;
        String rawMove = config.get("activity.move-min-blocks", Double.toString(fallback.moveMinBlocks));
        try {
            moveMinBlocks = Double.parseDouble(rawMove.trim());
        } catch (NumberFormatException ignored) {
            // Keep the default when the value is not a number.
        }
        return builder()
                .mode(BridgeMode.fromId(config.get("mode", fallback.mode.getId()), fallback.mode))
                .enabled(config.getBoolean("enabled", fallback.enabled))
                .bridge(BridgeSettings.from(config))
                .snapshotIntervalSeconds(config.getInt("snapshot.interval-seconds", fallback.snapshotIntervalSeconds))
                .heartbeatSeconds(config.getInt("heartbeat-seconds", fallback.heartbeatSeconds))
                .activityChat(config.getBoolean("activity.chat", fallback.activityChat))
                .activityMove(config.getBoolean("activity.move", fallback.activityMove))
                .activityInteract(config.getBoolean("activity.interact", fallback.activityInteract))
                .activityCommand(config.getBoolean("activity.command", fallback.activityCommand))
                .moveMinBlocks(moveMinBlocks)
                .activityMinIntervalMs(config.getLong("activity.min-interval-seconds", fallback.activityMinIntervalMs / 1000L) * 1000L)
                .debug(config.getBoolean("log.debug", fallback.debug))
                .build();
    }

    public static void applyDefaults(ConfigFile config, FabricSettings settings) {
        config.setIfAbsent("mode", settings.mode.getId());
        config.setIfAbsent("enabled", settings.enabled);
        BridgeSettings.applyDefaults(config, settings.bridge);
        config.setIfAbsent("snapshot.interval-seconds", settings.snapshotIntervalSeconds);
        config.setIfAbsent("heartbeat-seconds", settings.heartbeatSeconds);
        config.setIfAbsent("activity.chat", settings.activityChat);
        config.setIfAbsent("activity.move", settings.activityMove);
        config.setIfAbsent("activity.interact", settings.activityInteract);
        config.setIfAbsent("activity.command", settings.activityCommand);
        config.setIfAbsent("activity.move-min-blocks", Double.toString(settings.moveMinBlocks));
        config.setIfAbsent("activity.min-interval-seconds", settings.activityMinIntervalMs / 1000L);
        config.setIfAbsent("log.debug", settings.debug);
    }

    public BridgeMode mode() {
        return mode;
    }

    /** True when this server is a sensor behind a proxy and uploads nothing itself. */
    public boolean isDownstream() {
        return mode.isDownstream();
    }

    /** True when this server talks to YuDream Admin itself because there is no proxy. */
    public boolean isStandalone() {
        return mode.isStandalone();
    }

    /**
     * The Admin endpoint, HTTP, AFK and shutdown settings.
     *
     * <p>Only meaningful in standalone mode, but always parsed: the file is shared with downstream
     * deployments so an operator can flip {@code mode} without re-editing the keys.
     */
    public BridgeSettings bridge() {
        return bridge;
    }

    /** How often standalone mode re-reports the full roster, so Admin can reconcile missed events. */
    public int snapshotIntervalSeconds() {
        return snapshotIntervalSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int heartbeatSeconds() {
        return heartbeatSeconds;
    }

    public boolean isActivityChat() {
        return activityChat;
    }

    public boolean isActivityMove() {
        return activityMove;
    }

    public boolean isActivityInteract() {
        return activityInteract;
    }

    public boolean isActivityCommand() {
        return activityCommand;
    }

    public double moveMinBlocks() {
        return moveMinBlocks;
    }

    /** Shortest gap between two forwarded activity signals for the same player. */
    public long activityMinIntervalMs() {
        return activityMinIntervalMs;
    }

    public boolean isDebug() {
        return debug;
    }

    /** Mutable builder; only {@link FabricSettings} produces an immutable instance. */
    public static final class Builder {

        private BridgeMode mode = BridgeMode.DOWNSTREAM;
        private boolean enabled = true;
        private BridgeSettings bridge = BridgeSettings.defaults();
        private int snapshotIntervalSeconds = 60;
        private int heartbeatSeconds = 20;
        private boolean activityChat = true;
        private boolean activityMove = true;
        private boolean activityInteract = true;
        private boolean activityCommand = true;
        private double moveMinBlocks = 1.0D;
        private long activityMinIntervalMs = 10_000L;
        private boolean debug = false;

        public Builder mode(BridgeMode value) {
            this.mode = value == null ? BridgeMode.DOWNSTREAM : value;
            return this;
        }

        public Builder bridge(BridgeSettings value) {
            this.bridge = value == null ? BridgeSettings.defaults() : value;
            return this;
        }

        public Builder snapshotIntervalSeconds(int value) {
            this.snapshotIntervalSeconds = value;
            return this;
        }

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder heartbeatSeconds(int value) {
            this.heartbeatSeconds = value;
            return this;
        }

        public Builder activityChat(boolean value) {
            this.activityChat = value;
            return this;
        }

        public Builder activityMove(boolean value) {
            this.activityMove = value;
            return this;
        }

        public Builder activityInteract(boolean value) {
            this.activityInteract = value;
            return this;
        }

        public Builder activityCommand(boolean value) {
            this.activityCommand = value;
            return this;
        }

        public Builder moveMinBlocks(double value) {
            this.moveMinBlocks = value;
            return this;
        }

        public Builder activityMinIntervalMs(long value) {
            this.activityMinIntervalMs = value;
            return this;
        }

        public Builder debug(boolean value) {
            this.debug = value;
            return this;
        }

        public FabricSettings build() {
            return new FabricSettings(this);
        }
    }
}
