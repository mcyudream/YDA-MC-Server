package online.yudream.minecraft.bridge.core.protocol;

import online.yudream.minecraft.bridge.core.json.JsonValue;
import online.yudream.minecraft.bridge.core.model.PlayerIdentity;

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
public interface BridgeMessage {

    String TYPE_HELLO = "hello";
    String TYPE_HELLO_ACK = "hello_ack";
    String TYPE_PROBE = "probe";
    String TYPE_EVENT = "event";
    /** Proxy → sensor: a group-chat message that should be broadcast into the backend's game chat. */
    String TYPE_GROUP_MSG = "group_msg";

    String KIND_JOIN = "join";
    String KIND_QUIT = "quit";
    String KIND_ACTIVITY = "activity";
    /** Game events the proxy reports to YuDream Admin for the group-server bridge. */
    String KIND_CHAT = "chat";
    String KIND_DEATH = "death";
    String KIND_ADVANCEMENT = "advancement";

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
    final class Hello implements BridgeMessage {

        private final String serverName;
        private final String modVersion;
        private final int protocolVersion;
        private final List<PlayerIdentity> players;
        private final long at;

        public Hello(String serverName,
                     String modVersion,
                     int protocolVersion,
                     List<PlayerIdentity> players,
                     long at) {
            this.serverName = serverName;
            this.modVersion = modVersion;
            this.protocolVersion = protocolVersion;
            this.players = players == null
                    ? Collections.<PlayerIdentity>emptyList()
                    : Collections.unmodifiableList(new ArrayList<PlayerIdentity>(players));
            this.at = at;
        }

        public String serverName() {
            return serverName;
        }

        public String modVersion() {
            return modVersion;
        }

        public int protocolVersion() {
            return protocolVersion;
        }

        public List<PlayerIdentity> players() {
            return players;
        }

