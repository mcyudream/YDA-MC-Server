package online.yudream.minecraft.mod.common;

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

public final class YudreamApiClient {

    private static final String PLUGIN_PATH = "/api/plugins/minecraft-server/servers/";

    private final BridgeConfig config;

    public YudreamApiClient(BridgeConfig config) {
        this.config = config;
    }

    public HttpResult report(PlayerEventType type, PlayerEventPayload payload) throws IOException {
        return request("POST", serverUrl("/" + type.getRemotePath()), JsonObjects.playerEvent(payload));
    }

    public HttpResult snapshot(Collection<PlayerEventPayload> players, long observedAt) throws IOException {
        return request("POST", serverUrl("/players/snapshot"), JsonObjects.playerSnapshot(players, observedAt));
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

    String serverUrl(String serverRelativePath) {
        return config.getBaseUrl()
                + PLUGIN_PATH
                + encodePathSegment(config.getServerId())
                + serverRelativePath;
    }

    private HttpResult request(String method, String url, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(config.getConnectTimeoutMs());
        connection.setReadTimeout(config.getReadTimeoutMs());
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "YudreamMinecraftServerMod/1.0");
        connection.setRequestProperty("X-API-Key", config.getApiKey());

        if (body != null) {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(data);
            }
        }

        int status = connection.getResponseCode();
        String responseBody = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        return new HttpResult(status, responseBody);
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(line);
            }
        }
        return body.toString();
    }

    static String encodePathSegment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (IOException e) {
            throw new IllegalStateException("UTF-8 is unavailable", e);
        }
    }
}
