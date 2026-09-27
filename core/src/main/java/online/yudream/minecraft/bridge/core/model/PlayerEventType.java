package online.yudream.minecraft.bridge.core.model;

/** Every player event the bridge can report, with the YuDream Admin path it maps to. */
public enum PlayerEventType {

    JOIN("players/join"),
    QUIT("players/quit"),
    AFK_START("players/afk/start"),
    AFK_END("players/afk/end"),

    /** 群服互联：玩家聊天，content 为消息原文。 */
    CHAT("events/chat"),
    /** 群服互联：玩家死亡，content 为死亡消息。 */
    DEATH("events/death"),
    /** 群服互联：玩家达成成就，content 为成就名。 */
    ADVANCEMENT("events/advancement"),

    /**
     * Cross-server switch. Reserved and disabled by default: YuDream Admin has to accept the extra
     * {@code fromServer} / {@code toServer} fields before this can be switched on.
     */
    SERVER_SWITCH("players/server/switch");

    private final String remotePath;

    PlayerEventType(String remotePath) {
        this.remotePath = remotePath;
    }

    public String getRemotePath() {
        return remotePath;
    }

    /** Parses a wire name such as {@code "players/afk/start"}. Returns {@code null} for unknown names. */
    public static PlayerEventType fromRemotePath(String remotePath) {
        for (PlayerEventType type : values()) {
            if (type.remotePath.equals(remotePath)) {
                return type;
            }
        }
        return null;
    }
}
