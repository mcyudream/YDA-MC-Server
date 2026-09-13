package online.yudream.minecraft.bukkit.bridge;

import online.yudream.minecraft.bukkit.report.PlayerEventPayload;
import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the wire format shared with the Velocity bridge.
 *
 * <p>The exact byte strings below are asserted on the proxy side too
 * ({@code bridge/common/.../BridgeProtocolTest}), so a change to either implementation fails a test
 * instead of silently breaking the channel.
 */
public class BridgeProtocolTest {

    private static final String UUID = "11111111-2222-3333-4444-555555555555";
    private static final long AT = 1783512000000L;

    private static PlayerEventPayload player() {
        return new PlayerEventPayload(UUID, "Steve", AT);
    }

    @Test
    public void helloMatchesTheProxyContract() {
        String expected = "{\"t\":\"hello\",\"protocol\":1,\"mod\":\"1.0.0\",\"server\":\"\",\"at\":1783512000000,"
                + "\"players\":[{\"id\":\"" + UUID + "\",\"name\":\"Steve\"}]}";
        assertEquals(expected, text(BridgeProtocol.encodeHello("1.0.0", Collections.singletonList(player()), AT)));
    }

    @Test
    public void helloWithNoPlayersIsStillValid() {
        String expected = "{\"t\":\"hello\",\"protocol\":1,\"mod\":\"1.0.0\",\"server\":\"\",\"at\":1783512000000,\"players\":[]}";
        assertEquals(expected, text(BridgeProtocol.encodeHello("1.0.0", Collections.<PlayerEventPayload>emptyList(), AT)));
    }

    @Test
    public void activityEventMatchesTheProxyContract() {
        String expected = "{\"t\":\"event\",\"server\":\"\",\"kind\":\"activity\",\"at\":1783512000000,"
                + "\"source\":\"chat\",\"player\":{\"id\":\"" + UUID + "\",\"name\":\"Steve\"}}";
        assertEquals(expected, text(BridgeProtocol.encodeEvent(
                BridgeProtocol.KIND_ACTIVITY, BridgeProtocol.SOURCE_CHAT, player(), AT)));
    }

    @Test
    public void presenceEventsOmmitTheSource() {
        String expected = "{\"t\":\"event\",\"server\":\"\",\"kind\":\"join\",\"at\":1783512000000,"
                + "\"player\":{\"id\":\"" + UUID + "\",\"name\":\"Steve\"}}";
        assertEquals(expected, text(BridgeProtocol.encodeEvent(BridgeProtocol.KIND_JOIN, null, player(), AT)));
    }

    @Test
    public void playerNamesAreEscaped() {
        PlayerEventPayload awkward = new PlayerEventPayload(UUID, "a\"b\\c\nd", AT);
        String encoded = text(BridgeProtocol.encodeEvent(BridgeProtocol.KIND_JOIN, null, awkward, AT));
        assertTrue(encoded.contains("\"name\":\"a\\\"b\\\\c\\nd\""));
        // And the result is still parseable, which is the real point.
        assertTrue(MiniJson.parseObject(encoded).containsKey("player"));
    }

    @Test
    public void decodesAProbe() {
        BridgeProtocol.Incoming incoming = BridgeProtocol.decode(
                bytes("{\"t\":\"probe\",\"protocol\":1,\"proxy\":\"1.0.0\"}"));
        assertTrue(incoming.isProbe());
        assertFalse(incoming.isHelloAck());
        assertEquals(1, incoming.getProtocolVersion());
        assertEquals("1.0.0", incoming.getProxyVersion());
    }

    @Test
    public void decodesAHelloAck() {
        BridgeProtocol.Incoming incoming = BridgeProtocol.decode(bytes(
                "{\"t\":\"hello_ack\",\"protocol\":1,\"proxy\":\"1.0.0\",\"accepting\":true,\"target\":\"survival\"}"));
        assertTrue(incoming.isHelloAck());
        assertTrue(incoming.isAccepting());
        assertEquals("survival", incoming.getTargetServer());
    }

    @Test
    public void ackFromAnUnsetTargetIsAccepted() {
        BridgeProtocol.Incoming incoming = BridgeProtocol.decode(bytes(
                "{\"t\":\"hello_ack\",\"protocol\":1,\"proxy\":\"1.0.0\",\"accepting\":false}"));
        assertEquals("", incoming.getTargetServer());
        assertFalse(incoming.isAccepting());
    }

    @Test
    public void ignoresAnythingElse() {
        assertNull(BridgeProtocol.decode(bytes("{\"t\":\"something_else\"}")));
        assertNull(BridgeProtocol.decode(bytes("not json")));
        assertNull(BridgeProtocol.decode(new byte[0]));
        assertNull(BridgeProtocol.decode(null));
    }

    @Test
    public void outgoingMessagesRoundTripThroughTheSharedParser() {
        // The proxy parses with the shared JSON reader; this asserts the shape it depends on.
        String hello = text(BridgeProtocol.encodeHello("1.0.0", Arrays.asList(player(), player()), AT));
        assertEquals("hello", MiniJson.string(MiniJson.parseObject(hello), "t", ""));
        assertEquals(2, ((java.util.List<?>) MiniJson.parseObject(hello).get("players")).size());
    }

    private static String text(byte[] data) {
        return new String(data, java.nio.charset.Charset.forName("UTF-8"));
    }

    private static byte[] bytes(String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
