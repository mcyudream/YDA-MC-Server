package online.yudream.minecraft.bukkit.bridge;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MiniJsonTest {

    @Test
    public void parsesNestedObjectsAndArrays() {
        Map<String, Object> root = MiniJson.parseObject(
                "{\"a\":1,\"b\":{\"c\":\"x\"},\"d\":[1,2,3],\"e\":true,\"f\":null}");
        assertEquals(1L, root.get("a"));
        assertEquals("x", MiniJson.string((Map<String, Object>) root.get("b"), "c", ""));
        assertEquals(3, ((List<?>) root.get("d")).size());
        assertEquals(Boolean.TRUE, root.get("e"));
        assertTrue(root.containsKey("f"));
        assertNull(root.get("f"));
    }

    @Test
    public void readsTheProxyMessages() {
        Map<String, Object> probe = MiniJson.parseObject("{\"t\":\"probe\",\"protocol\":1,\"proxy\":\"1.0.0\"}");
        assertEquals("probe", MiniJson.string(probe, "t", ""));
        assertEquals(1, MiniJson.integer(probe, "protocol", 0));

        Map<String, Object> ack = MiniJson.parseObject(
                "{\"t\":\"hello_ack\",\"protocol\":1,\"proxy\":\"1.0.0\",\"accepting\":true,\"target\":\"survival\"}");
        assertTrue(MiniJson.bool(ack, "accepting", false));
        assertEquals("survival", MiniJson.string(ack, "target", ""));
    }

    @Test
    public void handlesEscapesAndUnicode() {
        Map<String, Object> root = MiniJson.parseObject(
                "{\"q\":\"a\\\"b\\\\c\\nd\",\"u\":\"\\u4f60\\u597d\",\"slash\":\"a\\/b\"}");
        assertEquals("a\"b\\c\nd", MiniJson.string(root, "q", ""));
        assertEquals("\u4f60\u597d", MiniJson.string(root, "u", ""));
        assertEquals("a/b", MiniJson.string(root, "slash", ""));
    }

    @Test
    public void keepsIntegralNumbersIntegral() {
        Map<String, Object> root = MiniJson.parseObject("{\"big\":1783512000000,\"frac\":1.5,\"exp\":2e3}");
        assertEquals(1783512000000L, MiniJson.longValue(root, "big", 0L));
        assertEquals(1, MiniJson.integer(root, "frac", 0));
        assertEquals(2000, MiniJson.integer(root, "exp", 0));
    }

    @Test
    public void emptyContainersAreSupported() {
        Map<String, Object> root = MiniJson.parseObject("{\"o\":{},\"a\":[]}");
        assertTrue(((Map<?, ?>) root.get("o")).isEmpty());
        assertTrue(((List<?>) root.get("a")).isEmpty());
    }

    @Test
    public void malformedInputReturnsNullInsteadOfThrowing() {
        assertNull(MiniJson.parseObject("not json"));
        assertNull(MiniJson.parseObject("{\"a\":1"));
        assertNull(MiniJson.parseObject(""));
        assertNull(MiniJson.parseObject(null));
        assertNull(MiniJson.parseObject("[1,2,3]"));
    }

    @Test
    public void accessorsFallBackForMissingOrWrongTypedValues() {
        Map<String, Object> root = MiniJson.parseObject("{\"n\":123,\"s\":\"text\"}");
        // Missing keys always fall back.
        assertEquals("fallback", MiniJson.string(root, "missing", "fallback"));
        assertEquals(7, MiniJson.integer(root, "missing", 7));
        assertTrue(MiniJson.bool(root, "missing", true));
        // So do present keys of the wrong type.
        assertEquals("fallback", MiniJson.string(root, "n", "fallback"));
        assertEquals(7, MiniJson.integer(root, "s", 7));
        assertTrue(MiniJson.bool(root, "s", true));
        // Correctly typed values come back as they are.
        assertEquals("text", MiniJson.string(root, "s", "fallback"));
        assertEquals(123, MiniJson.integer(root, "n", 0));
        assertFalse(MiniJson.bool(MiniJson.parseObject("{\"b\":false}"), "b", true));
    }
}
