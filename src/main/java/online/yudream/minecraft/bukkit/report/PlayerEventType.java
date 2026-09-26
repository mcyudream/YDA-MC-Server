package online.yudream.minecraft.bukkit.report;

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
    ADVANCEMENT("events/advancement");

    private final String remotePath;

    PlayerEventType(String remotePath) {
        this.remotePath = remotePath;
    }

    public String getRemotePath() {
        return remotePath;
    }
}
