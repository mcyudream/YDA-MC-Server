package online.yudream.minecraft.bridge.velocity.presence;

import online.yudream.minecraft.bridge.common.model.PlayerIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The proxy's own view of which backend each player is on.
 *
 * <p>This is the authoritative source for join and quit reports. A backend sensor can be missing,
 * restarted or unable to reach the proxy, and presence would still be right, because Velocity
 * raises {@code ServerConnectedEvent} and {@code DisconnectEvent} for every transition.
 */
public final class ProxyPresence {

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<UUID, Entry>();

    /** Records the backend a player is now on. */
    public void connected(PlayerIdentity identity, String serverName) {
        entries.put(identity.uuid(), new Entry(identity, serverName));
    }

    public void disconnect(UUID uuid) {
        entries.remove(uuid);
    }

    public boolean knows(UUID uuid) {
        return entries.containsKey(uuid);
    }

    /** The backend a player is currently on, or {@code null} when the proxy has no record. */
    public String serverOf(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry == null ? null : entry.serverName;
    }

    public PlayerIdentity identityOf(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry == null ? null : entry.identity;
    }

    /** Every player currently on the given backend, in a stable order. */
    public List<PlayerIdentity> playersOn(String serverName) {
        List<PlayerIdentity> players = new ArrayList<PlayerIdentity>();
        if (serverName == null || serverName.isEmpty()) {
            return players;
        }
        for (Entry entry : entries.values()) {
            if (serverName.equals(entry.serverName)) {
                players.add(entry.identity);
            }
        }
        players.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return players;
    }

    public int countOn(String serverName) {
        int count = 0;
        if (serverName == null || serverName.isEmpty()) {
            return 0;
        }
        for (Entry entry : entries.values()) {
            if (serverName.equals(entry.serverName)) {
                count++;
            }
        }
        return count;
    }

    public void clear() {
        entries.clear();
    }

    private static final class Entry {

        private final PlayerIdentity identity;
        private final String serverName;

        private Entry(PlayerIdentity identity, String serverName) {
            this.identity = identity;
            this.serverName = serverName;
        }
    }
}
