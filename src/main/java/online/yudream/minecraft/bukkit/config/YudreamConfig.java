package online.yudream.minecraft.bukkit.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class YudreamConfig {

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
    private final long afkCheckIntervalTicks;
    private final boolean resetOnMove;
    private final boolean resetOnChat;
    private final boolean resetOnCommand;
    private final boolean resetOnInteract;
    private final boolean syncOnlineOnEnable;
    private final boolean reportQuitOnDisable;
    private final long flushTimeoutMs;
    private final DownstreamOptions downstream;

    private YudreamConfig(boolean enabled,
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
                          long afkCheckIntervalTicks,
                          boolean resetOnMove,
                          boolean resetOnChat,
                          boolean resetOnCommand,
                          boolean resetOnInteract,
                          boolean syncOnlineOnEnable,
                          boolean reportQuitOnDisable,
                          long flushTimeoutMs,
                          DownstreamOptions downstream) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.serverId = serverId;
        this.apiKey = apiKey;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.retryAttempts = retryAttempts;
        this.retryDelayMs = retryDelayMs;
        this.queueCapacity = queueCapacity;
        this.logQueued = logQueued;
        this.logAttempts = logAttempts;
        this.logSuccess = logSuccess;
        this.logFailures = logFailures;
        this.logPayload = logPayload;
        this.afkEnabled = afkEnabled;
        this.afkTimeoutMs = afkTimeoutMs;
        this.afkCheckIntervalTicks = afkCheckIntervalTicks;
        this.resetOnMove = resetOnMove;
        this.resetOnChat = resetOnChat;
        this.resetOnCommand = resetOnCommand;
        this.resetOnInteract = resetOnInteract;
        this.syncOnlineOnEnable = syncOnlineOnEnable;
        this.reportQuitOnDisable = reportQuitOnDisable;
        this.flushTimeoutMs = flushTimeoutMs;
        this.downstream = downstream == null ? DownstreamOptions.defaults() : downstream;
    }

    public static YudreamConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        String baseUrl = stripTrailingSlash(config.getString("base-url", ""));
        return new YudreamConfig(
                config.getBoolean("enabled", true),
                baseUrl,
                trim(config.getString("server-id", "")),
                trim(config.getString("api-key", "")),
                positive(config.getInt("http.connect-timeout-ms", 5000), 5000),
                positive(config.getInt("http.read-timeout-ms", 8000), 8000),
                Math.max(config.getInt("http.retry-attempts", 3), 1),
                positive(config.getLong("http.retry-delay-ms", 1500L), 1500L),
                Math.max(config.getInt("http.queue-capacity", 1000), 1),
                config.getBoolean("http.log-queued", false),
                config.getBoolean("http.log-attempts", false),
                config.getBoolean("http.log-success", true),
                config.getBoolean("http.log-failures", true),
                config.getBoolean("http.log-payload", false),
                config.getBoolean("afk.enabled", true),
                positive(config.getLong("afk.timeout-seconds", 300L), 300L) * 1000L,
                positive(config.getLong("afk.check-interval-seconds", 30L), 30L) * 20L,
                config.getBoolean("afk.reset-on-move", true),
                config.getBoolean("afk.reset-on-chat", true),
                config.getBoolean("afk.reset-on-command", true),
                config.getBoolean("afk.reset-on-interact", true),
                config.getBoolean("startup.sync-online-on-enable", true),
                config.getBoolean("shutdown.report-quit-on-disable", false),
                positive(config.getLong("shutdown.flush-timeout-ms", 5000L), 5000L),
                DownstreamOptions.load(config)
        );
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

    public long getAfkCheckIntervalTicks() {
        return afkCheckIntervalTicks;
    }

    public boolean isResetOnMove() {
        return resetOnMove;
    }

    public boolean isResetOnChat() {
        return resetOnChat;
    }

    public boolean isResetOnCommand() {
        return resetOnCommand;
    }

    public boolean isResetOnInteract() {
        return resetOnInteract;
    }

    public boolean isSyncOnlineOnEnable() {
        return syncOnlineOnEnable;
    }

    public boolean isReportQuitOnDisable() {
        return reportQuitOnDisable;
    }

    public long getFlushTimeoutMs() {
        return flushTimeoutMs;
    }

    /** Mode selection and the knobs that only matter when this server sits behind a proxy. */
    public DownstreamOptions getDownstream() {
        return downstream;
    }

    public boolean isDownstream() {
        return downstream.isDownstream();
    }
}
