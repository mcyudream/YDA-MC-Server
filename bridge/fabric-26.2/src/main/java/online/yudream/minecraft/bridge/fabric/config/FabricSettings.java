package online.yudream.minecraft.bridge.fabric.config;

import online.yudream.minecraft.bridge.common.config.ConfigFile;

/**
 * Sensor-side configuration.
 *
 * <p>There is deliberately no YuDream Admin endpoint or API key here: the Velocity plugin is the only
 * uploader, so a backend server never holds credentials.
 */
public final class FabricSettings {

    private final boolean enabled;
    private final int heartbeatSeconds;
    private final boolean activityChat;
    private final boolean activityMove;
    private final boolean activityInteract;
    private final boolean activityCommand;
    private final double moveMinBlocks;
    private final long activityMinIntervalMs;
    private final boolean debug;

    private FabricSettings(Builder builder) {
        this.enabled = builder.enabled;
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
                .enabled(config.getBoolean("enabled", fallback.enabled))
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
        config.setIfAbsent("enabled", settings.enabled);
        config.setIfAbsent("heartbeat-seconds", settings.heartbeatSeconds);
        config.setIfAbsent("activity.chat", settings.activityChat);
        config.setIfAbsent("activity.move", settings.activityMove);
        config.setIfAbsent("activity.interact", settings.activityInteract);
        config.setIfAbsent("activity.command", settings.activityCommand);
        config.setIfAbsent("activity.move-min-blocks", Double.toString(settings.moveMinBlocks));
        config.setIfAbsent("activity.min-interval-seconds", settings.activityMinIntervalMs / 1000L);
        config.setIfAbsent("log.debug", settings.debug);
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

        private boolean enabled = true;
        private int heartbeatSeconds = 20;
        private boolean activityChat = true;
        private boolean activityMove = true;
        private boolean activityInteract = true;
        private boolean activityCommand = true;
        private double moveMinBlocks = 1.0D;
        private long activityMinIntervalMs = 10_000L;
        private boolean debug = false;

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
