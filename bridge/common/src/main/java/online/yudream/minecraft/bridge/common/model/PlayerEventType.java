package online.yudream.minecraft.bridge.common.model;

/** Every player event the bridge can report, with the YuDream Admin path it maps to. */
public enum PlayerEventType {

    JOIN("join"),
    QUIT("quit"),
    AFK_START("afk/start"),
    AFK_END("afk/end"),

    /**
     * Cross-server switch. Reserved and disabled by default: YuDream Admin has to accept the extra
     * {@code fromServer} / {@code toServer} fields before this can be switched on.
     */
    SERVER_SWITCH("server/switch");

    private final String remotePath;

    PlayerEventType(String remotePath) {
        this.remotePath = remotePath;
    }

    public String getRemotePath() {
        return remotePath;
    }

    /** Parses a wire name such as {@code "afk/start"}. Returns {@code null} for unknown names. */
    public static PlayerEventType fromRemotePath(String remotePath) {
        for (PlayerEventType type : values()) {
            if (type.remotePath.equals(remotePath)) {
                return type;
            }
        }
        return null;
    }
}
