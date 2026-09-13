package online.yudream.minecraft.bukkit.config;

import online.yudream.minecraft.bukkit.bridge.BridgeMode;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The settings that only matter when this server sits behind a proxy.
 *
 * <p>Grouped into one object instead of being spread across {@link YudreamConfig}'s positional
 * constructor, so adding a knob here cannot silently shift an unrelated argument.
 */
public final class DownstreamOptions {

    private final BridgeMode mode;
    private final boolean fallbackToApi;
    private final long ackTimeoutMs;
    private final long heartbeatSeconds;
    private final long activityMinIntervalMs;

    public DownstreamOptions(BridgeMode mode,
                             boolean fallbackToApi,
                             long ackTimeoutMs,
                             long heartbeatSeconds,
                             long activityMinIntervalMs) {
        this.mode = mode == null ? BridgeMode.STANDALONE : mode;
        this.fallbackToApi = fallbackToApi;
        this.ackTimeoutMs = positive(ackTimeoutMs, 90_000L);
        this.heartbeatSeconds = positive(heartbeatSeconds, 20L);
        this.activityMinIntervalMs = Math.max(activityMinIntervalMs, 0L);
    }

    public static DownstreamOptions defaults() {
        return new DownstreamOptions(BridgeMode.STANDALONE, false, 90_000L, 20L, 10_000L);
    }

    public static DownstreamOptions load(FileConfiguration config) {
        DownstreamOptions fallback = defaults();
        return new DownstreamOptions(
                BridgeMode.fromId(config.getString("mode", fallback.mode.getId())),
                config.getBoolean("downstream.fallback-to-api", fallback.fallbackToApi),
                positive(config.getLong("downstream.ack-timeout-seconds", fallback.ackTimeoutMs / 1000L), 90L) * 1000L,
                positive(config.getLong("downstream.heartbeat-seconds", fallback.heartbeatSeconds), 20L),
                Math.max(config.getLong("downstream.activity-min-interval-seconds",
                        fallback.activityMinIntervalMs / 1000L), 0L) * 1000L);
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    public BridgeMode getMode() {
        return mode;
    }

    public boolean isDownstream() {
        return mode.isDownstream();
    }

    /**
     * Whether this backend also reports to YuDream Admin directly while the proxy has not
     * acknowledged. Off by default: the proxy is meant to be the only uploader.
     */
    public boolean isFallbackToApi() {
        return fallbackToApi;
    }

    /** How long the proxy may stay silent before {@link #isFallbackToApi()} takes over. */
    public long getAckTimeoutMs() {
        return ackTimeoutMs;
    }

    public long getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    /** Shortest gap between two forwarded activity signals for the same player. */
    public long getActivityMinIntervalMs() {
        return activityMinIntervalMs;
    }
}
