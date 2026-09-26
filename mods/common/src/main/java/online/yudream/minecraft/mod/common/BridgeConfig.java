package online.yudream.minecraft.mod.common;

public final class BridgeConfig {

    private final boolean enabled;
    private final String baseUrl;
    private final String serverId;
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int retryAttempts;
    private final long retryDelayMs;
    private final int queueCapacity;
    private final boolean logQueued;
    private final boolean logAttempts;
    private final boolean logSuccess;
    private final boolean logFailures;
    private final boolean logPayload;
    private final boolean afkEnabled;
    private final long afkTimeoutMs;
    private final long afkCheckIntervalMs;
    private final boolean syncOnlineOnStart;
    private final boolean reportQuitOnStop;
    private final long flushTimeoutMs;
    private final boolean chatBridgeEnabled;
    private final boolean chatReportEvents;
    private final boolean chatPollInbound;
    private final long chatPollIntervalMs;
    private final String chatInboundFormat;

    public BridgeConfig(boolean enabled,
                        String baseUrl,
                        String serverId,
                        String apiKey,
                        int connectTimeoutMs,
                        int readTimeoutMs,
                        int retryAttempts,
                        long retryDelayMs,
                        int queueCapacity,
                        boolean logQueued,
                        boolean logAttempts,
                        boolean logSuccess,
                        boolean logFailures,
                        boolean logPayload,
                        boolean afkEnabled,
                        long afkTimeoutMs,
                        long afkCheckIntervalMs,
                        boolean syncOnlineOnStart,
                        boolean reportQuitOnStop,
                        long flushTimeoutMs,
                        boolean chatBridgeEnabled,
                        boolean chatReportEvents,
                        boolean chatPollInbound,
                        long chatPollIntervalMs,
                        String chatInboundFormat) {
        this.enabled = enabled;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.serverId = trim(serverId);
        this.apiKey = trim(apiKey);
        this.connectTimeoutMs = positive(connectTimeoutMs, 5000);
        this.readTimeoutMs = positive(readTimeoutMs, 8000);
        this.retryAttempts = Math.max(retryAttempts, 1);
        this.retryDelayMs = positive(retryDelayMs, 1500L);
        this.queueCapacity = Math.max(queueCapacity, 1);
        this.logQueued = logQueued;
        this.logAttempts = logAttempts;
        this.logSuccess = logSuccess;
        this.logFailures = logFailures;
        this.logPayload = logPayload;
        this.afkEnabled = afkEnabled;
        this.afkTimeoutMs = positive(afkTimeoutMs, 300_000L);
        this.afkCheckIntervalMs = positive(afkCheckIntervalMs, 30_000L);
        this.syncOnlineOnStart = syncOnlineOnStart;
        this.reportQuitOnStop = reportQuitOnStop;
        this.flushTimeoutMs = positive(flushTimeoutMs, 5000L);
        this.chatBridgeEnabled = chatBridgeEnabled;
        this.chatReportEvents = chatReportEvents;
        this.chatPollInbound = chatPollInbound;
        this.chatPollIntervalMs = Math.max(positive(chatPollIntervalMs, 3000L), 2000L);
        this.chatInboundFormat = normalizeFormat(chatInboundFormat);
    }

    private static String normalizeFormat(String value) {
        String format = trim(value);
        if (format.isEmpty()) {
            return "§8[§a群§8] §f{sender} §7» §f{content}";
        }
        return format.contains("{sender}") || format.contains("{content}")
                ? format
                : "§8[§a群§8] §f{sender} §7» §f{content}";
    }

    public static BridgeConfig defaults() {
        return new BridgeConfig(true, "http://127.0.0.1:8080", "", "",
                5000, 8000, 3, 1500L, 1000,
                false, false, true, true, false,
                true, 300_000L, 30_000L,
                true, false, 5000L,
                true, true, true, 3000L,
                "§8[§a群§8] §f{sender} §7» §f{content}");
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !serverId.isEmpty() && !apiKey.isEmpty();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getServerId() {
        return serverId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public boolean isLogQueued() {
        return logQueued;
    }

    public boolean isLogAttempts() {
        return logAttempts;
    }

    public boolean isLogSuccess() {
        return logSuccess;
    }

    public boolean isLogFailures() {
        return logFailures;
    }

    public boolean isLogPayload() {
        return logPayload;
    }

    public boolean isAfkEnabled() {
        return afkEnabled;
    }

    public long getAfkTimeoutMs() {
        return afkTimeoutMs;
    }

    public long getAfkCheckIntervalMs() {
        return afkCheckIntervalMs;
    }

    public boolean isSyncOnlineOnStart() {
        return syncOnlineOnStart;
    }

    public boolean isReportQuitOnStop() {
        return reportQuitOnStop;
    }

    public long getFlushTimeoutMs() {
        return flushTimeoutMs;
    }

    public boolean isChatBridgeEnabled() {
        return chatBridgeEnabled;
    }

    public boolean isChatReportEvents() {
        return chatReportEvents;
    }

    public boolean isChatPollInbound() {
        return chatPollInbound;
    }

    public long getChatPollIntervalMs() {
        return chatPollIntervalMs;
    }

    /** 群消息广播格式；{sender} 为发送者标识，{content} 为消息内容。 */
    public String formatChatInbound(String sender, String content) {
        return chatInboundFormat
                .replace("{sender}", sanitize(sender))
                .replace("{content}", sanitize(content));
    }

    /** 群文本可能携带旧式 § 格式码，替换掉避免干扰游戏内聊天渲染。 */
    private static String sanitize(String value) {
        return value == null ? "" : value.replace('§', '&');
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        String result = trim(value);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }
}
