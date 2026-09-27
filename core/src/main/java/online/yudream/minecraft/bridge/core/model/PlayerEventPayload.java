package online.yudream.minecraft.bridge.core.model;

/**
 * One player event, or one entry of an online-player snapshot.
 *
 * <p>{@code playerId}/{@code playerName}/{@code eventAt} are exactly the fields YuDream Admin
 * already accepts. {@code serverName} is local metadata: it is only ever logged, and it is only
 * added to a snapshot body when {@code snapshot.include-server-name} is switched on.
 * {@code content} is the game-event text (chat line, death message or advancement title) the
 * group-server bridge forwards; it is {@code null} for presence and AFK events.
 */
public final class PlayerEventPayload {

    private final String playerId;
    private final String playerName;
    private final long eventAt;
    private final String serverName;
    private final String content;

    public PlayerEventPayload(String playerId, String playerName, long eventAt, String serverName, String content) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.eventAt = eventAt;
        this.serverName = serverName;
        this.content = content;
    }

    public PlayerEventPayload(String playerId, String playerName, long eventAt) {
        this(playerId, playerName, eventAt, null, null);
    }

    public PlayerEventPayload(String playerId, String playerName, long eventAt, String serverName) {
        this(playerId, playerName, eventAt, serverName, null);
    }

    public String playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public long eventAt() {
        return eventAt;
    }

    public String serverName() {
        return serverName;
    }

    public String content() {
        return content;
    }

    public static PlayerEventPayload of(PlayerIdentity identity, long eventAt, String serverName) {
        return new PlayerEventPayload(identity.uuidString(), identity.name(), eventAt, serverName, null);
    }

    public static PlayerEventPayload of(PlayerIdentity identity, long eventAt, String serverName, String content) {
        return new PlayerEventPayload(identity.uuidString(), identity.name(), eventAt, serverName, content);
    }

    public PlayerEventPayload withServerName(String serverName) {
        return new PlayerEventPayload(playerId, playerName, eventAt, serverName, content);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlayerEventPayload)) {
            return false;
        }
        PlayerEventPayload other = (PlayerEventPayload) o;
        return eventAt == other.eventAt
                && (playerId == null ? other.playerId == null : playerId.equals(other.playerId))
                && (playerName == null ? other.playerName == null : playerName.equals(other.playerName))
                && (serverName == null ? other.serverName == null : serverName.equals(other.serverName))
                && (content == null ? other.content == null : content.equals(other.content));
    }

    @Override
    public int hashCode() {
        int result = playerId == null ? 0 : playerId.hashCode();
        result = 31 * result + (playerName == null ? 0 : playerName.hashCode());
        result = 31 * result + (int) (eventAt ^ (eventAt >>> 32));
        result = 31 * result + (serverName == null ? 0 : serverName.hashCode());
        result = 31 * result + (content == null ? 0 : content.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "PlayerEventPayload[playerId=" + playerId + ", playerName=" + playerName
                + ", eventAt=" + eventAt + ", serverName=" + serverName + ", content=" + content + "]";
    }
}
