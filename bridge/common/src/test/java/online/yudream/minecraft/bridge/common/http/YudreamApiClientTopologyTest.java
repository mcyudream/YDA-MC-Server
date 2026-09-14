package online.yudream.minecraft.bridge.common.http;

import online.yudream.minecraft.bridge.common.config.BridgeSettings;
import online.yudream.minecraft.bridge.common.json.JsonValue;
import online.yudream.minecraft.bridge.common.model.SubServerInfo;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the topology report the bridge sends.
 *
 * <p>The Admin plugin has a matching test built from this exact payload, so a rename on either side
 * breaks a test instead of silently dropping a field.
 *
 * <p>This also guards what is deliberately NOT sent: a topology report carries no player identifiers,
 * so the address-matched route stays usable on a proxy that never has a server id configured.
 */
public class YudreamApiClientTopologyTest {

    private static YudreamApiClient client() {
        return new YudreamApiClient(BridgeSettings.defaults(), "yudream-bridge-test");
    }

    @Test
    public void emitsTheFieldNamesTheAdminPluginReads() {
        String body = client().topologyBody("velocity", "3.5.0",
                List.of("play.example.com", "play.example.com:25565"),
                List.of(
                        new SubServerInfo("lobby", "127.0.0.1:25566", 3, true, true),
                        new SubServerInfo("survival", "127.0.0.1:25567", 0, false, false)),
                1757850000000L);

        JsonValue root = JsonValue.parse(body);
        assertEquals("velocity", root.getOr("proxy", JsonValue.of("")).asString(""));
        assertEquals("3.5.0", root.getOr("proxyVersion", JsonValue.of("")).asString(""));
        assertEquals(1757850000000L, root.getOr("reportedAt", JsonValue.of(0L)).asLong(0L));

        List<JsonValue> addresses = root.getOr("addresses", JsonValue.array()).items();
        assertEquals(2, addresses.size());
        assertEquals("play.example.com", addresses.get(0).asString(""));

        List<JsonValue> servers = root.getOr("servers", JsonValue.array()).items();
        assertEquals(2, servers.size());
        JsonValue lobby = servers.get(0);
        assertEquals("lobby", lobby.getOr("name", JsonValue.of("")).asString(""));
        assertEquals("127.0.0.1:25566", lobby.getOr("address", JsonValue.of("")).asString(""));
        assertEquals(3, lobby.getOr("online", JsonValue.of(0)).asInt(0));
        assertTrue(lobby.getOr("sensor", JsonValue.of(false)).asBoolean(false));
        assertTrue(lobby.getOr("defaultServer", JsonValue.of(false)).asBoolean(false));
        assertFalse(servers.get(1).getOr("sensor", JsonValue.of(false)).asBoolean(false));
    }

    @Test
    public void toleratesAProxyWithNoAddressAndNoServers() {
        String body = client().topologyBody("velocity", null, Collections.emptyList(),
                Collections.emptyList(), 0L);
        JsonValue root = JsonValue.parse(body);
        assertEquals("", root.getOr("proxyVersion", JsonValue.of("x")).asString("x"));
        assertEquals(0, root.getOr("addresses", JsonValue.array()).items().size());
        assertEquals(0, root.getOr("servers", JsonValue.array()).items().size());
    }

    @Test
    public void carriesNoPlayerIdentifiers() {
        String body = client().topologyBody("velocity", "3.5.0", List.of("play.example.com"),
                List.of(new SubServerInfo("lobby", "127.0.0.1:25566", 3, true, true)), 1L);
        assertFalse(body.contains("playerId"));
        assertFalse(body.contains("playerName"));
        assertFalse(body.contains("uuid"));
    }

    @Test
    public void usesTheRoutesTheAdminPluginExposes() {
        // The Admin plugin registers these two paths in MinecraftServerReportController; a rename
        // there has to be matched here or the report starts answering 404.
        assertEquals("/api/plugins/minecraft-server/report/topology", YudreamApiClient.REPORT_TOPOLOGY_PATH);
        assertEquals("/api/plugins/minecraft-server/servers/", YudreamApiClient.PLUGIN_PATH);
    }
}
