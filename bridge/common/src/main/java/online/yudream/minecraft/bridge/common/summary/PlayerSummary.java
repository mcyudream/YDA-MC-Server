package online.yudream.minecraft.bridge.common.summary;

import online.yudream.minecraft.bridge.common.json.JsonValue;

import java.util.List;

/**
 * A tolerant reader for the {@code GET .../players} response.
 *
 * <p>The Bukkit plugin used to count occurrences of {@code "playerId"} and {@code "online":true} in
 * the raw body, which breaks the moment a player name contains those characters. This parses the
 * JSON properly and accepts either a bare array or the usual paged envelope.
 */
public record PlayerSummary(int total, int online, int afk) {

    private static final String[] ARRAY_FIELDS = {
            "items", "records", "list", "data", "content", "players", "rows"
    };

    public static PlayerSummary parse(String json) {
        JsonValue root = JsonValue.tryParse(json == null ? "" : json);
        if (root == null) {
            return new PlayerSummary(0, 0, 0);
        }
        List<JsonValue> entries = entriesOf(root);
        int online = 0;
        int afk = 0;
        for (JsonValue entry : entries) {
            if (entry.getOr("online", JsonValue.of(false)).asBoolean(false)) {
                online++;
            }
            if (entry.getOr("afk", JsonValue.of(false)).asBoolean(false)) {
                afk++;
            }
        }
        long reportedTotal = root.isObject()
                ? root.getOr("total", root.getOr("totalElements", JsonValue.of((long) entries.size()))).asLong(entries.size())
                : entries.size();
        return new PlayerSummary((int) Math.max(reportedTotal, 0), online, afk);
    }

    private static List<JsonValue> entriesOf(JsonValue root) {
        if (root.isArray()) {
            return root.items();
        }
        if (!root.isObject()) {
            return List.of();
        }
        for (String field : ARRAY_FIELDS) {
            JsonValue candidate = root.get(field);
            if (candidate != null && candidate.isArray()) {
                return candidate.items();
            }
        }
        return List.of();
    }
}
