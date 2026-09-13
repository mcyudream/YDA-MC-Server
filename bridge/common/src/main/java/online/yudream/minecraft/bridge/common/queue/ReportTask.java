package online.yudream.minecraft.bridge.common.queue;

import online.yudream.minecraft.bridge.common.json.JsonValue;
import online.yudream.minecraft.bridge.common.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.common.model.PlayerEventType;

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
public record ReportTask(String id,
                         boolean snapshot,
                         PlayerEventType type,
                         PlayerEventPayload payload,
                         List<PlayerEventPayload> players,
                         long observedAt,
                         String serverName) {

    public ReportTask {
        players = players == null
                ? Collections.<PlayerEventPayload>emptyList()
                : Collections.unmodifiableList(new ArrayList<PlayerEventPayload>(players));
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
            node.put("player", JsonValue.object()
                    .put("playerId", payload.playerId())
                    .put("playerName", payload.playerName())
                    .put("eventAt", payload.eventAt()));
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
                serverName);
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
        message.append(", endpoint=/players/").append(type.getRemotePath());
        message.append(", player=").append(payload.playerName());
        if (serverName != null) {
            message.append(", server=").append(serverName);
        }
        if (includePayload) {
            message.append(", playerId=").append(payload.playerId());
            message.append(", eventAt=").append(payload.eventAt());
        }
        return message.toString();
    }
}
