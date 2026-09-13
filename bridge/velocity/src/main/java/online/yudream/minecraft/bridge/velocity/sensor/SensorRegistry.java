package online.yudream.minecraft.bridge.velocity.sensor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which downstream servers actually run the companion Fabric sensor.
 *
 * <p>Minecraft plugin messages only travel over a player connection, so a backend can only announce
 * itself once somebody is on it. Confirmation is therefore sticky for the lifetime of the proxy: a
 * backend that has said hello once is treated as instrumented even while it is empty, which is what
 * lets an empty target still receive its shutdown snapshot.
 */
public final class SensorRegistry {

    private final Map<String, Sensor> sensors = new ConcurrentHashMap<String, Sensor>();

    /** Records a hello from a backend. Returns true when this is the first one from that backend. */
    public boolean hello(String serverName, String modVersion, int protocolVersion, int reportedPlayers, long now) {
        if (serverName == null || serverName.isEmpty()) {
            return false;
        }
        Sensor sensor = sensors.get(serverName);
        if (sensor == null) {
            sensor = new Sensor();
            sensors.put(serverName, sensor);
        }
        boolean first = !sensor.confirmed;
        sensor.confirmed = true;
        sensor.modVersion = modVersion;
        sensor.protocolVersion = protocolVersion;
        sensor.reportedPlayers = reportedPlayers;
        sensor.lastHelloAt = now;
        return first;
    }

    public boolean isConfirmed(String serverName) {
        Sensor sensor = serverName == null ? null : sensors.get(serverName);
        return sensor != null && sensor.confirmed;
    }

    public long lastHelloAt(String serverName) {
        Sensor sensor = serverName == null ? null : sensors.get(serverName);
        return sensor == null ? 0L : sensor.lastHelloAt;
    }

    public String modVersion(String serverName) {
        Sensor sensor = serverName == null ? null : sensors.get(serverName);
        return sensor == null ? null : sensor.modVersion;
    }

    public int reportedPlayers(String serverName) {
        Sensor sensor = serverName == null ? null : sensors.get(serverName);
        return sensor == null ? 0 : sensor.reportedPlayers;
    }

    /** True when a confirmed sensor has gone quiet for longer than the timeout. */
    public boolean isStale(String serverName, long timeoutMs, long now) {
        Sensor sensor = serverName == null ? null : sensors.get(serverName);
        if (sensor == null || !sensor.confirmed || sensor.lastHelloAt <= 0L) {
            return false;
        }
        return now - sensor.lastHelloAt > timeoutMs;
    }

    /** One summary line per backend that has ever said hello. */
    public List<String> describe() {
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<String, Sensor> entry : sensors.entrySet()) {
            Sensor sensor = entry.getValue();
            lines.add(entry.getKey()
                    + ": confirmed=" + sensor.confirmed
                    + ", mod=" + (sensor.modVersion == null ? "?" : sensor.modVersion)
                    + ", players=" + sensor.reportedPlayers
                    + ", lastHelloMsAgo=" + (sensor.lastHelloAt <= 0L ? "never" : (System.currentTimeMillis() - sensor.lastHelloAt)));
        }
        lines.sort(String::compareTo);
        return lines;
    }

    public void clear() {
        sensors.clear();
    }

    private static final class Sensor {

        private volatile boolean confirmed;
        private volatile String modVersion;
        private volatile int protocolVersion;
        private volatile int reportedPlayers;
        private volatile long lastHelloAt;
    }
}
