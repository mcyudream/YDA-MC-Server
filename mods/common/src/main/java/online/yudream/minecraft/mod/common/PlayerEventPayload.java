package online.yudream.minecraft.mod.common;

public final class PlayerEventPayload {

    private final String playerId;
    private final String playerName;
    private final long eventAt;
    /** 群服互联事件的文本（聊天原文、死亡消息、成就名）；进退服/挂机事件为 null。 */
    private final String content;

    public PlayerEventPayload(String playerId, String playerName, long eventAt) {
        this(playerId, playerName, eventAt, null);
    }

    public PlayerEventPayload(String playerId, String playerName, long eventAt, String content) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.eventAt = eventAt;
        this.content = content;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public long getEventAt() {
        return eventAt;
    }

    public String getContent() {
        return content;
    }
}
