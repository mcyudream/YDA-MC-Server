package online.yudream.minecraft.bridge.common.config;

import java.util.Locale;

/**
 * Which part of a deployment uploads player activity to YuDream Admin.
 *
 * <ul>
 *   <li>{@link #DOWNSTREAM} — this runtime sits behind a proxy and becomes a <b>sensor</b>: it watches
 *       local players and forwards what it sees to the Velocity bridge over the {@code yudream:bridge}
 *       plugin channel, uploading nothing itself. A backend server therefore never holds an API key.</li>
 *   <li>{@link #STANDALONE} — there is no proxy. This runtime talks to Admin directly and owns
 *       presence, AFK state and every HTTP call.</li>
 * </ul>
 *
 * <p>Which value is the default is deliberately <em>not</em> decided here: {@link #fromId} takes the
 * caller's fallback, because the two runtimes have different histories. The Fabric mod has always been
 * a sensor and must stay one unless an operator says otherwise, so it falls back to
 * {@link #DOWNSTREAM}; the Bukkit plugin has always uploaded directly and falls back to
 * {@link #STANDALONE}.
 *
 * <p>The Bukkit plugin keeps its own copy of this enum: it is built by Maven from a separate source
 * tree that does not include this module. The two id strings are a wire contract with the shipped
 * config files, so they must stay identical.
 */
public enum BridgeMode {

    DOWNSTREAM("downstream"),
    STANDALONE("standalone");

    private final String id;

    BridgeMode(String id) {
        this.id = id;
    }

    /** The value written to, and read from, the config file. */
    public String getId() {
        return id;
    }

    public boolean isDownstream() {
        return this == DOWNSTREAM;
    }

    public boolean isStandalone() {
        return this == STANDALONE;
    }

    /**
     * Parses a config value, returning {@code fallback} for null, blank or unrecognised input.
     *
     * <p>Falling back rather than throwing keeps a typo in one key from stopping a server: a
     * misspelled mode behaves like the runtime's historical default.
     */
    public static BridgeMode fromId(String value, BridgeMode fallback) {
        if (value != null) {
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            for (BridgeMode mode : values()) {
                if (mode.id.equals(trimmed)) {
                    return mode;
                }
            }
        }
        return fallback;
    }
}
