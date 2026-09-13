package online.yudream.minecraft.bridge.common.afk;

import online.yudream.minecraft.bridge.common.config.BridgeSettings;
import online.yudream.minecraft.bridge.common.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.common.model.PlayerEventType;
import online.yudream.minecraft.bridge.common.model.PlayerIdentity;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AfkTrackerTest {

    private static final PlayerIdentity PLAYER =
            new PlayerIdentity(UUID.fromString("11111111-2222-3333-4444-555555555555"), "Steve");
    private static final String SERVER = "survival";

    private final List<String> emitted = new ArrayList<String>();
    private final AfkTracker tracker = new AfkTracker((type, payload) -> emitted.add(type + ":" + payload.playerName()));

    private static BridgeSettings afkSettings(long timeoutMs, long checkIntervalMs, boolean enabled) {
        return BridgeSettings.builder()
                .afkEnabled(enabled)
                .afkTimeoutMs(timeoutMs)
                .afkCheckIntervalMs(checkIntervalMs)
                .build();
    }

    @Test
    public void staysSilentBeforeTheTimeout() {
        BridgeSettings settings = afkSettings(300_000L, 30_000L, true);
        tracker.markOnline(PLAYER, 1_000L);
        assertEquals(0, tracker.tick(List.of(PLAYER), SERVER, 1_000L + 299_999L, settings));
        assertTrue(emitted.isEmpty());
    }

    @Test
    public void reportsAfkStartOnceAfterTheTimeout() {
        BridgeSettings settings = afkSettings(300_000L, 30_000L, true);
        tracker.markOnline(PLAYER, 1_000L);
        long now = 1_000L + 300_000L;
        assertEquals(1, tracker.tick(List.of(PLAYER), SERVER, now, settings));
        assertTrue(tracker.isAfk(PLAYER.uuid()));
        assertEquals(List.of("AFK_START:Steve"), emitted);

        // A second tick must not repeat the transition.
        assertEquals(0, tracker.tick(List.of(PLAYER), SERVER, now + 30_000L, settings));
        assertEquals(1, emitted.size());
    }

    @Test
    public void reportsAfkEndWhenActivityResumes() {
        BridgeSettings settings = afkSettings(300_000L, 30_000L, true);
        tracker.markOnline(PLAYER, 1_000L);
        tracker.tick(List.of(PLAYER), SERVER, 301_000L, settings);
        tracker.markActive(PLAYER, SERVER, 400_000L, settings);
        assertEquals(List.of("AFK_START:Steve", "AFK_END:Steve"), emitted);
        assertFalse(tracker.isAfk(PLAYER.uuid()));
    }

    @Test
    public void activityWhileAwakeEmitsNothing() {
        BridgeSettings settings = afkSettings(300_000L, 30_000L, true);
        tracker.markOnline(PLAYER, 1_000L);
        tracker.markActive(PLAYER, SERVER, 2_000L, settings);
        assertTrue(emitted.isEmpty());
    }

    @Test
    public void unknownPlayersAreSeededInsteadOfReported() {
        BridgeSettings settings = afkSettings(300_000L, 30_000L, true);
        assertEquals(0, tracker.tick(List.of(PLAYER), SERVER, 500_000L, settings));
        assertEquals(1, tracker.trackedPlayers());
        assertFalse(tracker.isAfk(PLAYER.uuid()));
    }

    @Test
    public void doesNothingWhenAfkIsDisabled() {
        BridgeSettings settings = afkSettings(300_000L, 30_000L, false);
        tracker.markOnline(PLAYER, 1_000L);
        assertEquals(0, tracker.tick(List.of(PLAYER), SERVER, 10_000_000L, settings));
        tracker.markActive(PLAYER, SERVER, 10_000_001L, settings);
        assertTrue(emitted.isEmpty());
    }

    @Test
    public void offlinePlayersAreForgotten() {
        tracker.markOnline(PLAYER, 1_000L);
        tracker.markOffline(PLAYER.uuid());
        assertEquals(0, tracker.trackedPlayers());
    }

    @Test
    public void retainOnlyDropsPlayersTheCallerNoLongerSees() {
        tracker.markOnline(PLAYER, 1_000L);
        tracker.retainOnly(List.of());
        assertEquals(0, tracker.trackedPlayers());
    }

    @Test
    public void payloadsCarryTheServerName() {
        BridgeSettings settings = afkSettings(1L, 1L, true);
        List<String> transitions = new ArrayList<String>();
        List<PlayerEventPayload> payloads = new ArrayList<PlayerEventPayload>();
        AfkTracker capturing = new AfkTracker((type, payload) -> {
            transitions.add(type.getRemotePath());
            payloads.add(payload);
        });
        capturing.markOnline(PLAYER, 0L);
        capturing.tick(List.of(PLAYER), SERVER, 10L, settings);
        assertEquals(List.of("afk/start"), transitions);
        assertEquals(1, payloads.size());
        assertEquals("survival", payloads.get(0).serverName());
        assertEquals(PLAYER.uuidString(), payloads.get(0).playerId());
    }
}
