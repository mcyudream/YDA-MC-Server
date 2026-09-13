package online.yudream.minecraft.bukkit.bridge;

import online.yudream.minecraft.bukkit.YudreamMinecraftPlugin;
import online.yudream.minecraft.bukkit.config.DownstreamOptions;
import online.yudream.minecraft.bukkit.report.PlayerEventPayload;
import online.yudream.minecraft.bukkit.util.PlayerSnapshots;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Downstream mode: this server becomes a sensor for a proxy network.
 *
 * <p>It forwards player activity to the Velocity bridge over the {@code yudream:bridge} plugin
 * message channel and uploads nothing to YuDream Admin itself. The counterpart on the proxy owns
 * presence, AFK state and every HTTP call, exactly as it does for the Fabric sensor.
 *
 * <p>Two things travel outwards: a <b>hello</b> on every player join, on a heartbeat and whenever the
 * proxy probes — a hello is the only way the proxy can confirm this backend is instrumented, because
 * plugin messages need a player connection to travel over — and <b>activity</b> signals, throttled per
 * player, which never carry presence.
 */
public final class DownstreamSensor implements PluginMessageListener {

    private final YudreamMinecraftPlugin plugin;
    private final DownstreamOptions options;

    private final Map<UUID, Long> lastActivityAt = new HashMap<UUID, Long>();

    private volatile long lastAckAt;
    private volatile int proxyProtocolVersion = -1;
    private volatile String proxyVersion = "";
    private volatile String proxyTarget = "";
    private volatile boolean proxyAccepting;

    private int heartbeatTaskId = -1;
    private boolean channelsRegistered;

