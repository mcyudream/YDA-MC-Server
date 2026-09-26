package online.yudream.minecraft.mod.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class BridgeConfigLoader {

    private BridgeConfigLoader() {
    }

    public static BridgeConfig load(Path file, LogSink logger) {
        try {
            ensureDefaultFile(file);
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
            return new BridgeConfig(
                    bool(properties, "enabled", true),
                    text(properties, "base-url", "http://127.0.0.1:8080"),
                    text(properties, "server-id", ""),
                    text(properties, "api-key", ""),
                    integer(properties, "http.connect-timeout-ms", 5000),
                    integer(properties, "http.read-timeout-ms", 8000),
                    integer(properties, "http.retry-attempts", 3),
                    longValue(properties, "http.retry-delay-ms", 1500L),
                    integer(properties, "http.queue-capacity", 1000),
                    bool(properties, "http.log-queued", false),
                    bool(properties, "http.log-attempts", false),
                    bool(properties, "http.log-success", true),
                    bool(properties, "http.log-failures", true),
                    bool(properties, "http.log-payload", false),
                    bool(properties, "afk.enabled", true),
                    longValue(properties, "afk.timeout-seconds", 300L) * 1000L,
                    longValue(properties, "afk.check-interval-seconds", 30L) * 1000L,
                    bool(properties, "startup.sync-online-on-start", true),
                    bool(properties, "shutdown.report-quit-on-stop", false),
                    longValue(properties, "shutdown.flush-timeout-ms", 5000L),
                    bool(properties, "chat-bridge.enabled", true),
                    bool(properties, "chat-bridge.report-events", true),
                    bool(properties, "chat-bridge.poll-inbound", true),
                    longValue(properties, "chat-bridge.poll-interval-seconds", 3L) * 1000L,
                    text(properties, "chat-bridge.inbound-format", "")
            );
        } catch (Exception e) {
            logger.warn("Failed to load YuDream config; using defaults: " + e.getMessage());
            return BridgeConfig.defaults();
        }
    }

    private static void ensureDefaultFile(Path file) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties properties = new Properties();
        properties.setProperty("base-url", "http://127.0.0.1:8080");
        properties.setProperty("server-id", "");
        properties.setProperty("api-key", "");
        properties.setProperty("enabled", "true");
        properties.setProperty("http.connect-timeout-ms", "5000");
        properties.setProperty("http.read-timeout-ms", "8000");
        properties.setProperty("http.retry-attempts", "3");
        properties.setProperty("http.retry-delay-ms", "1500");
        properties.setProperty("http.queue-capacity", "1000");
        properties.setProperty("http.log-queued", "false");
        properties.setProperty("http.log-attempts", "false");
        properties.setProperty("http.log-success", "true");
        properties.setProperty("http.log-failures", "true");
        properties.setProperty("http.log-payload", "false");
        properties.setProperty("afk.enabled", "true");
        properties.setProperty("afk.timeout-seconds", "300");
        properties.setProperty("afk.check-interval-seconds", "30");
        properties.setProperty("startup.sync-online-on-start", "true");
        properties.setProperty("shutdown.report-quit-on-stop", "false");
        properties.setProperty("shutdown.flush-timeout-ms", "5000");
        properties.setProperty("chat-bridge.enabled", "true");
        properties.setProperty("chat-bridge.report-events", "true");
        properties.setProperty("chat-bridge.poll-inbound", "true");
        properties.setProperty("chat-bridge.poll-interval-seconds", "3");
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "YuDream Minecraft server bridge config. API key is sent as X-API-Key and is never logged.");
        }
    }

    private static String text(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : value.trim();
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null || value.trim().isEmpty() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int integer(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(text(properties, key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longValue(Properties properties, String key, long fallback) {
        try {
            return Long.parseLong(text(properties, key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
