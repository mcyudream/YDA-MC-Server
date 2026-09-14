package online.yudream.minecraft.bridge.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import online.yudream.minecraft.bridge.common.config.BridgeMode;
import online.yudream.minecraft.bridge.common.model.PlayerIdentity;
import online.yudream.minecraft.bridge.common.protocol.BridgeMessage;
import online.yudream.minecraft.bridge.common.protocol.BridgeProtocol;
import online.yudream.minecraft.bridge.common.protocol.ProtocolException;
import online.yudream.minecraft.bridge.fabric.config.FabricSettings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Minecraft-facing half of the bridge, in whichever mode the config selects.
 *
 * <p>It watches this server's players. Where that observation goes depends on the mode:
 *
 * <ul>
 *   <li><b>downstream</b> — two things travel to the proxy over {@code yudream:bridge}:
 *       <ul>
 *         <li><b>hello</b> — on every player join, on a heartbeat, and whenever the proxy probes. A
 *             hello is the only way the proxy can confirm that this backend actually runs the mod,
 *             because plugin messages need a player connection to travel over.</li>
 *         <li><b>activity</b> — chat, movement, interactions and commands, throttled per player.
 *             Activity never carries presence: the proxy decides who is online.</li>
 *       </ul></li>
 *   <li><b>standalone</b> — there is no proxy, so the same signals feed {@link FabricReporter},
 *       which owns presence, AFK state and every HTTP call to YuDream Admin.</li>
 * </ul>
 *
 * <p>Only the shared, mode-independent observation lives here: movement detection and the activity
 * throttle. Everything downstream of that is one call into either the channel or the reporter.
 */
public final class FabricSensor {

    private final FabricBridge bridge;
    private final Map<UUID, BlockPos> lastPositions = new HashMap<UUID, BlockPos>();
    private final Map<UUID, Long> lastActivityForwardedAt = new HashMap<UUID, Long>();

    private volatile long lastHeartbeatAt;
    private volatile long lastAfkTickAt;
    private volatile long lastSnapshotAt;
    /** Set until the first tick has run {@code startup.sync-online-on-enable} once. */
    private volatile boolean startupSyncPending = true;

    public FabricSensor(FabricBridge bridge) {
        this.bridge = bridge;
    }

