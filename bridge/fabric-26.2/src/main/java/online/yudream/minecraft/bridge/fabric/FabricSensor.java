package online.yudream.minecraft.bridge.fabric;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
import online.yudream.minecraft.bridge.common.model.PlayerIdentity;
import online.yudream.minecraft.bridge.common.protocol.BridgeMessage;
import online.yudream.minecraft.bridge.common.protocol.BridgeProtocol;
import online.yudream.minecraft.bridge.common.protocol.ProtocolException;
import online.yudream.minecraft.bridge.fabric.config.FabricSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Minecraft-facing half of the sensor.
 *
 * <p>It watches this backend's players and forwards two things to the proxy:
 *
 * <ul>
 *   <li><b>hello</b> — on every player join, on a heartbeat, and whenever the proxy probes. A hello
 *       is the only way the proxy can confirm that this backend actually runs the mod, because
 *       plugin messages need a player connection to travel over.</li>
 *   <li><b>activity</b> — chat, movement, interactions and commands, throttled per player. Activity
 *       never carries presence: the proxy decides who is online.</li>
 * </ul>
 *
 * <p>Nothing here contacts YuDream Admin and there are no credentials on this server.
 */
public final class FabricSensor {

    private final FabricBridge bridge;
    private final Map<UUID, BlockPos> lastPositions = new HashMap<UUID, BlockPos>();
    private final Map<UUID, Long> lastActivityForwardedAt = new HashMap<UUID, Long>();

    private volatile long lastHeartbeatAt;

    public FabricSensor(FabricBridge bridge) {
        this.bridge = bridge;
    }

    public void install() {
        PayloadTypeRegistry.clientboundPlay().register(BridgePayload.TYPE, BridgePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BridgePayload.TYPE, BridgePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(BridgePayload.TYPE, this::onPayload);

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
            // Advisory only: the proxy owns presence and will already have reported the quit.
            forward(player, BridgeMessage.KIND_QUIT, null);
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, bound) -> {
            if (bridge.settings().isActivityChat()) {
                forwardActivity(sender, BridgeMessage.SOURCE_CHAT);
            }
        });
        ServerMessageEvents.COMMAND_MESSAGE.register((message, source, bound) -> {
            if (!bridge.settings().isActivityCommand()) {
                return;
            }
            ServerPlayer player = source.getPlayer();
            if (player != null) {
                forwardActivity(player, BridgeMessage.SOURCE_COMMAND);
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

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) -> registerCommands(dispatcher));

        FabricLog.LOGGER.info("YuDream sensor installed; player activity is forwarded to the Velocity proxy on {}.",
                BridgeProtocol.CHANNEL);
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
        // Announce this backend immediately, then report the join itself.
        sendHello(player, server);
        lastHeartbeatAt = System.currentTimeMillis();
        forward(player, BridgeMessage.KIND_JOIN, null);
    }

    private void onEndTick(MinecraftServer server) {
        FabricSettings settings = bridge.settings();
        if (!settings.isEnabled()) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        for (ServerPlayer player : players) {
            BlockPos current = player.blockPosition();
            BlockPos previous = lastPositions.put(player.getUUID(), current);
            if (previous != null && settings.isActivityMove() && movedEnough(previous, current, settings.moveMinBlocks())) {
                forwardActivity(player, BridgeMessage.SOURCE_MOVE);
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatAt >= settings.heartbeatSeconds() * 1000L) {
            lastHeartbeatAt = now;
            sendHello(players.get(0), server);
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
            forwardActivity(serverPlayer, BridgeMessage.SOURCE_INTERACT);
        }
    }

    /**
     * Forwards one activity signal, at most once per player per
     * {@code activity.min-interval-seconds}. The proxy only needs this to reset an AFK timer that is
     * measured in minutes, so sending every chat line or every step would be pure packet spam.
     */
    private void forwardActivity(ServerPlayer player, String source) {
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

    private static PlayerIdentity identity(ServerPlayer player) {
        return new PlayerIdentity(player.getUUID(), player.getName().getString());
    }

    // ------------------------------------------------------------------ command

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("yudreammc")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    source.sendSuccess(() -> Component.literal(bridge.describeProxyLink()), false);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(context -> {
                    bridge.reload();
                    context.getSource().sendSuccess(
                            () -> Component.literal("YuDream sensor config reloaded."), false);
                    return 1;
                })));
    }
}
