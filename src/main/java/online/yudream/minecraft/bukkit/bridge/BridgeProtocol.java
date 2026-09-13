package online.yudream.minecraft.bukkit.bridge;

import online.yudream.minecraft.bukkit.report.PlayerEventPayload;
import online.yudream.minecraft.bukkit.util.JsonObjects;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Map;

/**
 * The wire format shared with the Velocity bridge, reimplemented for Java 8.
 *
 * <p>This deliberately mirrors {@code bridge/common/BridgeProtocol} on the proxy side. It is a second
 * implementation rather than a shared one because this plugin is compiled to Java 8 bytecode so the
 * same jar runs on 1.8.8 and newer, and the shared core uses records and sealed interfaces. The
 * channel name, the protocol version and the message shapes are the contract; the tests pin them.
 */
public final class BridgeProtocol {

    /** Must match {@code BridgeProtocol.CHANNEL} in the Velocity plugin. */
    public static final String CHANNEL = "yudream:bridge";

    /** Bumped whenever the message shape changes in a way an older peer cannot read. */
    public static final int PROTOCOL_VERSION = 1;

    public static final String TYPE_HELLO = "hello";
    public static final String TYPE_HELLO_ACK = "hello_ack";
    public static final String TYPE_PROBE = "probe";
    public static final String TYPE_EVENT = "event";

    public static final String KIND_JOIN = "join";
    public static final String KIND_QUIT = "quit";
    public static final String KIND_ACTIVITY = "activity";

    public static final String SOURCE_CHAT = "chat";
    public static final String SOURCE_MOVE = "move";
    public static final String SOURCE_INTERACT = "interact";
    public static final String SOURCE_COMMAND = "command";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private BridgeProtocol() {
    }

    /**
     * A hello tells the proxy that this backend runs a sensor, and lists who is online here.
     *
     * <p>The server name is left empty on purpose: the proxy names this backend from the connection
     * the message arrived on, which cannot be spoofed by a stale config value.
     */
    public static byte[] encodeHello(String modVersion, Collection<PlayerEventPayload> players, long at) {
        StringBuilder json = new StringBuilder(128);
        json.append("{\"t\":\"").append(TYPE_HELLO).append('"');
        json.append(",\"protocol\":").append(PROTOCOL_VERSION);
        json.append(",\"mod\":\"").append(JsonObjects.escape(modVersion)).append('"');
        json.append(",\"server\":\"\"");
        json.append(",\"at\":").append(at);
        json.append(",\"players\":[");
        boolean first = true;
        for (PlayerEventPayload player : players) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"id\":\"").append(JsonObjects.escape(player.getPlayerId()))
                    .append("\",\"name\":\"").append(JsonObjects.escape(player.getPlayerName())).append("\"}");
        }
        return bytes(json.append("]}").toString());
    }

    /**
     * One activity or presence report.
     *
     * <p>{@code join} and {@code quit} are advisory: the proxy owns presence and only uses them to
     * repair a gap it missed. {@code activity} feeds the proxy-side AFK state machine.
     */
    public static byte[] encodeEvent(String kind, String source, PlayerEventPayload player, long at) {
        StringBuilder json = new StringBuilder(128);
        json.append("{\"t\":\"").append(TYPE_EVENT).append('"');
        json.append(",\"server\":\"\"");
        json.append(",\"kind\":\"").append(JsonObjects.escape(kind)).append('"');
        json.append(",\"at\":").append(at);
        if (source != null && !source.isEmpty()) {
            json.append(",\"source\":\"").append(JsonObjects.escape(source)).append('"');
        }
        if (player != null) {
            json.append(",\"player\":{\"id\":\"").append(JsonObjects.escape(player.getPlayerId()))
                    .append("\",\"name\":\"").append(JsonObjects.escape(player.getPlayerName())).append("\"}");
        }
        return bytes(json.append('}').toString());
    }

    /**
     * Decodes a message the proxy sent to this backend.
     *
     * @return the decoded message, or {@code null} when the bytes are not a bridge message this build
     *         understands
     */
    public static Incoming decode(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        Map<String, Object> root = MiniJson.parseObject(new String(data, UTF_8));
        if (root == null) {
            return null;
        }
        String type = MiniJson.string(root, "t", "");
        if (!TYPE_PROBE.equals(type) && !TYPE_HELLO_ACK.equals(type)) {
            return null;
        }
        return new Incoming(
                type,
                MiniJson.integer(root, "protocol", 0),
                MiniJson.string(root, "proxy", ""),
                MiniJson.bool(root, "accepting", true),
                MiniJson.string(root, "target", ""));
    }

    private static byte[] bytes(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable", e);
        }
    }

    /** A message received from the proxy. */
    public static final class Incoming {

        private final String type;
        private final int protocolVersion;
        private final String proxyVersion;
        private final boolean accepting;
        private final String targetServer;

        private Incoming(String type, int protocolVersion, String proxyVersion, boolean accepting, String targetServer) {
            this.type = type;
            this.protocolVersion = protocolVersion;
            this.proxyVersion = proxyVersion;
            this.accepting = accepting;
            this.targetServer = targetServer;
        }

        public String getType() {
            return type;
        }

        public int getProtocolVersion() {
            return protocolVersion;
        }

        public String getProxyVersion() {
            return proxyVersion;
        }

        /** Whether the proxy currently reports this downstream server. */
        public boolean isAccepting() {
            return accepting;
        }

        public String getTargetServer() {
            return targetServer;
        }

        public boolean isProbe() {
            return TYPE_PROBE.equals(type);
        }

        public boolean isHelloAck() {
            return TYPE_HELLO_ACK.equals(type);
        }
    }
}
