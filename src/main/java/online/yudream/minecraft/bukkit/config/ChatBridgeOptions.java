package online.yudream.minecraft.bukkit.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 群服互联的本地开关。
 *
 * <p>转发哪些消息、发到哪个群由 YuDream Admin 管理端逐服务器配置；这里的开关只控制
 * 本机是否参与：上报游戏事件（聊天/死亡/成就）与拉取群消息广播进游戏。
 */
public final class ChatBridgeOptions {

    private final boolean enabled;
    private final boolean reportEvents;
    private final boolean pollInbound;
    private final long pollIntervalTicks;
    private final String inboundFormat;

    private ChatBridgeOptions(boolean enabled, boolean reportEvents, boolean pollInbound,
                              long pollIntervalTicks, String inboundFormat) {
        this.enabled = enabled;
        this.reportEvents = reportEvents;
        this.pollInbound = pollInbound;
        this.pollIntervalTicks = pollIntervalTicks;
        this.inboundFormat = inboundFormat;
    }

    public static ChatBridgeOptions load(FileConfiguration config) {
        int intervalSeconds = config.getInt("chat-bridge.poll-interval-seconds", 3);
        return new ChatBridgeOptions(
                config.getBoolean("chat-bridge.enabled", true),
                config.getBoolean("chat-bridge.report-events", true),
                config.getBoolean("chat-bridge.poll-inbound", true),
                Math.max(intervalSeconds, 2) * 20L,
                normalizeFormat(config.getString("chat-bridge.inbound-format", null))
        );
    }

    public static ChatBridgeOptions defaults() {
        return new ChatBridgeOptions(true, true, true, 60L,
                "§8[§a群§8] §f{sender} §7» §f{content}");
    }

    private static String normalizeFormat(String value) {
        String format = value == null || value.trim().isEmpty()
                ? "§8[§a群§8] §f{sender} §7» §f{content}"
                : value.trim();
        if (!format.contains("{sender}") && !format.contains("{content}")) {
            return "§8[§a群§8] §f{sender} §7» §f{content}";
        }
        return format;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 上报聊天、死亡与成就事件（进退服始终由 Admin 侧基于已有上报生成）。 */
    public boolean isReportEvents() {
        return reportEvents;
    }

    /** 轮询群消息并广播进游戏。 */
    public boolean isPollInbound() {
        return pollInbound;
    }

    public long getPollIntervalTicks() {
        return pollIntervalTicks;
    }

    public String formatInbound(String sender, String content) {
        return inboundFormat
                .replace("{sender}", sanitize(sender))
                .replace("{content}", sanitize(content));
    }

    /** 群文本可能携带旧式 § 格式码，替换掉避免干扰游戏内聊天渲染。 */
    private static String sanitize(String value) {
        return value == null ? "" : value.replace('§', '&');
    }
}
