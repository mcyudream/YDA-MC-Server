package online.yudream.minecraft.bridge.common.protocol;

import online.yudream.minecraft.bridge.common.json.JsonSyntaxException;
import online.yudream.minecraft.bridge.common.json.JsonValue;
import online.yudream.minecraft.bridge.common.model.PlayerIdentity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Channel name, protocol version and the JSON codec for {@link BridgeMessage}. */
public final class BridgeProtocol {

    /** Must stay identical in the Velocity plugin and in every Fabric sensor build. */
    public static final String CHANNEL_NAMESPACE = "yudream";
    public static final String CHANNEL_PATH = "bridge";
    public static final String CHANNEL = CHANNEL_NAMESPACE + ":" + CHANNEL_PATH;

    /**
     * Bumped whenever the message shape changes in a way an older peer cannot read. The proxy
     * rejects a hello from a newer protocol instead of guessing.
     */
    public static final int PROTOCOL_VERSION = 1;

    private BridgeProtocol() {
    }

    public static byte[] encode(BridgeMessage message) {
        return message.toJson().toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes a payload received on {@link #CHANNEL}.
     *
     * @throws ProtocolException when the bytes are not a bridge message this build understands
     */
    public static BridgeMessage decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new ProtocolException("Empty plugin message");
        }
        JsonValue root;
        try {
            root = JsonValue.parse(new String(data, StandardCharsets.UTF_8));
        } catch (JsonSyntaxException e) {
            throw new ProtocolException("Malformed bridge payload: " + e.getMessage());
        }
        if (!root.isObject()) {
            throw new ProtocolException("Bridge payload must be a JSON object");
        }
        String type = root.getOr("t", JsonValue.of("")).asString("");
        switch (type) {
            case BridgeMessage.TYPE_HELLO:
                return readHello(root);
            case BridgeMessage.TYPE_PROBE:
                return new BridgeMessage.Probe(
                        root.getOr("proxy", JsonValue.of("")).asString(""),
                        root.getOr("protocol", JsonValue.of(PROTOCOL_VERSION)).asInt(PROTOCOL_VERSION));
            case BridgeMessage.TYPE_HELLO_ACK:
                return new BridgeMessage.HelloAck(
                        root.getOr("accepting", JsonValue.of(true)).asBoolean(true),
                        root.getOr("target", JsonValue.of("")).asString(""),
                        root.getOr("proxy", JsonValue.of("")).asString(""),
                        root.getOr("protocol", JsonValue.of(PROTOCOL_VERSION)).asInt(PROTOCOL_VERSION));
            case BridgeMessage.TYPE_EVENT:
                return readEvent(root);
            default:
                throw new ProtocolException("Unknown bridge message type '" + type + "'");
        }
    }

    /** Reads a player object in either the {@code {id,name}} or the Admin {@code {playerId,playerName}} shape. */
    public static PlayerIdentity readIdentity(JsonValue node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String id = node.getOr("id", node.getOr("playerId", JsonValue.of(""))).asString("");
        String name = node.getOr("name", node.getOr("playerName", JsonValue.of(""))).asString("");
        if (id.isEmpty()) {
            return null;
        }
        try {
            return new PlayerIdentity(UUID.fromString(id), name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BridgeMessage readHello(JsonValue root) {
        List<PlayerIdentity> players = new ArrayList<PlayerIdentity>();
        for (JsonValue entry : root.getOr("players", JsonValue.array()).items()) {
            PlayerIdentity identity = readIdentity(entry);
            if (identity != null) {
                players.add(identity);
            }
        }
        return new BridgeMessage.Hello(
                root.getOr("server", JsonValue.of("")).asString(""),
                root.getOr("mod", JsonValue.of("")).asString(""),
                root.getOr("protocol", JsonValue.of(0)).asInt(0),
                players,
                root.getOr("at", JsonValue.of(0L)).asLong(0L));
    }

    private static BridgeMessage readEvent(JsonValue root) {
        String kind = root.getOr("kind", JsonValue.of("")).asString("");
        if (kind.isEmpty()) {
            throw new ProtocolException("Bridge event has no kind");
        }
        return new BridgeMessage.Event(
                root.getOr("server", JsonValue.of("")).asString(""),
                kind,
                root.has("source") ? root.get("source").asString(null) : null,
                readIdentity(root.get("player")),
                root.getOr("at", JsonValue.of(0L)).asLong(0L));
    }
}
