package online.yudream.minecraft.bridge.common.json;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JsonValueTest {

    @Test
    public void parsesAndReserialisesAnAdminSnapshotBody() {
        String body = "{\"observedAt\":1783512000000,\"players\":[{\"playerId\":\"abc\",\"playerName\":\"Steve\"}]}";
        JsonValue parsed = JsonValue.parse(body);
        assertEquals(1783512000000L, parsed.get("observedAt").asLong(0L));
        assertEquals(1, parsed.get("players").items().size());
        assertEquals("Steve", parsed.get("players").items().get(0).get("playerName").asString(""));
        assertEquals(body, parsed.toString());
    }

    @Test
    public void buildsTheExactBodyTheExistingArtifactsSend() {
        String body = JsonValue.object()
                .put("playerId", "player-uuid")
                .put("playerName", "Steve")
                .put("eventAt", 1783512000000L)
                .toString();
        assertEquals("{\"playerId\":\"player-uuid\",\"playerName\":\"Steve\",\"eventAt\":1783512000000}", body);
    }

    @Test
    public void keepsIntegralNumbersIntegral() {
        assertEquals("{\"n\":42}", JsonValue.object().put("n", 42L).toString());
        assertEquals(42L, JsonValue.parse("{\"n\":42}").get("n").asLong(0L));
        assertEquals(42.5D, JsonValue.parse("{\"n\":42.5}").get("n").asDouble(0D), 0.0001D);
    }

    @Test
    public void roundTripsEscapes() {
        String tricky = "quote\" backslash\\ newline\n tab\t unicode\u00e9";
        String written = JsonValue.object().put("v", tricky).toString();
        assertEquals(tricky, JsonValue.parse(written).get("v").asString(""));
    }

    @Test
    public void readsUnicodeEscapes() {
        assertEquals("\u4f60\u597d", JsonValue.parse("{\"v\":\"\\u4f60\\u597d\"}").get("v").asString(""));
    }

    @Test
    public void handlesEmptyContainers() {
        assertEquals(0, JsonValue.parse("{}").members().size());
        assertEquals(0, JsonValue.parse("[]").items().size());
        assertEquals("{}", JsonValue.object().toString());
        assertEquals("[]", JsonValue.array().toString());
    }

    @Test
    public void fallsBackForMissingAndWrongTypedMembers() {
        JsonValue value = JsonValue.object().put("a", "text");
        assertEquals("fallback", value.getOr("missing", JsonValue.of("fallback")).asString(""));
        assertEquals(7, value.get("a").asInt(7));
        assertNull(value.get("missing"));
        assertFalse(value.has("missing"));
    }

    @Test
    public void treatsNullMembersAsAbsent() {
        JsonValue value = JsonValue.parse("{\"a\":null}");
        assertFalse(value.has("a"));
        assertEquals("fallback", value.getOr("a", JsonValue.of("fallback")).asString(""));
    }

    @Test
    public void tryParseReturnsNullInsteadOfThrowing() {
        assertNull(JsonValue.tryParse("{not json"));
        assertNull(JsonValue.tryParse(""));
        assertTrue(JsonValue.tryParse("{\"ok\":true}").get("ok").asBoolean(false));
    }

    @Test(expected = JsonSyntaxException.class)
    public void rejectsTrailingContent() {
        JsonValue.parse("{\"a\":1} trailing");
    }

    @Test(expected = JsonSyntaxException.class)
    public void rejectsUnterminatedObjects() {
        JsonValue.parse("{\"a\":1");
    }
}
