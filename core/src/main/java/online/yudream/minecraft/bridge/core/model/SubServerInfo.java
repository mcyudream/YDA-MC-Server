package online.yudream.minecraft.bridge.core.model;

/**
 * One downstream server behind the proxy, as the bridge reports it.
 */
public final class SubServerInfo {

    private final String name;
    private final String address;
    private final int online;
    private final boolean sensor;
    private final boolean defaultServer;

    public SubServerInfo(
            String name,
            String address,
            int online,
            boolean sensor,
            boolean defaultServer
    ) {
        this.name = name;
        this.address = address;
        this.online = online;
        this.sensor = sensor;
        this.defaultServer = defaultServer;
    }

    /** Velocity's server name, the same string the sensors report in their events. */
    public String name() {
        return name;
    }

    /** The backend address Velocity was configured with. */
    public String address() {
        return address;
    }

    /** Players currently on that backend. */
    public int online() {
        return online;
    }

    /** Whether a YuDream sensor has said hello on that backend. */
    public boolean sensor() {
        return sensor;
    }

    /** Whether Velocity falls back to this one. */
    public boolean defaultServer() {
        return defaultServer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SubServerInfo)) {
            return false;
        }
        SubServerInfo other = (SubServerInfo) o;
        return online == other.online
                && sensor == other.sensor
                && defaultServer == other.defaultServer
                && (name == null ? other.name == null : name.equals(other.name))
                && (address == null ? other.address == null : address.equals(other.address));
    }

    @Override
    public int hashCode() {
        int result = name == null ? 0 : name.hashCode();
        result = 31 * result + (address == null ? 0 : address.hashCode());
        result = 31 * result + online;
        result = 31 * result + (sensor ? 1 : 0);
        result = 31 * result + (defaultServer ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "SubServerInfo[name=" + name + ", address=" + address + ", online=" + online
                + ", sensor=" + sensor + ", defaultServer=" + defaultServer + "]";
    }
}
