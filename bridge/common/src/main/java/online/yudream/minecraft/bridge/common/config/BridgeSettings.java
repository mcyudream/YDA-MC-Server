package online.yudream.minecraft.bridge.common.config;

/**
 * The reporting settings shared by every runtime: the YuDream Admin endpoint, HTTP behaviour,
 * AFK thresholds, logging switches and shutdown behaviour.
 *
 * <p>Only the proxy uploads, so in practice the Velocity plugin owns this object. The Fabric sensor
 * still reads the same keys it cares about (logging and AFK activity switches) so an operator can
 * keep one mental model for both files.
 */
public final class BridgeSettings {

    private final boolean enabled;
    private final String baseUrl;
    private final String serverId;
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int retryAttempts;
    private final long retryDelayMs;
    private final int queueCapacity;
    private final boolean persistQueue;
    private final String queueFile;
    private final boolean logQueued;
    private final boolean logAttempts;
    private final boolean logSuccess;
    private final boolean logFailures;
    private final boolean logPayload;
    private final boolean afkEnabled;
    private final long afkTimeoutMs;
    private final long afkCheckIntervalMs;
    private final boolean syncOnlineOnEnable;
    private final boolean reportQuitOnDisable;
    private final long flushTimeoutMs;
    private final boolean serverSwitchEventEnabled;
    private final boolean includeServerNameInSnapshot;

    private BridgeSettings(Builder builder) {
        this.enabled = builder.enabled;
        this.baseUrl = stripTrailingSlash(builder.baseUrl);
        this.serverId = trim(builder.serverId);
        this.apiKey = trim(builder.apiKey);
        this.connectTimeoutMs = positive(builder.connectTimeoutMs, 5000);
        this.readTimeoutMs = positive(builder.readTimeoutMs, 8000);
        this.retryAttempts = Math.max(builder.retryAttempts, 1);
        this.retryDelayMs = positive(builder.retryDelayMs, 1500L);
        this.queueCapacity = Math.max(builder.queueCapacity, 1);
        this.persistQueue = builder.persistQueue;
        this.queueFile = trim(builder.queueFile).isEmpty() ? "report-queue.jsonl" : trim(builder.queueFile);
        this.logQueued = builder.logQueued;
        this.logAttempts = builder.logAttempts;
        this.logSuccess = builder.logSuccess;
        this.logFailures = builder.logFailures;
        this.logPayload = builder.logPayload;
        this.afkEnabled = builder.afkEnabled;
        this.afkTimeoutMs = positive(builder.afkTimeoutMs, 300_000L);
        this.afkCheckIntervalMs = positive(builder.afkCheckIntervalMs, 30_000L);
        this.syncOnlineOnEnable = builder.syncOnlineOnEnable;
        this.reportQuitOnDisable = builder.reportQuitOnDisable;
        this.flushTimeoutMs = positive(builder.flushTimeoutMs, 5000L);
        this.serverSwitchEventEnabled = builder.serverSwitchEventEnabled;
        this.includeServerNameInSnapshot = builder.includeServerNameInSnapshot;
    }

