package online.yudream.minecraft.bridge.common.protocol;

import online.yudream.minecraft.bridge.common.model.PlayerIdentity;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BridgeProtocolTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    public void roundTripsHello() {
        BridgeMessage.Hello hello = new BridgeMessage.Hello("survival", "1.0.0", BridgeProtocol.PROTOCOL_VERSION,
                List.of(new PlayerIdentity(PLAYER, "Steve")), 1783512000000L);
        BridgeMessage decoded = BridgeProtocol.decode(BridgeProtocol.encode(hello));
        assertTrue(decoded instanceof BridgeMessage.Hello);
        BridgeMessage.Hello result = (BridgeMessage.Hello) decoded;
        assertEquals("survival", result.serverName());
        assertEquals("1.0.0", result.modVersion());
        assertEquals(1, result.players().size());
        assertEquals(PLAYER, result.players().get(0).uuid());
        assertEquals("Steve", result.players().get(0).name());
        assertEquals(1783512000000L, result.at());
    }

    @Test
    public void roundTripsActivityEvents() {
        BridgeMessage.Event event = new BridgeMessage.Event("survival", BridgeMessage.KIND_ACTIVITY,
                BridgeMessage.SOURCE_CHAT, new PlayerIdentity(PLAYER, "Steve"), 1783512000000L);
        BridgeMessage decoded = BridgeProtocol.decode(BridgeProtocol.encode(event));
        BridgeMessage.Event result = (BridgeMessage.Event) decoded;
        assertEquals(BridgeMessage.KIND_ACTIVITY, result.kind());
        assertEquals(BridgeMessage.SOURCE_CHAT, result.source());
        assertEquals(PLAYER, result.player().uuid());
        assertEquals(1783512000000L, result.at());
    }

    @Test
    public void roundTripsProbeAndAck() {
        BridgeMessage probe = BridgeProtocol.decode(BridgeProtocol.encode(new BridgeMessage.Probe("1.0.0", 1)));
        assertTrue(probe instanceof BridgeMessage.Probe);
        assertEquals("1.0.0", ((BridgeMessage.Probe) probe).proxyVersion());

        BridgeMessage ack = BridgeProtocol.decode(BridgeProtocol.encode(new BridgeMessage.HelloAck(true, "survival", "1.0.0", 1)));
        assertTrue(ack instanceof BridgeMessage.HelloAck);
        assertTrue(((BridgeMessage.HelloAck) ack).accepting());
        assertEquals("survival", ((BridgeMessage.HelloAck) ack).targetServer());
    }

    @Test
    public void acceptsTheAdminStylePlayerFieldNames() {
        byte[] payload = ("{\"t\":\"event\",\"kind\":\"join\",\"server\":\"survival\","
                + "\"player\":{\"playerId\":\"" + PLAYER + "\",\"playerName\":\"Steve\"}}")
                .getBytes(StandardCharsets.UTF_8);
        BridgeMessage decoded = BridgeProtocol.decode(payload);
        assertEquals(PLAYER, ((BridgeMessage.Event) decoded).player().uuid());
    }

    @Test
    public void missingPlayerIsTolerated() {
        byte[] payload = "{\"t\":\"event\",\"kind\":\"activity\",\"source\":\"move\"}".getBytes(StandardCharsets.UTF_8);
        assertNull(((BridgeMessage.Event) BridgeProtocol.decode(payload)).player());
    }

    @Test(expected = ProtocolException.class)
    public void rejectsUnknownMessageTypes() {
        BridgeProtocol.decode("{\"t\":\"nope\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test(expected = ProtocolException.class)
    public void rejectsNonJsonPayloads() {
        BridgeProtocol.decode(new byte[]{0x01, 0x02, 0x03});
    }

    @Test(expected = ProtocolException.class)
    public void rejectsEmptyPayloads() {
        BridgeProtocol.decode(new byte[0]);
    }

    @Test(expected = ProtocolException.class)
    public void rejectsEventsWithoutAKind() {
        BridgeProtocol.decode("{\"t\":\"event\"}".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The Bukkit plugin reimplements this protocol in Java 8 so the same jar runs on 1.8.8 and newer.
     * These literals are asserted byte for byte by that plugin's own BridgeProtocolTest, so a change
     * to either side fails a test instead of silently breaking the channel.
     */
    @Test
    public void decodesTheBukkitSensorPayloadsVerbatim() {
        String id = "11111111-2222-3333-4444-555555555555";

        BridgeMessage hello = BridgeProtocol.decode(("{\"t\":\"hello\",\"protocol\":1,\"mod\":\"1.0.0\","
                + "\"server\":\"\",\"at\":1783512000000,"
                + "\"players\":[{\"id\":\"" + id + "\",\"name\":\"Steve\"}]}").getBytes(StandardCharsets.UTF_8));
        assertTrue(hello instanceof BridgeMessage.Hello);
        BridgeMessage.Hello decodedHello = (BridgeMessage.Hello) hello;
        assertEquals(1, decodedHello.protocolVersion());
        assertEquals("1.0.0", decodedHello.modVersion());
        assertEquals(1, decodedHello.players().size());
        assertEquals(PLAYER, decodedHello.players().get(0).uuid());
        assertEquals(1783512000000L, decodedHello.at());

        BridgeMessage event = BridgeProtocol.decode(("{\"t\":\"event\",\"server\":\"\",\"kind\":\"activity\","
                + "\"at\":1783512000000,\"source\":\"chat\","
                + "\"player\":{\"id\":\"" + id + "\",\"name\":\"Steve\"}}").getBytes(StandardCharsets.UTF_8));
        assertTrue(event instanceof BridgeMessage.Event);
        BridgeMessage.Event decodedEvent = (BridgeMessage.Event) event;
        assertEquals(BridgeMessage.KIND_ACTIVITY, decodedEvent.kind());
        assertEquals(BridgeMessage.SOURCE_CHAT, decodedEvent.source());
        assertEquals(PLAYER, decodedEvent.player().uuid());

        BridgeMessage presence = BridgeProtocol.decode(("{\"t\":\"event\",\"server\":\"\",\"kind\":\"join\","
                + "\"at\":1783512000000,"
                + "\"player\":{\"id\":\"" + id + "\",\"name\":\"Steve\"}}").getBytes(StandardCharsets.UTF_8));
        assertEquals(BridgeMessage.KIND_JOIN, ((BridgeMessage.Event) presence).kind());
        assertNull(((BridgeMessage.Event) presence).source());
    }
}
