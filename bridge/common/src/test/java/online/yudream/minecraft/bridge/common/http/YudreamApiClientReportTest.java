package online.yudream.minecraft.bridge.common.http;

import online.yudream.minecraft.bridge.common.config.BridgeSettings;
import online.yudream.minecraft.bridge.common.json.JsonValue;
import online.yudream.minecraft.bridge.common.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.common.model.SubServerRoster;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the player-report bodies the bridge sends to YuDream Admin.
 *
 * <p>The Admin plugin has matching schema tests, and the standalone Bukkit plugin still sends the
 * flat form, so both directions have to stay readable:
 *
 * <ul>
 *   <li>an event naming no sub-server must stay byte-identical to the legacy body</li>
 *   <li>an event naming one must add {@code server}</li>
 *   <li>the flat snapshot must keep working, {@code players} only</li>
 *   <li>the grouped snapshot lists every sub-server, including the empty ones</li>
 * </ul>
 */
public class YudreamApiClientReportTest {

    private static YudreamApiClient client() {
        return new YudreamApiClient(BridgeSettings.defaults(), "yudream-bridge-test");
    }

    @Test
    public void eventBodyNamesTheSubServerWhenThereIsOne() {
        JsonValue root = JsonValue.parse(client().eventBody(
                new PlayerEventPayload("player-uuid", "Steve", 1757850000000L, "fabric")));

        assertEquals("player-uuid", root.getOr("playerId", JsonValue.of("")).asString(""));
        assertEquals("Steve", root.getOr("playerName", JsonValue.of("")).asString(""));
        assertEquals(1757850000000L, root.getOr("eventAt", JsonValue.of(0L)).asLong(0L));
        assertEquals("fabric", root.getOr("server", JsonValue.of("")).asString(""));
    }

    @Test
    public void eventBodyWithoutASubServerKeepsTheLegacyShape() {
        String body = client().eventBody(new PlayerEventPayload("player-uuid", "Steve", 1L, null));

        assertFalse(body.contains("server"));
        JsonValue root = JsonValue.parse(body);
        assertEquals("player-uuid", root.getOr("playerId", JsonValue.of("")).asString(""));
    }

    @Test
    public void blankSubServerNameIsOmittedEntirely() {
        String body = client().eventBody(new PlayerEventPayload("player-uuid", "Steve", 1L, ""));

        assertFalse(body.contains("server"));
    }

    @Test
    public void groupedSnapshotBodyListsEverySubServerIncludingEmptyOnes() {
        String body = client().groupedSnapshotBody(List.of(
                new SubServerRoster("fabric", List.of(
                        new PlayerEventPayload("a", "Steve", 0L, "fabric"),
                        new PlayerEventPayload("b", "Alex", 0L, "fabric"))),
                SubServerRoster.empty("paper")), 1757850000000L);

        JsonValue root = JsonValue.parse(body);
        assertEquals(1757850000000L, root.getOr("observedAt", JsonValue.of(0L)).asLong(0L));
        // 分组形态只有 servers，不再有顶层的 players。
        assertFalse(root.has("players"));

        List<JsonValue> servers = root.getOr("servers", JsonValue.array()).items();
        assertEquals(2, servers.size());
        assertEquals("fabric", servers.get(0).getOr("name", JsonValue.of("")).asString(""));
        assertEquals(2, servers.get(0).getOr("players", JsonValue.array()).items().size());
        // 空名册必须被保留：它就是“这个子服现在没人”的表达。
        assertEquals("paper", servers.get(1).getOr("name", JsonValue.of("")).asString(""));
        assertTrue(servers.get(1).getOr("players", JsonValue.array()).items().isEmpty());
    }

    @Test
    public void flatSnapshotBodyIsUnchangedByDefault() {
        String body = client().snapshotBody(
                List.of(new PlayerEventPayload("a", "Steve", 0L, "fabric")), 7L, "fabric");

        // serverName 只在开关打开时出现，默认形态与旧版一致。
        assertFalse(body.contains("serverName"));
        JsonValue root = JsonValue.parse(body);
        assertEquals(7L, root.getOr("observedAt", JsonValue.of(0L)).asLong(0L));
        assertEquals(1, root.getOr("players", JsonValue.array()).items().size());
    }

    @Test
    public void groupedSnapshotWithNoSubServersSerialisesAnEmptyArray() {
        JsonValue root = JsonValue.parse(client().groupedSnapshotBody(Collections.emptyList(), 1L));

        assertTrue(root.getOr("servers", JsonValue.array()).items().isEmpty());
    }
}