    public static BridgeSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .enabled(enabled)
                .baseUrl(baseUrl)
                .serverId(serverId)
                .apiKey(apiKey)
                .connectTimeoutMs(connectTimeoutMs)
                .readTimeoutMs(readTimeoutMs)
                .retryAttempts(retryAttempts)
                .retryDelayMs(retryDelayMs)
                .queueCapacity(queueCapacity)
                .persistQueue(persistQueue)
                .queueFile(queueFile)
                .logQueued(logQueued)
                .logAttempts(logAttempts)
                .logSuccess(logSuccess)
                .logFailures(logFailures)
                .logPayload(logPayload)
                .afkEnabled(afkEnabled)
                .afkTimeoutMs(afkTimeoutMs)
                .afkCheckIntervalMs(afkCheckIntervalMs)
                .syncOnlineOnEnable(syncOnlineOnEnable)
                .reportQuitOnDisable(reportQuitOnDisable)
                .flushTimeoutMs(flushTimeoutMs)
                .serverSwitchEventEnabled(serverSwitchEventEnabled)
                .includeServerNameInSnapshot(includeServerNameInSnapshot);
    }

    /** Reads every setting, falling back to {@link #defaults()} for anything missing or invalid. */
    public static BridgeSettings from(ConfigFile config) {
        BridgeSettings fallback = defaults();
        return builder()
                .enabled(config.getBoolean("enabled", fallback.enabled))
                .baseUrl(config.get("api.base-url", fallback.baseUrl))
                .serverId(config.get("api.server-id", fallback.serverId))
                .apiKey(config.get("api.api-key", fallback.apiKey))
                .includeServerNameInSnapshot(config.getBoolean("api.include-server-name-in-snapshot", fallback.includeServerNameInSnapshot))
                .connectTimeoutMs(config.getInt("http.connect-timeout-ms", fallback.connectTimeoutMs))
                .readTimeoutMs(config.getInt("http.read-timeout-ms", fallback.readTimeoutMs))
                .retryAttempts(config.getInt("http.retry-attempts", fallback.retryAttempts))
                .retryDelayMs(config.getLong("http.retry-delay-ms", fallback.retryDelayMs))
                .queueCapacity(config.getInt("http.queue-capacity", fallback.queueCapacity))
                .persistQueue(config.getBoolean("http.persist-queue", fallback.persistQueue))
                .queueFile(config.get("http.queue-file", fallback.queueFile))
                .logQueued(config.getBoolean("http.log-queued", fallback.logQueued))
                .logAttempts(config.getBoolean("http.log-attempts", fallback.logAttempts))
                .logSuccess(config.getBoolean("http.log-success", fallback.logSuccess))
                .logFailures(config.getBoolean("http.log-failures", fallback.logFailures))
                .logPayload(config.getBoolean("http.log-payload", fallback.logPayload))
                .afkEnabled(config.getBoolean("afk.enabled", fallback.afkEnabled))
                .afkTimeoutMs(config.getLong("afk.timeout-seconds", fallback.afkTimeoutMs / 1000L) * 1000L)
                .afkCheckIntervalMs(config.getLong("afk.check-interval-seconds", fallback.afkCheckIntervalMs / 1000L) * 1000L)
                .syncOnlineOnEnable(config.getBoolean("startup.sync-online-on-enable", fallback.syncOnlineOnEnable))
                .reportQuitOnDisable(config.getBoolean("shutdown.report-quit-on-disable", fallback.reportQuitOnDisable))
                .flushTimeoutMs(config.getLong("shutdown.flush-timeout-ms", fallback.flushTimeoutMs))
                .serverSwitchEventEnabled(config.getBoolean("events.server-switch", fallback.serverSwitchEventEnabled))
                .build();
    }

    /** Fills in any key this class reads that the file does not define yet. */
    public static void applyDefaults(ConfigFile config, BridgeSettings settings) {
        config.setIfAbsent("enabled", settings.enabled);
        config.setIfAbsent("api.base-url", settings.baseUrl);
        config.setIfAbsent("api.server-id", settings.serverId);
        config.setIfAbsent("api.api-key", settings.apiKey);
        config.setIfAbsent("api.include-server-name-in-snapshot", settings.includeServerNameInSnapshot);
        config.setIfAbsent("http.connect-timeout-ms", settings.connectTimeoutMs);
        config.setIfAbsent("http.read-timeout-ms", settings.readTimeoutMs);
        config.setIfAbsent("http.retry-attempts", settings.retryAttempts);
        config.setIfAbsent("http.retry-delay-ms", settings.retryDelayMs);
        config.setIfAbsent("http.queue-capacity", settings.queueCapacity);
        config.setIfAbsent("http.persist-queue", settings.persistQueue);
        config.setIfAbsent("http.queue-file", settings.queueFile);
        config.setIfAbsent("http.log-queued", settings.logQueued);
        config.setIfAbsent("http.log-attempts", settings.logAttempts);
        config.setIfAbsent("http.log-success", settings.logSuccess);
        config.setIfAbsent("http.log-failures", settings.logFailures);
        config.setIfAbsent("http.log-payload", settings.logPayload);
        config.setIfAbsent("afk.enabled", settings.afkEnabled);
        config.setIfAbsent("afk.timeout-seconds", settings.afkTimeoutMs / 1000L);
        config.setIfAbsent("afk.check-interval-seconds", settings.afkCheckIntervalMs / 1000L);
        config.setIfAbsent("startup.sync-online-on-enable", settings.syncOnlineOnEnable);
        config.setIfAbsent("shutdown.report-quit-on-disable", settings.reportQuitOnDisable);
        config.setIfAbsent("shutdown.flush-timeout-ms", settings.flushTimeoutMs);
        config.setIfAbsent("events.server-switch", settings.serverSwitchEventEnabled);
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

    public boolean isPersistQueue() {
        return persistQueue;
    }

    public String getQueueFile() {
        return queueFile;
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

    public boolean isSyncOnlineOnEnable() {
        return syncOnlineOnEnable;
    }

    public boolean isReportQuitOnDisable() {
        return reportQuitOnDisable;
    }

    public long getFlushTimeoutMs() {
        return flushTimeoutMs;
    }

    public boolean isServerSwitchEventEnabled() {
        return serverSwitchEventEnabled;
    }

    public boolean isIncludeServerNameInSnapshot() {
        return includeServerNameInSnapshot;
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

    /** Mutable builder; only {@link BridgeSettings} produces an immutable instance. */
    public static final class Builder {

        private boolean enabled = true;
        private String baseUrl = "http://127.0.0.1:8080";
        private String serverId = "";
        private String apiKey = "";
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 8000;
        private int retryAttempts = 3;
        private long retryDelayMs = 1500L;
        private int queueCapacity = 1000;
        private boolean persistQueue = true;
        private String queueFile = "report-queue.jsonl";
        private boolean logQueued = false;
        private boolean logAttempts = false;
        private boolean logSuccess = true;
        private boolean logFailures = true;
        private boolean logPayload = false;
        private boolean afkEnabled = true;
        private long afkTimeoutMs = 300_000L;
        private long afkCheckIntervalMs = 30_000L;
        private boolean syncOnlineOnEnable = true;
        private boolean reportQuitOnDisable = false;
        private long flushTimeoutMs = 5000L;
        private boolean serverSwitchEventEnabled = false;
        private boolean includeServerNameInSnapshot = false;

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder baseUrl(String value) {
            this.baseUrl = value;
            return this;
        }

        public Builder serverId(String value) {
            this.serverId = value;
            return this;
        }

        public Builder apiKey(String value) {
            this.apiKey = value;
            return this;
        }

        public Builder connectTimeoutMs(int value) {
            this.connectTimeoutMs = value;
            return this;
        }

        public Builder readTimeoutMs(int value) {
            this.readTimeoutMs = value;
            return this;
        }

        public Builder retryAttempts(int value) {
            this.retryAttempts = value;
            return this;
        }

        public Builder retryDelayMs(long value) {
            this.retryDelayMs = value;
            return this;
        }

        public Builder queueCapacity(int value) {
            this.queueCapacity = value;
            return this;
        }

        public Builder persistQueue(boolean value) {
            this.persistQueue = value;
            return this;
        }

        public Builder queueFile(String value) {
            this.queueFile = value;
            return this;
        }

        public Builder logQueued(boolean value) {
            this.logQueued = value;
            return this;
        }

        public Builder logAttempts(boolean value) {
            this.logAttempts = value;
            return this;
        }

        public Builder logSuccess(boolean value) {
            this.logSuccess = value;
            return this;
        }

        public Builder logFailures(boolean value) {
            this.logFailures = value;
            return this;
        }

        public Builder logPayload(boolean value) {
            this.logPayload = value;
            return this;
        }

        public Builder afkEnabled(boolean value) {
            this.afkEnabled = value;
            return this;
        }

        public Builder afkTimeoutMs(long value) {
            this.afkTimeoutMs = value;
            return this;
        }

        public Builder afkCheckIntervalMs(long value) {
            this.afkCheckIntervalMs = value;
            return this;
        }

        public Builder syncOnlineOnEnable(boolean value) {
            this.syncOnlineOnEnable = value;
            return this;
        }

        public Builder reportQuitOnDisable(boolean value) {
            this.reportQuitOnDisable = value;
            return this;
        }

        public Builder flushTimeoutMs(long value) {
            this.flushTimeoutMs = value;
            return this;
        }

        public Builder serverSwitchEventEnabled(boolean value) {
            this.serverSwitchEventEnabled = value;
            return this;
        }

        public Builder includeServerNameInSnapshot(boolean value) {
            this.includeServerNameInSnapshot = value;
            return this;
        }

        public BridgeSettings build() {
            return new BridgeSettings(this);
        }
    }
}
