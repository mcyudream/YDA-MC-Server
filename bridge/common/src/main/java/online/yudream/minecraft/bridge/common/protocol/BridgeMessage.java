package online.yudream.minecraft.bridge.common.protocol;

import online.yudream.minecraft.bridge.common.json.JsonValue;
import online.yudream.minecraft.bridge.common.model.PlayerIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The plugin message payloads exchanged between a backend Fabric sensor and the Velocity bridge.
 *
 * <p>The transport is a custom plugin message channel ({@code yudream:bridge}) carrying a UTF-8 JSON
 * object with a {@code t} type discriminator. JSON keeps the sensor and the proxy decoupled: a
 * backend on an older protocol version can be ignored instead of crashing the proxy, and the wire
 * format is readable in a packet dump.
 */
public sealed interface BridgeMessage {

    String TYPE_HELLO = "hello";
    String TYPE_HELLO_ACK = "hello_ack";
    String TYPE_PROBE = "probe";
    String TYPE_EVENT = "event";

    String KIND_JOIN = "join";
    String KIND_QUIT = "quit";
    String KIND_ACTIVITY = "activity";

    String SOURCE_CHAT = "chat";
    String SOURCE_MOVE = "move";
    String SOURCE_INTERACT = "interact";
    String SOURCE_COMMAND = "command";
    String SOURCE_SERVER_SWITCH = "server-switch";

    String type();

    JsonValue toJson();

    /**
     * Sent by a backend sensor as soon as a player connects to that backend. It is the only way the
     * proxy can learn that a given downstream server actually runs the sensor mod, because Minecraft
     * plugin messages only travel over a player connection.
     */
    record Hello(String serverName,
                 String modVersion,
                 int protocolVersion,
                 List<PlayerIdentity> players,
                 long at) implements BridgeMessage {

        public Hello {
            players = players == null
                    ? Collections.<PlayerIdentity>emptyList()
                    : Collections.unmodifiableList(new ArrayList<PlayerIdentity>(players));
        }

        @Override
        public String type() {
            return TYPE_HELLO;
        }

        @Override
        public JsonValue toJson() {
            JsonValue players = JsonValue.array();
            for (PlayerIdentity player : this.players) {
                players.add(JsonValue.object()
                        .put("id", player.uuidString())
                        .put("name", player.name()));
            }
            return JsonValue.object()
                    .put("t", TYPE_HELLO)
                    .put("protocol", protocolVersion)
                    .put("mod", modVersion)
                    .put("server", serverName)
                    .put("at", at)
                    .put("players", players);
        }
    }

    /**
     * A backend activity report.
     *
     * <p>{@code join} / {@code quit} are informational: the proxy owns presence and only uses these
     * to confirm the sensor is alive. {@code activity} feeds the proxy-side AFK state machine.
     */
    record Event(String serverName,
                 String kind,
                 String source,
                 PlayerIdentity player,
                 long at) implements BridgeMessage {

        @Override
        public String type() {
            return TYPE_EVENT;
        }

        @Override
        public JsonValue toJson() {
            JsonValue body = JsonValue.object()
                    .put("t", TYPE_EVENT)
                    .put("server", serverName)
                    .put("kind", kind)
                    .put("at", at);
            if (source != null) {
                body.put("source", source);
            }
            if (player != null) {
                body.put("player", JsonValue.object()
                        .put("id", player.uuidString())
                        .put("name", player.name()));
            }
            return body;
        }
    }

    /**
     * Sent by the proxy to a backend to ask every sensor there to introduce itself.
     *
     * <p>A sensor only learns about the proxy when a player connects, so after a proxy restart it
     * would stay silent until the next join. The proxy probes the target backend instead of waiting,
     * which is what makes a proxy restart safe while players are already online.
     */
    record Probe(String proxyVersion, int protocolVersion) implements BridgeMessage {

        @Override
        public String type() {
            return TYPE_PROBE;
        }

        @Override
        public JsonValue toJson() {
            return JsonValue.object()
                    .put("t", TYPE_PROBE)
                    .put("protocol", protocolVersion)
                    .put("proxy", proxyVersion);
        }
    }

    /** The proxy's reply to {@link Hello}. Lets the backend surface bridge state in its own command. */
    record HelloAck(boolean accepting,
                    String targetServer,
                    String proxyVersion,
                    int protocolVersion) implements BridgeMessage {

        @Override
        public String type() {
            return TYPE_HELLO_ACK;
        }

        @Override
        public JsonValue toJson() {
            JsonValue body = JsonValue.object()
                    .put("t", TYPE_HELLO_ACK)
                    .put("protocol", protocolVersion)
                    .put("proxy", proxyVersion)
                    .put("accepting", accepting);
            if (targetServer != null && !targetServer.isEmpty()) {
                body.put("target", targetServer);
            }
            return body;
        }
    }
}