    public void install() {
        if (bridge.settings().isDownstream()) {
            installProxyChannel();
        } else {
            FabricLog.LOGGER.info("YuDream bridge runs standalone: no proxy channel is registered, so"
                    + " vanilla clients are never sent a payload they cannot handle.");
        }

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) {
                onPlayerJoin(server, player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player == null) {
                return;
            }
            UUID uuid = player.getUUID();
            lastPositions.remove(uuid);
            lastActivityForwardedAt.remove(uuid);
            // A connection that never finished logging in still raises DISCONNECT. Reporting a quit
            // for somebody who never joined would be noise the proxy has to filter out.
            if (server.getPlayerList().getPlayer(uuid) == null) {
                return;
            }
            onPlayerQuit(player);
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, bound) -> {
            if (bridge.settings().isActivityChat()) {
                markActivity(sender, BridgeMessage.SOURCE_CHAT);
            }
        });
        ServerMessageEvents.COMMAND_MESSAGE.register((message, source, bound) -> {
            if (!bridge.settings().isActivityCommand()) {
                return;
            }
            ServerPlayer player = source.getPlayer();
            if (player != null) {
                markActivity(player, BridgeMessage.SOURCE_COMMAND);
            }
        });

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            markInteraction(player);
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            markInteraction(player);
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            markInteraction(player);
            return InteractionResult.PASS;
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) -> registerCommands(dispatcher));

        FabricLog.LOGGER.info("YuDream bridge installed in {} mode.", bridge.settings().mode().getId());
    }

    /** Registers the {@code yudream:bridge} payload, which only a proxy deployment uses. */
    private void installProxyChannel() {
        PayloadTypeRegistry.clientboundPlay().register(BridgePayload.TYPE, BridgePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BridgePayload.TYPE, BridgePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BridgePayload.TYPE, this::onPayload);
    }

    // ------------------------------------------------------------------ incoming

    private void onPayload(BridgePayload payload, ServerPlayNetworking.Context context) {
        BridgeMessage message;
        try {
            message = BridgeProtocol.decode(payload.data());
        } catch (ProtocolException e) {
            bridge.log().warn("Ignoring a malformed YuDream bridge message from the proxy: " + e.getMessage());
            return;
        }
        // Reading the player list and sending both belong on the server thread; execute() runs the
        // task inline when it is already there.
        MinecraftServer server = context.server();
        server.execute(() -> handle(message, context.player(), server));
    }

    private void handle(BridgeMessage message, ServerPlayer sender, MinecraftServer server) {
        if (message instanceof BridgeMessage.Probe) {
            sendHello(sender, server);
        } else if (message instanceof BridgeMessage.HelloAck ack) {
            bridge.onHelloAck(ack);
            bridge.log().debug("Proxy bridge replied: protocol=" + ack.protocolVersion()
                    + ", accepting=" + ack.accepting()
                    + ", target=" + (ack.targetServer().isEmpty() ? "<unset>" : ack.targetServer()));
        }
    }

    // ------------------------------------------------------------------ outgoing

    private void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
        FabricSettings settings = bridge.settings();
        if (!settings.isEnabled()) {
            return;
        }
        lastPositions.put(player.getUUID(), player.blockPosition());

        FabricReporter reporter = bridge.reporter();
        if (reporter != null) {
            reporter.join(identity(player), System.currentTimeMillis());
            return;
        }
        // Announce this backend immediately, then report the join itself.
        sendHello(player, server);
        lastHeartbeatAt = System.currentTimeMillis();
        forward(player, BridgeMessage.KIND_JOIN, null);
    }

    private void onPlayerQuit(ServerPlayer player) {
        FabricReporter reporter = bridge.reporter();
        if (reporter != null) {
            reporter.quit(identity(player), System.currentTimeMillis());
            return;
        }
        // Advisory only: the proxy owns presence and will already have reported the quit.
        forward(player, BridgeMessage.KIND_QUIT, null);
    }

    private void onServerStopping(MinecraftServer server) {
        FabricReporter reporter = bridge.reporter();
        if (reporter != null) {
            reporter.shutdown(identities(server.getPlayerList().getPlayers()));
        }
    }

    private void onEndTick(MinecraftServer server) {
        FabricSettings settings = bridge.settings();
        if (!settings.isEnabled()) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        long now = System.currentTimeMillis();

        FabricReporter reporter = bridge.reporter();
        if (reporter != null) {
            tickStandalone(reporter, players, now);
        } else {
            tickDownstream(server, players, now);
        }
    }

    /**
     * Standalone cadence: movement feeds the local AFK timer, and the roster is re-reported on an
     * interval.
     *
     * <p>This runs even with nobody online. An empty roster is a real report — it is how Admin learns
     * that the last player left or that a quit was missed while this server was unreachable — so it
     * must not be skipped just because the player list is empty.
     */
    private void tickStandalone(FabricReporter reporter, List<ServerPlayer> players, long now) {
        FabricSettings settings = bridge.settings();
        if (startupSyncPending) {
            startupSyncPending = false;
            // The first tick reports the roster exactly once and starts the cadence from there.
            // Without this, an unset lastSnapshotAt makes the cadence fire in the same tick as the
            // startup sync and two identical snapshots go out back to back.
            lastSnapshotAt = now;
            lastAfkTickAt = now;
            if (settings.bridge().isSyncOnlineOnEnable()) {
                reporter.syncOnline(identities(players), now);
            } else {
                reporter.reportSnapshot(identities(players), now);
            }
        }
        if (!players.isEmpty()) {
            trackMovement(players, settings, now);
        }
        long afkIntervalMs = Math.max(settings.bridge().getAfkCheckIntervalMs(), 1000L);
        if (now - lastAfkTickAt >= afkIntervalMs) {
            lastAfkTickAt = now;
            reporter.tickAfk(identities(players), now);
        }
        if (now - lastSnapshotAt >= settings.snapshotIntervalSeconds() * 1000L) {
            lastSnapshotAt = now;
            reporter.reportSnapshot(identities(players), now);
        }
    }

    /**
     * Downstream cadence: movement is forwarded to the proxy, which owns the AFK clock, plus the
     * heartbeat that keeps the proxy's sensor confirmation alive across a proxy restart.
     */
    private void tickDownstream(MinecraftServer server, List<ServerPlayer> players, long now) {
        if (players.isEmpty()) {
            return;
        }
        FabricSettings settings = bridge.settings();
        trackMovement(players, settings, now);
        if (now - lastHeartbeatAt >= settings.heartbeatSeconds() * 1000L) {
            lastHeartbeatAt = now;
            sendHello(players.get(0), server);
        }
    }

    /** Emits one activity signal per player that moved far enough since the previous tick. */
    private void trackMovement(List<ServerPlayer> players, FabricSettings settings, long now) {
        if (!settings.isActivityMove()) {
            return;
        }
        for (ServerPlayer player : players) {
            BlockPos current = player.blockPosition();
            BlockPos previous = lastPositions.put(player.getUUID(), current);
            if (previous != null && movedEnough(previous, current, settings.moveMinBlocks())) {
                emitActivity(player, BridgeMessage.SOURCE_MOVE, now);
            }
        }
    }

    private void sendHello(ServerPlayer carrier, MinecraftServer server) {
        List<PlayerIdentity> players = new ArrayList<PlayerIdentity>();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            players.add(identity(online));
        }
        // The server name is deliberately empty: the proxy names this backend from the connection it
        // arrived on, which cannot be spoofed by a stale config value.
        send(carrier, new BridgeMessage.Hello("", YudreamFabricMod.VERSION,
                BridgeProtocol.PROTOCOL_VERSION, players, System.currentTimeMillis()));
    }

    private void markInteraction(Player player) {
        if (!bridge.settings().isActivityInteract()) {
            return;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            markActivity(serverPlayer, BridgeMessage.SOURCE_INTERACT);
        }
    }

    /**
     * Routes one activity signal, at most once per player per
     * {@code activity.min-interval-seconds}. Both modes only need this to reset an AFK timer that is
     * measured in minutes, so sending every chat line or every step would be pure packet spam.
     */
    private void markActivity(ServerPlayer player, String source) {
        FabricSettings settings = bridge.settings();
        if (!settings.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastActivityForwardedAt.get(player.getUUID());
        if (previous != null && now - previous.longValue() < settings.activityMinIntervalMs()) {
            return;
        }
        lastActivityForwardedAt.put(player.getUUID(), Long.valueOf(now));
        emitActivity(player, source, now);
    }

    private void emitActivity(ServerPlayer player, String source, long now) {
        FabricReporter reporter = bridge.reporter();
        if (reporter != null) {
            reporter.activity(identity(player), now);
            return;
        }
        send(player, new BridgeMessage.Event("", BridgeMessage.KIND_ACTIVITY, source, identity(player), now));
    }

    private void forward(ServerPlayer player, String kind, String source) {
        if (!bridge.settings().isEnabled()) {
            return;
        }
        send(player, new BridgeMessage.Event("", kind, source, identity(player), System.currentTimeMillis()));
    }

    private void send(ServerPlayer player, BridgeMessage message) {
        try {
            ServerPlayNetworking.send(player, new BridgePayload(BridgeProtocol.encode(message)));
        } catch (Throwable t) {
            // A player can disconnect between the event and this call, and the proxy may not have
            // registered the channel yet. Neither is worth failing a tick over.
            bridge.log().debug("Could not forward a " + message.type() + " message to the proxy: " + t);
        }
    }

    private static boolean movedEnough(BlockPos from, BlockPos to, double minBlocks) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        return dx * dx + dy * dy + dz * dz >= minBlocks * minBlocks;
    }

    private static List<PlayerIdentity> identities(Collection<ServerPlayer> players) {
        List<PlayerIdentity> result = new ArrayList<PlayerIdentity>();
        for (ServerPlayer player : players) {
            result.add(identity(player));
        }
        return result;
    }

    private static PlayerIdentity identity(ServerPlayer player) {
        return new PlayerIdentity(player.getUUID(), player.getName().getString());
    }

    // ------------------------------------------------------------------ command

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("yudreammc")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    source.sendSuccess(() -> Component.literal(bridge.describeStatus()), false);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(context -> {
                    bridge.reload();
                    CommandSourceStack source = context.getSource();
                    MinecraftServer server = source.getServer();
                    FabricReporter reporter = bridge.reporter();
                    if (reporter != null && bridge.settings().bridge().isSyncOnlineOnEnable()) {
                        // Re-announce whoever is online: a reload can change who is responsible for
                        // reporting, and Admin should not have to wait for the next join.
                        reporter.syncOnline(identities(server.getPlayerList().getPlayers()),
                                System.currentTimeMillis());
                    }
                    source.sendSuccess(() -> Component.literal(
                            "YuDream bridge config reloaded; mode=" + bridge.settings().mode().getId() + "."), false);
                    return 1;
                }))
                .then(Commands.literal("mode")
                        .then(Commands.argument("value", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    builder.suggest("standalone");
                                    builder.suggest("downstream");
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    String raw = StringArgumentType.getString(context, "value");
                                    BridgeMode requested = BridgeMode.fromId(raw, null);
                                    CommandSourceStack source = context.getSource();
                                    if (requested == null) {
                                        source.sendFailure(Component.literal(
                                                "Unknown mode '" + raw + "'. Use standalone or downstream."));
                                        return 0;
                                    }
                                    if (!bridge.setMode(requested)) {
                                        source.sendFailure(Component.literal(
                                                "Could not write mode=" + requested.getId() + " to the config file."));
                                        return 0;
                                    }
                                    if (requested.isDownstream()) {
                                        // The new sensor has not greeted the proxy yet, and a hello needs a
                                        // player to travel on; the next join or probe covers it.
                                        sendHelloToAnyPlayer(source.getServer());
                                    } else {
                                        FabricReporter reporter = bridge.reporter();
                                        if (reporter != null
                                                && bridge.settings().bridge().isSyncOnlineOnEnable()) {
                                            reporter.syncOnline(
                                                    identities(source.getServer().getPlayerList().getPlayers()),
                                                    System.currentTimeMillis());
                                        }
                                    }
                                    source.sendSuccess(() -> Component.literal(
                                            "YuDream bridge mode is now " + requested.getId() + "."), true);
                                    return 1;
                                }))));
    }

    private void sendHelloToAnyPlayer(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (!players.isEmpty()) {
            sendHello(players.get(0), server);
        }
    }
}
