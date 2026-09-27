package online.yudream.minecraft.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import online.yudream.minecraft.bridge.core.log.LogSink;
import online.yudream.minecraft.bridge.core.model.PlayerEventType;
import online.yudream.minecraft.bridge.core.model.PlayerIdentity;
import online.yudream.minecraft.mod.common.YudreamBridgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Mod(YudreamForgeMod.MOD_ID)
public final class YudreamForgeMod {

    public static final String MOD_ID = "yudream_minecraft_server";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final YudreamBridgeService service;
    private final Map<UUID, BlockPos> lastPositions = new HashMap<>();
    private MinecraftServer server;

    public YudreamForgeMod() {
        service = new YudreamBridgeService(
                FMLPaths.CONFIGDIR.get().resolve("yudream-minecraft-server.properties"),
                new Slf4jLogSink(LOGGER)
        );
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        service.start();
        // 群服互联入站：群消息异步拉取后切回主线程广播给全体玩家
        service.setInboundBroadcast(line -> server.execute(() ->
                server.getPlayerList().broadcastSystemMessage(Component.literal(line), false)));
        if (service.shouldSyncOnlineOnStart()) {
            service.syncOnline(onlinePlayers());
        }
        LOGGER.info("YuDream Minecraft server bridge started for Forge 1.20.1.");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        service.stop(onlinePlayers());
        lastPositions.clear();
        server = null;
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            lastPositions.put(player.getUUID(), player.blockPosition());
            service.playerJoined(identity(player));
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            service.playerQuit(identity(player));
            lastPositions.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (server == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BlockPos current = player.blockPosition();
            BlockPos previous = lastPositions.put(player.getUUID(), current);
            if (previous != null && !sameBlock(previous, current)) {
                service.markActive(identity(player));
            }
        }
        service.tick(onlinePlayers());
        service.tickInbound();
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        service.markActive(identity(event.getPlayer()));
        // 群服互联：聊天原文上报（Admin 侧配置决定是否转发）
        service.reportGameEvent(PlayerEventType.CHAT, identity(event.getPlayer()), event.getMessage().getString());
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            service.reportGameEvent(PlayerEventType.DEATH, identity(player),
                    player.getCombatTracker().getDeathMessage().getString());
        }
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        DisplayInfo display = event.getAdvancement().getDisplay();
        if (display == null || !display.shouldAnnounceChat()) {
            return;
        }
        service.reportGameEvent(PlayerEventType.ADVANCEMENT, identity(player), display.getTitle().getString());
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        try {
            ServerPlayer player = event.getParseResults().getContext().getSource().getPlayerOrException();
            service.markActive(identity(player));
        } catch (CommandSyntaxException ignored) {
            // Console and command blocks are not player activity.
        }
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            service.markActive(identity(player));
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("yudreammc")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload").executes(context -> {
                    service.reload();
                    context.getSource().sendSuccess(() -> Component.literal("YuDream config reloaded."), false);
                    return 1;
                }))
                .then(Commands.literal("queue").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("YuDream pending reports: " + service.queueSize()), false);
                    return service.queueSize();
                }))
                .then(Commands.literal("sync").executes(context -> {
                    service.syncOnline(onlinePlayers());
                    context.getSource().sendSuccess(() -> Component.literal("Queued join reports for current online players."), false);
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    service.statusAsync(message -> source.getServer().execute(
                            () -> source.sendSuccess(() -> Component.literal(message), false)
                    ));
                    source.sendSuccess(() -> Component.literal("Checking YuDream remote players..."), false);
                    return 1;
                })));
    }

    private Collection<PlayerIdentity> onlinePlayers() {
        if (server == null) {
            return java.util.List.of();
        }
        return server.getPlayerList().getPlayers().stream()
                .map(this::identity)
                .collect(Collectors.toList());
    }

    private PlayerIdentity identity(ServerPlayer player) {
        return new PlayerIdentity(player.getUUID(), player.getGameProfile().getName());
    }

    private static boolean sameBlock(BlockPos first, BlockPos second) {
        return first.getX() == second.getX() && first.getY() == second.getY() && first.getZ() == second.getZ();
    }

    private static final class Slf4jLogSink implements LogSink {
        private final Logger logger;

        private Slf4jLogSink(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void info(String message) {
            logger.info(message);
        }

        @Override
        public void warn(String message) {
            logger.warn(message);
        }

        @Override
        public void warn(String message, Throwable throwable) {
            logger.warn(message, throwable);
        }
    }
}
