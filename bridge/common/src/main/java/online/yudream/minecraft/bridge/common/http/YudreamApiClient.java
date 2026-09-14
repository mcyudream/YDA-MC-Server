package online.yudream.minecraft.bridge.common.http;

import online.yudream.minecraft.bridge.common.config.BridgeSettings;
import online.yudream.minecraft.bridge.common.json.JsonValue;
import online.yudream.minecraft.bridge.common.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.common.model.PlayerEventType;
import online.yudream.minecraft.bridge.common.model.SubServerInfo;
import online.yudream.minecraft.bridge.common.model.SubServerRoster;

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
 * <p>The wire format is deliberately additive: every shape the Bukkit plugin and the older bridge
 * releases already send still goes out unchanged, and the newer fields are optional on the Admin
 * side. An Admin deployment that predates the sub-server dimension keeps working with a client that
 * does not send them.
 *
 * <pre>
 * POST .../players/{join|quit|afk/start|afk/end}   {"playerId":"..","playerName":"..","eventAt":123,"server":"fabric"}
 * POST .../players/snapshot                       {"observedAt":123,"players":[{"playerId":"..","playerName":".."}]}
 * POST .../players/snapshot                       {"observedAt":123,"servers":[{"name":"fabric","players":[]}]}
 * GET  .../players?page=1&amp;size=100
 * </pre>
 *
 * <p>{@code server} is the sub-server name the player was on (a Velocity backend name). It is
 * omitted when blank, which is exactly the legacy body: no sub-server dimension, so Admin files the
 * event under its {@code default} bucket.
 *
 * <p>{@code serverName} in the flat snapshot body is local metadata and is only added when
 * {@code api.include-server-name-in-snapshot} is switched on, because a strictly validating backend
 * would reject the extra field. The grouped form has its own {@code servers} array and never uses it.
 */
public final class YudreamApiClient {

    static final String PLUGIN_BASE = "/api/plugins/minecraft-server";
    static final String PLUGIN_PATH = PLUGIN_BASE + "/servers/";
    /** Address-matched topology route: no server id in the path. */
    static final String REPORT_TOPOLOGY_PATH = PLUGIN_BASE + "/report/topology";

    private final BridgeSettings settings;
    private final String userAgent;

    public YudreamApiClient(BridgeSettings settings, String userAgent) {
        this.settings = settings;
        this.userAgent = userAgent;
    }

    public HttpResult report(PlayerEventType type, PlayerEventPayload payload) throws IOException {
        return request("POST", serverUrl("/players/" + type.getRemotePath()), eventBody(payload));
    }

    public HttpResult snapshot(Collection<PlayerEventPayload> players, long observedAt, String serverName) throws IOException {
        return request("POST", serverUrl("/players/snapshot"), snapshotBody(players, observedAt, serverName));
    }

    /**
     * Reports every sub-server's roster in one snapshot.
     *
     * <p>This is the shape a proxy uses: each listed sub-server is reconciled on its own, so a
     * snapshot that only covers {@code paper} cannot close anyone on {@code fabric}. A listed
     * sub-server with an empty roster is meaningful and must be kept.
     */
    public HttpResult groupedSnapshot(Collection<SubServerRoster> servers, long observedAt) throws IOException {
        return request("POST", serverUrl("/players/snapshot"), groupedSnapshotBody(servers, observedAt));
    }

    public HttpResult players(int page, int size) throws IOException {
        String path = "/players?page=" + Math.max(page, 1) + "&size=" + Math.max(Math.min(size, 100), 1);
        return request("GET", serverUrl(path), null);
    }

    /**
     * Reports this proxy's downstream-server list bound to the configured Admin server id.
     *
     * <p>Used when {@code api.server-id} is set. An Admin deployment that exposes only the
     * address-matched route answers 404 here, which is why {@link #reportTopologyByAddress} exists.
     */
    public HttpResult reportTopology(String body) throws IOException {
        return request("POST", serverUrl("/topology"), body);
    }

    /**
     * Reports this proxy's downstream-server list without naming a server id, letting Admin match it
     * by the proxy's own addresses.
     *
     * <p>This is the route that makes "resolve the group server" a one-step install: the operator
     * configures Admin's base URL and API key and nothing else, instead of copying a server id out of
     * Admin into this file.
     */
    public HttpResult reportTopologyByAddress(String body) throws IOException {
        return request("POST", settings.getBaseUrl() + REPORT_TOPOLOGY_PATH, body);
    }

    /**
     * Serialises a topology report.
     *
     * @param addresses this proxy's own public addresses, used by the address-matched route
     */
    public String topologyBody(String proxy, String proxyVersion, Collection<String> addresses,
                               Collection<SubServerInfo> servers, long reportedAt) {
        JsonValue body = JsonValue.object();
        body.put("proxy", proxy == null ? "" : proxy);
        body.put("proxyVersion", proxyVersion == null ? "" : proxyVersion);
        body.put("reportedAt", reportedAt);
        JsonValue addressList = JsonValue.array();
        if (addresses != null) {
            for (String address : addresses) {
                if (address != null && !address.isEmpty()) {
                    addressList.add(JsonValue.of(address));
                }
            }
        }
        body.put("addresses", addressList);
        JsonValue serverList = JsonValue.array();
        if (servers != null) {
            for (SubServerInfo server : servers) {
                serverList.add(JsonValue.object()
                        .put("name", server.name())
                        .put("address", server.address() == null ? "" : server.address())
                        .put("online", server.online())
                        .put("sensor", server.sensor())
                        .put("defaultServer", server.defaultServer()));
            }
        }
        body.put("servers", serverList);
        return body.toString();
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
        // 空白即“没有子服维度”，此时整条报文与旧版一字不差。
        if (payload.serverName() != null && !payload.serverName().isEmpty()) {
            body.put("server", payload.serverName());
        }
        return body.toString();
    }

    public String snapshotBody(Collection<PlayerEventPayload> players, long observedAt, String serverName) {
        JsonValue body = JsonValue.object();
        body.put("observedAt", observedAt);
        if (settings.isIncludeServerNameInSnapshot() && serverName != null && !serverName.isEmpty()) {
            body.put("serverName", serverName);
        }
        body.put("players", playerArray(players));
        return body.toString();
    }

    /**
     * Serialises the grouped snapshot body:
     * {@code {"observedAt":123,"servers":[{"name":"fabric","players":[...]}]}}.
     *
     * <p>A sub-server with an empty roster is still emitted: that is how Admin learns nobody is left
     * there. When there is no sub-server at all the name is written as an empty string, which Admin
     * normalises into its {@code default} bucket.
     */
    public String groupedSnapshotBody(Collection<SubServerRoster> servers, long observedAt) {
        JsonValue body = JsonValue.object();
        body.put("observedAt", observedAt);
        JsonValue list = JsonValue.array();
        if (servers != null) {
            for (SubServerRoster roster : servers) {
                list.add(JsonValue.object()
                        .put("name", roster.name() == null ? "" : roster.name())
                        .put("players", playerArray(roster.players())));
            }
        }
        body.put("servers", list);
        return body.toString();
    }

    private static JsonValue playerArray(Collection<PlayerEventPayload> players) {
        JsonValue list = JsonValue.array();
        if (players == null) {
            return list;
        }
        for (PlayerEventPayload player : players) {
            list.add(JsonValue.object()
                    .put("playerId", player.playerId())
                    .put("playerName", player.playerName()));
        }
        return list;
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
