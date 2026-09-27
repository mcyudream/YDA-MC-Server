package online.yudream.minecraft.mod.common;

import online.yudream.minecraft.bridge.core.json.JsonSyntaxException;
import online.yudream.minecraft.bridge.core.json.JsonValue;

import online.yudream.minecraft.bridge.core.http.InboundChatSse;
import online.yudream.minecraft.bridge.core.http.HttpResult;
import online.yudream.minecraft.bridge.core.http.YudreamApiClient;
import online.yudream.minecraft.bridge.core.log.LogSink;
import online.yudream.minecraft.bridge.core.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.core.model.PlayerEventType;
import online.yudream.minecraft.bridge.core.model.PlayerIdentity;
import online.yudream.minecraft.bridge.core.summary.PlayerSummary;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class YudreamBridgeService {

    private static final long SNAPSHOT_INTERVAL_MS = 60_000L;

    private final Path configPath;
    private final LogSink logger;
    private final Map<UUID, PlayerActivityState> states = new ConcurrentHashMap<>();
    private BridgeConfig config;
    private YudreamApiClient apiClient;
    private ReportQueue reportQueue;
    /** 群服互联入站广播回调：由平台 mod 设置，负责把文本切回服务器主线程。 */
    private volatile java.util.function.Consumer<String> inboundBroadcast;
    private volatile long inboundCursor;
    /** SSE 长连接进行中标记：防止 tick 重入时出现双流重复投递。 */
    private final java.util.concurrent.atomic.AtomicBoolean inboundStreaming =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private long lastInboundPollAt;
    private long lastAfkCheckAt;
    private long lastSnapshotAt;

    public YudreamBridgeService(Path configPath, LogSink logger) {
        this.configPath = configPath;
        this.logger = logger;
    }

    public synchronized void start() {
        reload();
    }

    public synchronized void reload() {
        if (reportQueue != null) {
            reportQueue.shutdown(currentFlushTimeout());
        }
        config = BridgeConfigLoader.load(configPath, logger);
        apiClient = new YudreamApiClient(config.toSettings(), "YudreamMinecraftServerMod/1.0");
        reportQueue = new ReportQueue(logger, apiClient, config);
        lastAfkCheckAt = 0L;
        lastSnapshotAt = 0L;
        if (!config.isConfigured()) {
            logger.warn("YuDream bridge is not fully configured. Edit " + configPath + " and set base-url, server-id and api-key.");
        }
        if (!config.isEnabled()) {
            logger.warn("YuDream bridge is disabled by config.");
        }
    }

    public synchronized void stop(Collection<PlayerIdentity> onlinePlayers) {
        if (canReport()) {
            reportQueue.submitSnapshot(Collections.<PlayerEventPayload>emptyList(), System.currentTimeMillis());
        }
        if (config != null && config.isReportQuitOnStop() && reportQueue != null) {
            for (PlayerIdentity player : onlinePlayers) {
                reportQueue.submit(PlayerEventType.QUIT, player.payload(System.currentTimeMillis()));
            }
        }
        states.clear();
        if (reportQueue != null) {
            reportQueue.shutdown(currentFlushTimeout());
            reportQueue = null;
        }
    }

    public void syncOnline(Collection<PlayerIdentity> onlinePlayers) {
        if (!canReport()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (PlayerIdentity player : onlinePlayers) {
            states.put(player.uuid(), new PlayerActivityState(now, false));
            reportQueue.submit(PlayerEventType.JOIN, player.payload(now));
        }
        reportSnapshot(onlinePlayers, now);
    }

    public void playerJoined(PlayerIdentity player) {
        if (!canReport()) {
            return;
        }
        long now = System.currentTimeMillis();
        states.put(player.uuid(), new PlayerActivityState(now, false));
        reportQueue.submit(PlayerEventType.JOIN, player.payload(now));
    }

    public void playerQuit(PlayerIdentity player) {
        if (!canReport()) {
            return;
        }
        reportQueue.submit(PlayerEventType.QUIT, player.payload(System.currentTimeMillis()));
        states.remove(player.uuid());
    }

    // ---------------------------------------------------------------- 群服互联（游戏 → 群）

    /**
     * 群服互联：上报聊天、死亡或成就事件。转发与否由 Admin 管理端逐服务器配置，
     * 本地 {@code chat-bridge.report-events} 只决定本机是否上报。
     */
    public void reportGameEvent(PlayerEventType type, PlayerIdentity player, String content) {
        if (!canReport() || !config.isChatReportEvents()) {
            return;
        }
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        reportQueue.submit(type, new PlayerEventPayload(
                player.uuid().toString(), player.name(), System.currentTimeMillis(), content.trim()));
    }

    // ---------------------------------------------------------------- 群服互联（群 → 游戏）

    /** 设置入站广播回调；实现方必须把调用切回服务器主线程。 */
    public void setInboundBroadcast(java.util.function.Consumer<String> broadcast) {
        this.inboundBroadcast = broadcast;
    }

    public void tickInbound() {
        if (!canReport() || !config.isChatBridgeEnabled() || !config.isChatPollInbound()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastInboundPollAt < config.getChatPollIntervalMs()) {
            return;
        }
        lastInboundPollAt = now;
        CompletableFuture.runAsync(this::pollInbound);
    }

    private void pollInbound() {
        YudreamApiClient client = apiClient;
        BridgeConfig snapshot = config;
        java.util.function.Consumer<String> broadcast = inboundBroadcast;
        if (client == null || snapshot == null || broadcast == null) {
            return;
        }
        // 优先 SSE 长连接：群消息即到即推；连不上（旧宿主/网络）再退回轮询
        if (!inboundStreaming.compareAndSet(false, true)) {
            return;
        }
        try {
            try {
                streamInbound(client, snapshot, broadcast);
                return;
            } catch (IOException streamFailure) {
                logger.info("YuDream inbound chat stream unavailable, falling back to polling: "
                        + streamFailure.getMessage());
            }
            pollInboundOnce(client, snapshot, broadcast);
        } finally {
            inboundStreaming.set(false);
        }
    }

    /** 阻塞读 SSE 实时推送直到服务端断开；流不可用（非 200/非 SSE）抛 {@link IOException}。 */
    private void streamInbound(YudreamApiClient client, BridgeConfig snapshot,
                               java.util.function.Consumer<String> broadcast) throws IOException {
        InboundChatSse.read(client.inboundChatStream(inboundCursor), new InboundChatSse.Listener() {
            @Override
            public void onConnected(long latest) {
                // 首连快进：重启后不重播历史窗口内还留着的群消息
                if (inboundCursor == 0L && latest > 0) {
                    inboundCursor = latest;
                }
            }

            @Override
            public void onMessage(long seq, String sender, String content, long at) {
                if (seq <= inboundCursor || content.trim().isEmpty()) {
                    return;
                }
                inboundCursor = seq;
                broadcast.accept(snapshot.formatChatInbound(sender, content));
            }
        });
    }

    private void pollInboundOnce(YudreamApiClient client, BridgeConfig snapshot,
                                 java.util.function.Consumer<String> broadcast) {
        try {
            HttpResult result = client.inboundChat(inboundCursor);
            if (!result.isSuccess()) {
                return;
            }
            JsonValue body;
            try {
                body = JsonValue.parse(result.body());
            } catch (JsonSyntaxException e) {
                return;
            }
            if (!body.isObject()) {
                return;
            }
            long latest = body.getOr("latest", JsonValue.of(inboundCursor)).asLong(inboundCursor);
            // 首次拉取只快进游标：重启后不重播历史窗口内还留着的群消息
            boolean fastForward = inboundCursor == 0L && latest > 0;
            inboundCursor = Math.max(inboundCursor, latest);
            if (fastForward) {
                return;
            }
            for (JsonValue message : body.getOr("messages", JsonValue.array()).items()) {
                if (!message.isObject()) {
                    continue;
                }
                String content = message.getOr("content", JsonValue.of("")).asString("");
                if (content.trim().isEmpty()) {
                    continue;
                }
                long seq = message.getOr("seq", JsonValue.of(0L)).asLong(0L);
                inboundCursor = Math.max(inboundCursor, seq);
                String line = snapshot.formatChatInbound(
                        message.getOr("sender", JsonValue.of("")).asString(""), content);
                broadcast.accept(line);
            }
        } catch (Exception e) {
            logger.warn("YuDream inbound chat poll failed: " + e.getMessage());
        }
    }

    public void markActive(PlayerIdentity player) {
        if (!canReport() || !config.isAfkEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        PlayerActivityState state = states.get(player.uuid());
        if (state == null) {
            states.put(player.uuid(), new PlayerActivityState(now, false));
            return;
        }
        if (state.isAfk()) {
            reportQueue.submit(PlayerEventType.AFK_END, player.payload(now));
        }
        states.put(player.uuid(), new PlayerActivityState(now, false));
    }

    public void tick(Collection<PlayerIdentity> onlinePlayers) {
        if (!canReport()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSnapshotAt >= SNAPSHOT_INTERVAL_MS) {
            reportSnapshot(onlinePlayers, now);
        }
        if (!config.isAfkEnabled()) {
            return;
        }
        if (now - lastAfkCheckAt < config.getAfkCheckIntervalMs()) {
            return;
        }
        lastAfkCheckAt = now;
        for (PlayerIdentity player : onlinePlayers) {
            PlayerActivityState state = states.get(player.uuid());
            if (state == null) {
                states.put(player.uuid(), new PlayerActivityState(now, false));
                continue;
            }
            if (!state.isAfk() && now - state.getLastActiveAt() >= config.getAfkTimeoutMs()) {
                states.put(player.uuid(), new PlayerActivityState(state.getLastActiveAt(), true));
                reportQueue.submit(PlayerEventType.AFK_START, player.payload(now));
            }
        }
    }

    private void reportSnapshot(Collection<PlayerIdentity> onlinePlayers, long observedAt) {
        java.util.List<PlayerEventPayload> players = onlinePlayers.stream()
                .map(player -> player.payload(observedAt))
                .collect(java.util.stream.Collectors.toList());
        reportQueue.submitSnapshot(players, observedAt);
        lastSnapshotAt = observedAt;
    }

    public void statusAsync(Consumer<String> callback) {
        BridgeConfig snapshot = config;
        YudreamApiClient client = apiClient;
        if (snapshot == null || !snapshot.isEnabled() || !snapshot.isConfigured() || client == null) {
            callback.accept("YuDream bridge is not configured or disabled.");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                HttpResult result = client.players(1, 100);
                if (result.isSuccess()) {
                    PlayerSummary summary = PlayerSummary.parse(result.body());
                    callback.accept("Remote players: total=" + summary.total()
                            + ", online=" + summary.online()
                            + ", afk=" + summary.afk()
                            + ", http=" + result.statusCode());
                } else {
                    callback.accept("Remote status failed: HTTP " + result.statusCode() + " " + trim(result.body()));
                }
            } catch (Exception e) {
                callback.accept("Remote status failed: " + e.getMessage());
            }
        });
    }

    public int queueSize() {
        return reportQueue == null ? 0 : reportQueue.size();
    }

    public BridgeConfig getConfig() {
        return config == null ? BridgeConfig.defaults() : config;
    }

    public boolean shouldSyncOnlineOnStart() {
        return config != null && config.isSyncOnlineOnStart();
    }

    private boolean canReport() {
        return config != null && config.isEnabled() && config.isConfigured() && reportQueue != null;
    }

    private long currentFlushTimeout() {
        return config == null ? 5000L : config.getFlushTimeoutMs();
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 160 ? body : body.substring(0, 160) + "...";
    }
}
