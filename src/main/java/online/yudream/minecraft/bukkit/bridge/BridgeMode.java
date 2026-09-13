package online.yudream.minecraft.bukkit.bridge;

import java.util.Locale;

/**
 * How this server reports player activity.
 *
 * <p>{@link #STANDALONE} is the historical behaviour: the plugin talks to YuDream Admin itself.
 * {@link #DOWNSTREAM} turns the plugin into a sensor for a proxy network — it forwards player
 * activity to the Velocity bridge over a plugin message channel and uploads nothing itself, exactly
 * like the Fabric sensor does for Fabric backends.
 */
public enum BridgeMode {

    STANDALONE("standalone"),
    DOWNSTREAM("downstream");

    private final String id;

    BridgeMode(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public boolean isDownstream() {
        return this == DOWNSTREAM;
    }

    /** Parses a config value, falling back to {@link #STANDALONE} for anything unrecognised. */
    public static BridgeMode fromId(String value) {
        if (value != null) {
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            for (BridgeMode mode : values()) {
                if (mode.id.equals(trimmed)) {
                    return mode;
                }
            }
        }
        return STANDALONE;
    }
}
