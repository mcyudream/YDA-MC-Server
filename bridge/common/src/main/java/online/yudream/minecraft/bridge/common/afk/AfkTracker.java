package online.yudream.minecraft.bridge.common.afk;

import online.yudream.minecraft.bridge.common.config.BridgeSettings;
import online.yudream.minecraft.bridge.common.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.common.model.PlayerEventType;
import online.yudream.minecraft.bridge.common.model.PlayerIdentity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Platform-agnostic AFK state machine.
 *
 * <p>A Velocity proxy cannot see chat, block movement or interactions by itself, so the activity
 * signals arrive from the backend Fabric sensor over the bridge channel; presence arrives from the
 * proxy. This class owns only the transition logic, which keeps it unit-testable without a server.
 */
public final class AfkTracker {

    /** Where AFK transitions go. The proxy wires this to its report queue. */
    public interface Sink {
        void submit(PlayerEventType type, PlayerEventPayload payload);
    }

    private final Sink sink;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<UUID, Entry>();

    public AfkTracker(Sink sink) {
        this.sink = sink;
    }

    public void markOnline(PlayerIdentity player, long now) {
        if (player == null) {
            return;
        }
        entries.put(player.uuid(), new Entry(now, false));
    }

    public void markOffline(UUID uuid) {
        if (uuid != null) {
            entries.remove(uuid);
        }
    }

    /** Records activity. Emits {@code AFK_END} when the player was previously marked AFK. */
    public void markActive(PlayerIdentity player, String serverName, long now, BridgeSettings settings) {
        if (player == null || settings == null || !settings.isAfkEnabled()) {
            return;
        }
        Entry previous = entries.get(player.uuid());
        if (previous == null) {
            entries.put(player.uuid(), new Entry(now, false));
            return;
        }
        if (previous.afk) {
            sink.submit(PlayerEventType.AFK_END, PlayerEventPayload.of(player, now, serverName));
        }
        entries.put(player.uuid(), new Entry(now, false));
    }

    public boolean isAfk(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry != null && entry.afk;
    }

    /**
     * Emits {@code AFK_START} for every online player idle past the configured timeout.
     *
     * @return the number of transitions emitted, so the caller can log or assert in tests
     */
    public int tick(Collection<PlayerIdentity> onlinePlayers, String serverName, long now, BridgeSettings settings) {
        if (settings == null || !settings.isAfkEnabled()) {
            return 0;
        }
        int transitions = 0;
        for (PlayerIdentity player : onlinePlayers) {
            Entry entry = entries.get(player.uuid());
            if (entry == null) {
                entries.put(player.uuid(), new Entry(now, false));
                continue;
            }
            if (!entry.afk && now - entry.lastActiveAt >= settings.getAfkTimeoutMs()) {
                entries.put(player.uuid(), new Entry(entry.lastActiveAt, true));
                sink.submit(PlayerEventType.AFK_START, PlayerEventPayload.of(player, now, serverName));
                transitions++;
            }
        }
        return transitions;
    }

    /** Drops a player that is no longer accounted for by the caller's presence view. */
    public void retainOnly(Collection<UUID> onlineUuids) {
        entries.keySet().retainAll(onlineUuids);
    }

    public void reset() {
        entries.clear();
    }

    public int trackedPlayers() {
        return entries.size();
    }

    private static final class Entry {

        private final long lastActiveAt;
        private final boolean afk;

        private Entry(long lastActiveAt, boolean afk) {
            this.lastActiveAt = lastActiveAt;
            this.afk = afk;
        }
    }
}
