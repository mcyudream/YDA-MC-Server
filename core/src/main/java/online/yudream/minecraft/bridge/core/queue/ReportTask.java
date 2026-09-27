package online.yudream.minecraft.bridge.core.queue;

import online.yudream.minecraft.bridge.core.json.JsonValue;
import online.yudream.minecraft.bridge.core.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.core.model.PlayerEventType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One queued HTTP report: either a single player event or a full online-player snapshot.
 *
 * <p>{@code id} exists only for the local journal. It makes a task addressable while it is pending
 * and lets the bridge drop it after a successful send, without changing the YuDream Admin request
 * body (which still carries exactly the fields the existing artifacts send).
 */
public final class ReportTask {

    private final String id;
    private final boolean snapshot;
    private final PlayerEventType type;
    private final PlayerEventPayload payload;
    private final List<PlayerEventPayload> players;
    private final long observedAt;
    private final String serverName;

    public ReportTask(String id,
                      boolean snapshot,
                      PlayerEventType type,
                      PlayerEventPayload payload,
                      List<PlayerEventPayload> players,
                      long observedAt,
                      String serverName) {
        this.id = id;
        this.snapshot = snapshot;
        this.type = type;
        this.payload = payload;
        this.players = players == null
                ? Collections.<PlayerEventPayload>emptyList()
                : Collections.unmodifiableList(new ArrayList<PlayerEventPayload>(players));
        this.observedAt = observedAt;
        this.serverName = serverName;
    }

    public String id() {
        return id;
    }

    public boolean snapshot() {
        return snapshot;
    }

    public PlayerEventType type() {
        return type;
    }

    public PlayerEventPayload payload() {
        return payload;
    }

    public List<PlayerEventPayload> players() {
        return players;
    }

    public long observedAt() {
        return observedAt;
    }

    public String serverName() {
        return serverName;
    }

    public static ReportTask event(PlayerEventType type, PlayerEventPayload payload) {
        return new ReportTask(UUID.randomUUID().toString(), false, type, payload, null, payload.eventAt(), payload.serverName());
    }

    public static ReportTask snapshot(Collection<PlayerEventPayload> players, long observedAt, String serverName) {
        return new ReportTask(UUID.randomUUID().toString(), true, null, null, new ArrayList<PlayerEventPayload>(players), observedAt, serverName);
    }

    public JsonValue toJson() {
        JsonValue node = JsonValue.object()
                .put("id", id)
                .put("kind", snapshot ? "snapshot" : "event");
        if (snapshot) {
            node.put("observedAt", observedAt);
            node.put("server", serverName == null ? "" : serverName);
            JsonValue list = JsonValue.array();
            for (PlayerEventPayload player : players) {
                list.add(JsonValue.object()
                        .put("playerId", player.playerId())
                        .put("playerName", player.playerName()));
            }
            node.put("players", list);
        } else {
            node.put("type", type.getRemotePath());
            node.put("server", serverName == null ? "" : serverName);
            JsonValue player = JsonValue.object()
                    .put("playerId", payload.playerId())
                    .put("playerName", payload.playerName())
                    .put("eventAt", payload.eventAt());
            if (payload.content() != null && !payload.content().isEmpty()) {
                player.put("content", payload.content());
            }
            node.put("player", player);
        }
        return node;
    }

    /** Rebuilds a task from a journal line. Returns {@code null} when the line is unusable. */
    public static ReportTask fromJson(JsonValue node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String id = node.getOr("id", JsonValue.of("")).asString("");
        if (id.isEmpty()) {
            return null;
        }
        String serverName = node.getOr("server", JsonValue.of("")).asString("");
        if (serverName.isEmpty()) {
            serverName = null;
        }
        String kind = node.getOr("kind", JsonValue.of("event")).asString("event");
        if ("snapshot".equals(kind)) {
            List<PlayerEventPayload> players = new ArrayList<PlayerEventPayload>();
            for (JsonValue entry : node.getOr("players", JsonValue.array()).items()) {
                String playerId = entry.getOr("playerId", JsonValue.of("")).asString("");
                if (!playerId.isEmpty()) {
                    players.add(new PlayerEventPayload(playerId, entry.getOr("playerName", JsonValue.of("")).asString(""), 0L, serverName));
                }
            }
            return new ReportTask(id, true, null, null, players,
                    node.getOr("observedAt", JsonValue.of(0L)).asLong(0L), serverName);
        }
        PlayerEventType type = PlayerEventType.fromRemotePath(node.getOr("type", JsonValue.of("")).asString(""));
        if (type == null) {
            return null;
        }
        JsonValue player = node.get("player");
        if (player == null || !player.isObject()) {
            return null;
        }
        String playerId = player.getOr("playerId", JsonValue.of("")).asString("");
        if (playerId.isEmpty()) {
            return null;
        }
        PlayerEventPayload payload = new PlayerEventPayload(
                playerId,
                player.getOr("playerName", JsonValue.of("")).asString(""),
                player.getOr("eventAt", JsonValue.of(0L)).asLong(0L),
                serverName,
                player.has("content") ? player.get("content").asString(null) : null);
        return new ReportTask(id, false, type, payload, null, payload.eventAt(), serverName);
    }

    /** Human readable label used by every report log line. */
    public String describe(boolean includePayload) {
        if (snapshot) {
            return "type=SNAPSHOT, endpoint=/players/snapshot, players=" + players.size()
                    + (serverName == null ? "" : ", server=" + serverName)
                    + (includePayload ? ", observedAt=" + observedAt : "");
        }
        StringBuilder message = new StringBuilder();
        message.append("type=").append(type);
        message.append(", endpoint=/").append(type.getRemotePath());
        message.append(", player=").append(payload.playerName());
        if (serverName != null) {
            message.append(", server=").append(serverName);
        }
        if (includePayload) {
            message.append(", playerId=").append(payload.playerId());
            message.append(", eventAt=").append(payload.eventAt());
            if (payload.content() != null) {
                message.append(", content=").append(payload.content());
            }
        }
        return message.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReportTask)) {
            return false;
        }
        ReportTask other = (ReportTask) o;
        return snapshot == other.snapshot
                && observedAt == other.observedAt
                && (id == null ? other.id == null : id.equals(other.id))
                && type == other.type
                && (payload == null ? other.payload == null : payload.equals(other.payload))
                && players.equals(other.players)
                && (serverName == null ? other.serverName == null : serverName.equals(other.serverName));
    }

    @Override
    public int hashCode() {
        int result = id == null ? 0 : id.hashCode();
        result = 31 * result + (snapshot ? 1 : 0);
        result = 31 * result + (type == null ? 0 : type.hashCode());
        result = 31 * result + (payload == null ? 0 : payload.hashCode());
        result = 31 * result + players.hashCode();
        result = 31 * result + (int) (observedAt ^ (observedAt >>> 32));
        result = 31 * result + (serverName == null ? 0 : serverName.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "ReportTask[id=" + id + ", snapshot=" + snapshot + ", type=" + type
                + ", payload=" + payload + ", players=" + players + ", observedAt=" + observedAt
                + ", serverName=" + serverName + "]";
    }
}
