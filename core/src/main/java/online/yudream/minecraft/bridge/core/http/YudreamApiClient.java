package online.yudream.minecraft.bridge.core.http;

import online.yudream.minecraft.bridge.core.config.BridgeSettings;
import online.yudream.minecraft.bridge.core.json.JsonValue;
import online.yudream.minecraft.bridge.core.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.core.model.PlayerEventType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Talks to the YuDream Admin {@code minecraft-server} plugin.
 *
 * <p>The wire format is intentionally byte-identical to the one the Bukkit plugin and the
 * Forge/NeoForge mods already send:
 *
 * <pre>
 * POST .../players/{join|quit|afk/start|afk/end}   {"playerId":"..","playerName":"..","eventAt":123}
 * POST .../players/snapshot                       {"observedAt":123,"players":[{"playerId":"..","playerName":".."}]}
 * GET  .../players?page=1&amp;size=100
 * </pre>
 *
 * <p>{@code serverName} is local metadata and is only added to the snapshot body when
 * {@code api.include-server-name-in-snapshot} is switched on, because a strictly validating backend
 * would reject the extra field.
 */
public final class YudreamApiClient {

    private static final String PLUGIN_PATH = "/api/plugins/minecraft-server/servers/";

    private final BridgeSettings settings;
    private final String userAgent;

    public YudreamApiClient(BridgeSettings settings, String userAgent) {
        this.settings = settings;
        this.userAgent = userAgent;
    }

    public HttpResult report(PlayerEventType type, PlayerEventPayload payload) throws IOException {
        return request("POST", serverUrl("/" + type.getRemotePath()), eventBody(payload));
    }

    public HttpResult snapshot(Collection<PlayerEventPayload> players, long observedAt, String serverName) throws IOException {
        return request("POST", serverUrl("/players/snapshot"), snapshotBody(players, observedAt, serverName));
    }

    public HttpResult players(int page, int size) throws IOException {
        String path = "/players?page=" + Math.max(page, 1) + "&size=" + Math.max(Math.min(size, 100), 1);
        return request("GET", serverUrl(path), null);
    }

    /** 群服互联：按游标增量拉取要广播进游戏的群消息。 */
    public HttpResult inboundChat(long after) throws IOException {
        String path = "/chat/inbound?after=" + Math.max(after, 0) + "&limit=50";
        return request("GET", serverUrl(path), null);
    }

    /**
     * 群服互联 SSE：打开实时推送长连接，调用方用 {@link InboundChatSse#read} 阻塞读。
     * 读取超时放宽到 90s，靠服务端 25s 心跳保活；连不上或响应不是 SSE 时抛
     * {@link IOException}，调用方降级回 {@link #inboundChat(long)} 轮询。
     */
    public HttpURLConnection inboundChatStream(long after) throws IOException {
        String path = "/chat/inbound/stream?after=" + Math.max(after, 0);
        HttpURLConnection connection = (HttpURLConnection) URI.create(serverUrl(path)).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(settings.getConnectTimeoutMs());
        connection.setReadTimeout(Math.max(settings.getReadTimeoutMs(), 90_000));
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("X-API-Key", settings.getApiKey());
        return connection;
    }

    public String serverUrl(String serverRelativePath) {
        return settings.getBaseUrl()
                + PLUGIN_PATH
                + encodePathSegment(settings.getServerId())
                + serverRelativePath;
    }

    public String eventBody(PlayerEventPayload payload) {
        JsonValue body = JsonValue.object()
                .put("playerId", payload.playerId())
                .put("playerName", payload.playerName())
                .put("eventAt", payload.eventAt());
        // content 只在群服互联事件（聊天/死亡/成就）上出现；旧端点的载荷保持不变
        if (payload.content() != null && !payload.content().isEmpty()) {
            body.put("content", payload.content());
        }
        return body.toString();
    }

    public String snapshotBody(Collection<PlayerEventPayload> players, long observedAt, String serverName) {
        JsonValue body = JsonValue.object();
        body.put("observedAt", observedAt);
        if (settings.isIncludeServerNameInSnapshot() && serverName != null && !serverName.isEmpty()) {
            body.put("serverName", serverName);
        }
        JsonValue list = JsonValue.array();
        for (PlayerEventPayload player : players) {
            list.add(JsonValue.object()
                    .put("playerId", player.playerId())
                    .put("playerName", player.playerName()));
        }
        body.put("players", list);
        return body.toString();
    }

    private HttpResult request(String method, String url, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(settings.getConnectTimeoutMs());
            connection.setReadTimeout(settings.getReadTimeoutMs());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("X-API-Key", settings.getApiKey());

            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setFixedLengthStreamingMode(data.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(data);
                } finally {
                    out.close();
                }
            }

            int status = connection.getResponseCode();
            String responseBody = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            return new HttpResult(status, responseBody);
        } finally {
            connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(line);
            }
            return body.toString();
        } finally {
            reader.close();
        }
    }

    static String encodePathSegment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable", e);
        }
    }
}
