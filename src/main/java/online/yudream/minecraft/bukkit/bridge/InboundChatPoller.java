package online.yudream.minecraft.bukkit.bridge;

import online.yudream.minecraft.bukkit.YudreamMinecraftPlugin;
import online.yudream.minecraft.bukkit.api.HttpResult;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 群服互联入站：按游标轮询 YuDream Admin 的群消息队列，并把新消息广播进游戏。
 *
 * <p>轮询在异步线程执行（HTTP 与 JSON 解析都不碰 Bukkit API），广播切回主线程。
 * 游标只保存在内存里：重启后游标归零，只会重复最近几分钟内的群消息（Admin 侧队列
 * 只保留 10 分钟内的消息），影响可忽略。
 */
public final class InboundChatPoller {

    private static final int LOG_THROTTLE_MS = 60_000;

    private final YudreamMinecraftPlugin plugin;
    private long cursor;
    private long lastFailureLogAt;

    public InboundChatPoller(YudreamMinecraftPlugin plugin) {
        this.plugin = plugin;
    }

    /** 由异步循环任务调用；所有失败都折叠成日志，绝不抛出。 */
    public void poll() {
        if (!plugin.canReport()) {
            // 下游模式由代理统一拉取并通过插件消息通道下发；本机无凭据时不轮询。
            return;
        }
        HttpResult result;
        try {
            result = plugin.getApiClient().inboundChat(cursor);
        } catch (IOException e) {
            logFailure("连接 YuDream Admin 失败：" + e.getMessage());
            return;
        }
        if (!result.isSuccess()) {
            logFailure("拉取群消息失败，HTTP " + result.getStatusCode());
            return;
        }
        Map<String, Object> body = MiniJson.parseObject(result.getBody());
        if (body == null) {
            return;
        }
        long latest = MiniJson.longValue(body, "latest", cursor);
        // 首次拉取只快进游标：重启后不重播历史窗口内还留着的群消息
        boolean fastForward = cursor == 0L && latest > 0;
        cursor = Math.max(cursor, latest);
        Object rawMessages = body.get("messages");
        if (!(rawMessages instanceof List) || fastForward) {
            return;
        }
        List<Broadcast> broadcasts = new ArrayList<Broadcast>();
        for (Object item : (List<?>) rawMessages) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> message = (Map<?, ?>) item;
            Object content = message.get("content");
            if (content == null || String.valueOf(content).trim().isEmpty()) {
                continue;
            }
            long seq = message.get("seq") instanceof Number ? ((Number) message.get("seq")).longValue() : 0L;
            String sender = String.valueOf(message.get("sender"));
            broadcasts.add(new Broadcast(seq, sender, String.valueOf(content)));
            cursor = Math.max(cursor, seq);
        }
        if (broadcasts.isEmpty()) {
            return;
        }
        Runnable deliver = new Runnable() {
            @Override
            public void run() {
                for (Broadcast broadcast : broadcasts) {
                    String line = plugin.getSettings().getChatBridge().formatInbound(broadcast.sender, broadcast.content);
                    Bukkit.broadcastMessage(line);
                }
            }
        };
        plugin.getServer().getScheduler().runTask(plugin, deliver);
    }

    private void logFailure(String message) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogAt < LOG_THROTTLE_MS) {
            return;
        }
        lastFailureLogAt = now;
        plugin.getLogger().log(Level.WARNING, "群服互联入站拉取：" + message);
    }

    private static final class Broadcast {
        private final long seq;
        private final String sender;
        private final String content;

        private Broadcast(long seq, String sender, String content) {
            this.seq = seq;
            this.sender = sender;
            this.content = content;
        }
    }
}
