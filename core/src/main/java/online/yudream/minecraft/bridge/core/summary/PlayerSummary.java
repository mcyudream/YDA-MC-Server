package online.yudream.minecraft.bridge.core.summary;

import online.yudream.minecraft.bridge.core.json.JsonValue;

import java.util.Collections;
import java.util.List;

/**
 * A tolerant reader for the {@code GET .../players} response.
 *
 * <p>The Bukkit plugin used to count occurrences of {@code "playerId"} and {@code "online":true} in
 * the raw body, which breaks the moment a player name contains those characters. This parses the
 * JSON properly and accepts either a bare array or the usual paged envelope.
 */
public final class PlayerSummary {

    private static final String[] ARRAY_FIELDS = {
            "items", "records", "list", "data", "content", "players", "rows"
    };

    private final int total;
    private final int online;
    private final int afk;

    public PlayerSummary(int total, int online, int afk) {
        this.total = total;
        this.online = online;
        this.afk = afk;
    }

    public int total() {
        return total;
    }

    public int online() {
        return online;
    }

    public int afk() {
        return afk;
    }

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
            return Collections.emptyList();
        }
        for (String field : ARRAY_FIELDS) {
            JsonValue candidate = root.get(field);
            if (candidate != null && candidate.isArray()) {
                return candidate.items();
            }
        }
        return Collections.emptyList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlayerSummary)) {
            return false;
        }
        PlayerSummary other = (PlayerSummary) o;
        return total == other.total && online == other.online && afk == other.afk;
    }

    @Override
    public int hashCode() {
        int result = total;
        result = 31 * result + online;
        result = 31 * result + afk;
        return result;
    }

    @Override
    public String toString() {
        return "PlayerSummary[total=" + total + ", online=" + online + ", afk=" + afk + "]";
    }
}
