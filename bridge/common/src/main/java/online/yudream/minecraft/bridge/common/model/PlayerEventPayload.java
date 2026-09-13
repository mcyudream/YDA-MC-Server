package online.yudream.minecraft.bridge.common.model;

/**
 * One player event, or one entry of an online-player snapshot.
 *
 * <p>{@code playerId}/{@code playerName}/{@code eventAt} are exactly the fields YuDream Admin
 * already accepts. {@code serverName} is local metadata: it is only ever logged, and it is only
 * added to a snapshot body when {@code snapshot.include-server-name} is switched on.
 */
public record PlayerEventPayload(String playerId, String playerName, long eventAt, String serverName) {

    public PlayerEventPayload(String playerId, String playerName, long eventAt) {
        this(playerId, playerName, eventAt, null);
    }

    public static PlayerEventPayload of(PlayerIdentity identity, long eventAt, String serverName) {
        return new PlayerEventPayload(identity.uuidString(), identity.name(), eventAt, serverName);
    }

    public PlayerEventPayload withServerName(String serverName) {
        return new PlayerEventPayload(playerId, playerName, eventAt, serverName);
    }
}