        public long at() {
            return at;
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

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Hello)) {
                return false;
            }
            Hello other = (Hello) o;
            return protocolVersion == other.protocolVersion
                    && at == other.at
                    && (serverName == null ? other.serverName == null : serverName.equals(other.serverName))
                    && (modVersion == null ? other.modVersion == null : modVersion.equals(other.modVersion))
                    && players.equals(other.players);
        }

        @Override
        public int hashCode() {
            int result = serverName == null ? 0 : serverName.hashCode();
            result = 31 * result + (modVersion == null ? 0 : modVersion.hashCode());
            result = 31 * result + protocolVersion;
            result = 31 * result + players.hashCode();
            result = 31 * result + (int) (at ^ (at >>> 32));
            return result;
        }

        @Override
        public String toString() {
            return "Hello[serverName=" + serverName + ", modVersion=" + modVersion
                    + ", protocolVersion=" + protocolVersion + ", players=" + players + ", at=" + at + "]";
        }
    }

    /**
     * A backend activity, presence or game-event report.
     *
     * <p>{@code join} / {@code quit} are informational: the proxy owns presence and only uses these
     * to confirm the sensor is alive. {@code activity} feeds the proxy-side AFK state machine.
     * {@code chat} / {@code death} / {@code advancement} carry a {@code content} text the proxy
     * reports to YuDream Admin for the group-server bridge.
     */
    final class Event implements BridgeMessage {

        private final String serverName;
        private final String kind;
        private final String source;
        private final PlayerIdentity player;
        private final long at;
        private final String content;

        public Event(String serverName, String kind, String source, PlayerIdentity player, long at, String content) {
            this.serverName = serverName;
            this.kind = kind;
            this.source = source;
            this.player = player;
            this.at = at;
            this.content = content;
        }

        public Event(String serverName, String kind, String source, PlayerIdentity player, long at) {
            this(serverName, kind, source, player, at, null);
        }

        public String serverName() {
            return serverName;
        }

        public String kind() {
            return kind;
        }

        public String source() {
            return source;
        }

        public PlayerIdentity player() {
            return player;
        }

        public long at() {
            return at;
        }

        public String content() {
            return content;
        }

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
            if (content != null && !content.isEmpty()) {
                body.put("content", content);
            }
            if (player != null) {
                body.put("player", JsonValue.object()
                        .put("id", player.uuidString())
                        .put("name", player.name()));
            }
            return body;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Event)) {
                return false;
            }
            Event other = (Event) o;
            return at == other.at
                    && (serverName == null ? other.serverName == null : serverName.equals(other.serverName))
                    && (kind == null ? other.kind == null : kind.equals(other.kind))
                    && (source == null ? other.source == null : source.equals(other.source))
                    && (player == null ? other.player == null : player.equals(other.player))
                    && (content == null ? other.content == null : content.equals(other.content));
        }

        @Override
        public int hashCode() {
            int result = serverName == null ? 0 : serverName.hashCode();
            result = 31 * result + (kind == null ? 0 : kind.hashCode());
            result = 31 * result + (source == null ? 0 : source.hashCode());
            result = 31 * result + (player == null ? 0 : player.hashCode());
            result = 31 * result + (int) (at ^ (at >>> 32));
            result = 31 * result + (content == null ? 0 : content.hashCode());
            return result;
        }

        @Override
        public String toString() {
            return "Event[serverName=" + serverName + ", kind=" + kind + ", source=" + source
                    + ", player=" + player + ", at=" + at + ", content=" + content + "]";
        }
    }

    /**
     * Sent by the proxy to a backend: one group-chat message to broadcast into the game chat.
     *
     * <p>{@code sender} is the platform-side sender identity (a QQ id, not a player name).
     */
    final class GroupMessage implements BridgeMessage {

        private final String sender;
        private final String content;
        private final long at;

        public GroupMessage(String sender, String content, long at) {
            this.sender = sender;
            this.content = content;
            this.at = at;
        }

        public String sender() {
            return sender;
        }

        public String content() {
            return content;
        }

        public long at() {
            return at;
        }

        @Override
        public String type() {
            return TYPE_GROUP_MSG;
        }

        @Override
        public JsonValue toJson() {
            return JsonValue.object()
                    .put("t", TYPE_GROUP_MSG)
                    .put("sender", sender)
                    .put("content", content)
                    .put("at", at);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof GroupMessage)) {
                return false;
            }
            GroupMessage other = (GroupMessage) o;
            return at == other.at
                    && (sender == null ? other.sender == null : sender.equals(other.sender))
                    && (content == null ? other.content == null : content.equals(other.content));
        }

        @Override
        public int hashCode() {
            int result = sender == null ? 0 : sender.hashCode();
            result = 31 * result + (content == null ? 0 : content.hashCode());
            result = 31 * result + (int) (at ^ (at >>> 32));
            return result;
        }

        @Override
        public String toString() {
            return "GroupMessage[sender=" + sender + ", content=" + content + ", at=" + at + "]";
        }
    }

    /**
     * Sent by the proxy to a backend to ask every sensor there to introduce itself.
     *
     * <p>A sensor only learns about the proxy when a player connects, so after a proxy restart it
     * would stay silent until the next join. The proxy probes the target backend instead of waiting,
     * which is what makes a proxy restart safe while players are already online.
     */
    final class Probe implements BridgeMessage {

        private final String proxyVersion;
        private final int protocolVersion;

        public Probe(String proxyVersion, int protocolVersion) {
            this.proxyVersion = proxyVersion;
            this.protocolVersion = protocolVersion;
        }

        public String proxyVersion() {
            return proxyVersion;
        }

        public int protocolVersion() {
            return protocolVersion;
        }

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

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Probe)) {
                return false;
            }
            Probe other = (Probe) o;
            return protocolVersion == other.protocolVersion
                    && (proxyVersion == null ? other.proxyVersion == null : proxyVersion.equals(other.proxyVersion));
        }

        @Override
        public int hashCode() {
            int result = proxyVersion == null ? 0 : proxyVersion.hashCode();
            result = 31 * result + protocolVersion;
            return result;
        }

        @Override
        public String toString() {
            return "Probe[proxyVersion=" + proxyVersion + ", protocolVersion=" + protocolVersion + "]";
        }
    }

    /** The proxy's reply to {@link Hello}. Lets the backend surface bridge state in its own command. */
    final class HelloAck implements BridgeMessage {

        private final boolean accepting;
        private final String targetServer;
        private final String proxyVersion;
        private final int protocolVersion;

        public HelloAck(boolean accepting,
                        String targetServer,
                        String proxyVersion,
                        int protocolVersion) {
            this.accepting = accepting;
            this.targetServer = targetServer;
            this.proxyVersion = proxyVersion;
            this.protocolVersion = protocolVersion;
        }

        public boolean accepting() {
            return accepting;
        }

        public String targetServer() {
            return targetServer;
        }

        public String proxyVersion() {
            return proxyVersion;
        }

        public int protocolVersion() {
            return protocolVersion;
        }

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

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof HelloAck)) {
                return false;
            }
            HelloAck other = (HelloAck) o;
            return accepting == other.accepting
                    && protocolVersion == other.protocolVersion
                    && (targetServer == null ? other.targetServer == null : targetServer.equals(other.targetServer))
                    && (proxyVersion == null ? other.proxyVersion == null : proxyVersion.equals(other.proxyVersion));
        }

        @Override
        public int hashCode() {
            int result = accepting ? 1 : 0;
            result = 31 * result + (targetServer == null ? 0 : targetServer.hashCode());
            result = 31 * result + (proxyVersion == null ? 0 : proxyVersion.hashCode());
            result = 31 * result + protocolVersion;
            return result;
        }

        @Override
        public String toString() {
            return "HelloAck[accepting=" + accepting + ", targetServer=" + targetServer
                    + ", proxyVersion=" + proxyVersion + ", protocolVersion=" + protocolVersion + "]";
        }
    }
}
