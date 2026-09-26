package online.yudream.minecraft.bridge.common.model;

/**
 * One player event, or one entry of an online-player snapshot.
 *
 * <p>{@code playerId}/{@code playerName}/{@code eventAt} are exactly the fields YuDream Admin
 * already accepts. {@code serverName} is local metadata: it is only ever logged, and it is only
 * added to a snapshot body when {@code snapshot.include-server-name} is switched on.
 * {@code content} is the game-event text (chat line, death message or advancement title) the
 * group-server bridge forwards; it is {@code null} for presence and AFK events.
 */
public record PlayerEventPayload(String playerId, String playerName, long eventAt, String serverName, String content) {

    public PlayerEventPayload(String playerId, String playerName, long eventAt) {
        this(playerId, playerName, eventAt, null, null);
    }

    public PlayerEventPayload(String playerId, String playerName, long eventAt, String serverName) {
        this(playerId, playerName, eventAt, serverName, null);
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
}