    public DownstreamSensor(YudreamMinecraftPlugin plugin, DownstreamOptions options) {
        this.plugin = plugin;
        this.options = options;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        if (!channelsRegistered) {
            Messenger messenger = plugin.getServer().getMessenger();
            messenger.registerOutgoingPluginChannel(plugin, BridgeProtocol.CHANNEL);
            messenger.registerIncomingPluginChannel(plugin, BridgeProtocol.CHANNEL, this);
            channelsRegistered = true;
        }
        long periodTicks = Math.max(options.getHeartbeatSeconds(), 1L) * 20L;
        if (heartbeatTaskId < 0) {
            heartbeatTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                    plugin, new Runnable() {
                        @Override
                        public void run() {
                            sendHello(null);
                        }
                    }, periodTicks, periodTicks);
        }
        plugin.getLogger().info("Downstream mode active. Player activity is forwarded to the proxy on "
                + BridgeProtocol.CHANNEL + "; this server does not contact YuDream Admin directly"
                + (options.isFallbackToApi() ? " unless the proxy stays silent." : "."));
    }

    public void stop() {
        if (heartbeatTaskId >= 0) {
            plugin.getServer().getScheduler().cancelTask(heartbeatTaskId);
            heartbeatTaskId = -1;
        }
        if (channelsRegistered) {
            Messenger messenger = plugin.getServer().getMessenger();
            messenger.unregisterOutgoingPluginChannel(plugin, BridgeProtocol.CHANNEL);
            messenger.unregisterIncomingPluginChannel(plugin, BridgeProtocol.CHANNEL, this);
            channelsRegistered = false;
        }
        lastActivityAt.clear();
    }

    // ------------------------------------------------------------------ outgoing

    /** Announces this backend and the players currently on it, then reports the join. */
    public void onPlayerJoin(Player player) {
        sendHello(player);
        sendEvent(player, BridgeProtocol.KIND_JOIN, null);
    }

    /**
     * Reports the quit.
     *
     * <p>Advisory only: the proxy derives quit from its own {@code DisconnectEvent} and will already
     * have reported it. This exists so the proxy can repair a gap it missed.
     */
    public void onPlayerQuit(Player player) {
        lastActivityAt.remove(player.getUniqueId());
        sendEvent(player, BridgeProtocol.KIND_QUIT, null);
    }

    /**
     * Forwards one activity signal, at most once per player per
     * {@code downstream.activity-min-interval-seconds}. The proxy only uses these to reset an AFK
     * timer measured in minutes, so forwarding every chat line or every step would be packet spam.
     */
    public void onActivity(Player player, String source) {
        long now = System.currentTimeMillis();
        Long previous = lastActivityAt.get(player.getUniqueId());
        if (previous != null && now - previous.longValue() < options.getActivityMinIntervalMs()) {
            return;
        }
        lastActivityAt.put(player.getUniqueId(), Long.valueOf(now));
        sendEvent(player, BridgeProtocol.KIND_ACTIVITY, source);
    }

    /** Sends a hello right now, used by {@code /yudreammc sync}. */
    public void requestHello() {
        sendHello(null);
    }

    private void sendHello(Player carrier) {
        Player chosen = carrier != null && carrier.isOnline() ? carrier : firstOnlinePlayer();
        if (chosen == null) {
            // A plugin message needs a player connection; with nobody online there is nothing to
            // send it over. The proxy probes this backend instead, and the reply covers the gap.
            return;
        }
        Collection<? extends Player> online = plugin.getServer().getOnlinePlayers();
        List<PlayerEventPayload> players = new ArrayList<PlayerEventPayload>(online.size());
        for (Player player : online) {
            players.add(PlayerSnapshots.from(player));
        }
        send(chosen, BridgeProtocol.encodeHello(plugin.getDescription().getVersion(), players, System.currentTimeMillis()));
    }

    private void sendEvent(Player player, String kind, String source) {
        if (player == null || !player.isOnline()) {
            return;
        }
        send(player, BridgeProtocol.encodeEvent(kind, source, PlayerSnapshots.from(player), System.currentTimeMillis()));
    }

    private void send(Player player, byte[] data) {
        try {
            player.sendPluginMessage(plugin, BridgeProtocol.CHANNEL, data);
        } catch (Throwable t) {
            // A player can disconnect between the event and this call. Losing one activity signal or
            // an advisory quit is not worth failing the caller over.
            plugin.getLogger().log(Level.FINE, "Could not forward a YuDream bridge message", t);
        }
    }

    private Player firstOnlinePlayer() {
        Collection<? extends Player> online = plugin.getServer().getOnlinePlayers();
        for (Player player : online) {
            return player;
        }
        return null;
    }

    // ------------------------------------------------------------------ incoming

    @Override
    public void onPluginMessageReceived(String channel, final Player player, byte[] message) {
        if (!BridgeProtocol.CHANNEL.equals(channel)) {
            return;
        }
        final BridgeProtocol.Incoming incoming = BridgeProtocol.decode(message);
        if (incoming == null) {
            return;
        }
        // Reply from the next tick: this callback already runs on the server thread, and the reply
        // touches the player list and the network, so it stays on the main thread either way.
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                if (incoming.isProbe()) {
                    sendHello(player);
                } else if (incoming.isHelloAck()) {
                    onHelloAck(incoming);
                }
            }
        });
    }

    private void onHelloAck(BridgeProtocol.Incoming incoming) {
        boolean first = lastAckAt == 0L;
        lastAckAt = System.currentTimeMillis();
        proxyProtocolVersion = incoming.getProtocolVersion();
        proxyVersion = incoming.getProxyVersion();
        proxyTarget = incoming.getTargetServer();
        proxyAccepting = incoming.isAccepting();
        if (first) {
            if (proxyProtocolVersion != BridgeProtocol.PROTOCOL_VERSION) {
                plugin.getLogger().warning("The proxy speaks YuDream bridge protocol " + proxyProtocolVersion
                        + " but this plugin speaks " + BridgeProtocol.PROTOCOL_VERSION
                        + ". Update the older side; no reports will be taken from this server.");
            } else {
                plugin.getLogger().info("Proxy bridge acknowledged: proxy " + proxyVersion
                        + ", reporting downstream server "
                        + (proxyTarget.isEmpty() ? "<unset>" : proxyTarget) + ".");
            }
        }
    }

    // ------------------------------------------------------------------ status

    /** Whether the proxy has acknowledged recently enough that it is clearly alive. */
    public boolean isProxyReachable() {
        long ack = lastAckAt;
        return ack > 0L && System.currentTimeMillis() - ack <= options.getAckTimeoutMs();
    }

    public long getLastAckAt() {
        return lastAckAt;
    }

    /** One line for {@code /yudreammc status}. */
    public String describeLink() {
        long ack = lastAckAt;
        if (ack <= 0L) {
            return "no reply from the proxy yet (channel " + BridgeProtocol.CHANNEL + ")";
        }
        return "proxy " + (proxyVersion.isEmpty() ? "?" : proxyVersion)
                + ", protocol " + proxyProtocolVersion
                + ", accepting " + proxyAccepting
                + ", reporting downstream " + (proxyTarget.isEmpty() ? "<unset>" : proxyTarget)
                + ", lastReplyMsAgo " + (System.currentTimeMillis() - ack);
    }
}
