package online.yudream.minecraft.bridge.common.queue;

import online.yudream.minecraft.bridge.common.json.JsonValue;
import online.yudream.minecraft.bridge.common.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.common.model.PlayerEventType;
import online.yudream.minecraft.bridge.common.model.SubServerRoster;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReportTaskTest {

    @Test
    public void roundTripsAnEvent() {
        ReportTask original = ReportTask.event(PlayerEventType.JOIN,
                new PlayerEventPayload("player-uuid", "Steve", 1783512000000L, "survival"));
        ReportTask restored = ReportTask.fromJson(JsonValue.parse(original.toJson().toString()));
        assertEquals(original.id(), restored.id());
        assertEquals(PlayerEventType.JOIN, restored.type());
        assertFalse(restored.snapshot());
        assertEquals("player-uuid", restored.payload().playerId());
        assertEquals("Steve", restored.payload().playerName());
        assertEquals(1783512000000L, restored.payload().eventAt());
        assertEquals("survival", restored.serverName());
    }

    @Test
    public void roundTripsASnapshot() {
        ReportTask original = ReportTask.snapshot(List.of(
                new PlayerEventPayload("a", "Steve", 0L),
                new PlayerEventPayload("b", "Alex", 0L)), 1783512000000L, "survival");
        ReportTask restored = ReportTask.fromJson(JsonValue.parse(original.toJson().toString()));
        assertTrue(restored.snapshot());
        assertEquals(2, restored.players().size());
        assertEquals("Alex", restored.players().get(1).playerName());
        assertEquals(1783512000000L, restored.observedAt());
        assertEquals("survival", restored.serverName());
    }

    @Test
    public void roundTripsAGroupedSnapshot() {
        ReportTask original = ReportTask.groupedSnapshot(List.of(
                new SubServerRoster("fabric", List.of(new PlayerEventPayload("a", "Steve", 0L, "fabric"))),
                SubServerRoster.empty("paper")), 1783512000000L);
        ReportTask restored = ReportTask.fromJson(JsonValue.parse(original.toJson().toString()));
        assertTrue(restored.snapshot());
        assertTrue(restored.grouped());
        assertEquals(2, restored.servers().size());
        assertEquals("fabric", restored.servers().get(0).name());
        assertEquals("Steve", restored.servers().get(0).players().get(0).playerName());
        // 空名册必须在日志里活下来，否则重启后这次“该子服没人”的对账就丢了。
        assertEquals("paper", restored.servers().get(1).name());
        assertTrue(restored.servers().get(1).players().isEmpty());
        assertEquals(1783512000000L, restored.observedAt());
    }

    @Test
    public void groupedSnapshotDescribeNamesTheSubServerCount() {
        ReportTask task = ReportTask.groupedSnapshot(List.of(
                new SubServerRoster("fabric", List.of(new PlayerEventPayload("a", "Steve", 0L, "fabric"))),
                SubServerRoster.empty("paper")), 1L);

        String described = task.describe(false);

        assertTrue(described.contains("endpoint=/players/snapshot"));
        assertTrue(described.contains("subServers=2"));
        assertTrue(described.contains("players=1"));
    }

    @Test
    public void aFlatSnapshotStaysFlatAfterARoundTrip() {
        ReportTask original = ReportTask.snapshot(List.of(new PlayerEventPayload("a", "Steve", 0L)), 5L, "survival");
        ReportTask restored = ReportTask.fromJson(JsonValue.parse(original.toJson().toString()));

        assertFalse(restored.grouped());
        assertEquals("survival", restored.serverName());
        assertEquals(1, restored.players().size());
    }

    @Test
    public void everyTaskGetsItsOwnId() {
        ReportTask first = ReportTask.event(PlayerEventType.JOIN, new PlayerEventPayload("a", "Steve", 1L));
        ReportTask second = ReportTask.event(PlayerEventType.JOIN, new PlayerEventPayload("a", "Steve", 1L));
        assertFalse(first.id().equals(second.id()));
    }

    @Test
    public void unknownEventTypesAreRejectedOnLoad() {
        assertNull(ReportTask.fromJson(JsonValue.parse(
                "{\"id\":\"x\",\"kind\":\"event\",\"type\":\"teleport\",\"player\":{\"playerId\":\"a\"}}")));
    }

    @Test
    public void linesWithoutAnIdAreRejected() {
        assertNull(ReportTask.fromJson(JsonValue.parse("{\"kind\":\"event\",\"type\":\"join\"}")));
        assertNull(ReportTask.fromJson(JsonValue.tryParse("not json")));
        assertNull(ReportTask.fromJson(null));
    }

    @Test
    public void describeNamesTheEndpoint() {
        ReportTask task = ReportTask.event(PlayerEventType.AFK_START,
                new PlayerEventPayload("a", "Steve", 1L, "survival"));
        String described = task.describe(false);
        assertTrue(described.contains("endpoint=/players/afk/start"));
        assertTrue(described.contains("player=Steve"));
        assertTrue(described.contains("server=survival"));
        assertFalse(described.contains("playerId"));
    }
}
