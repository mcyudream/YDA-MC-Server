package online.yudream.minecraft.bridge.velocity.config;

import online.yudream.minecraft.bridge.common.config.BridgeSettings;
import online.yudream.minecraft.bridge.common.config.ConfigFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The proxy-side configuration: everything in {@link BridgeSettings} plus the pieces that only make
 * sense on a Velocity proxy.
 *
 * <p>Every downstream server is reported, each as its own sub-server, so {@code target.server} is
 * <b>not</b> a reporting switch any more: it only marks the default / login-entry backend that
 * {@code /yudreammc target} edits and that a login hint reads. {@code target.require-sensor} is
 * applied per sub-server instead of once for the whole proxy.
 */
public final class VelocitySettings {

    private final BridgeSettings bridge;
    private final String targetServer;
    private final boolean requireSensor;
    private final long sensorTimeoutSeconds;
    private final int snapshotIntervalSeconds;
    private final int probeIntervalSeconds;
    private final boolean topologyEnabled;
    private final int topologyIntervalSeconds;
    private final List<String> topologyAddresses;
    private final double moveActivityMinBlocks;
    private final List<String> admins;
    private final String commandPermission;
    private final List<String> blockedLoginNames;

    private VelocitySettings(Builder builder) {
        this.bridge = builder.bridge;
        this.targetServer = builder.targetServer == null ? "" : builder.targetServer.trim();
        this.requireSensor = builder.requireSensor;
        this.sensorTimeoutSeconds = Math.max(builder.sensorTimeoutSeconds, 5L);
        this.snapshotIntervalSeconds = (int) Math.max(builder.snapshotIntervalSeconds, 5L);
        this.probeIntervalSeconds = (int) Math.max(builder.probeIntervalSeconds, 5L);
        this.topologyEnabled = builder.topologyEnabled;
        this.topologyIntervalSeconds = (int) Math.max(builder.topologyIntervalSeconds, 10L);
        this.topologyAddresses = Collections.unmodifiableList(new ArrayList<String>(builder.topologyAddresses));
        this.moveActivityMinBlocks = builder.moveActivityMinBlocks <= 0 ? 1.0D : builder.moveActivityMinBlocks;
        this.admins = Collections.unmodifiableList(new ArrayList<String>(builder.admins));
        this.commandPermission = builder.commandPermission == null || builder.commandPermission.trim().isEmpty()
                ? "yudreammc.admin"
                : builder.commandPermission.trim();
        this.blockedLoginNames = Collections.unmodifiableList(new ArrayList<String>(builder.blockedLoginNames));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static VelocitySettings defaults() {
        return builder().build();
    }

    public static VelocitySettings from(ConfigFile config) {
        VelocitySettings fallback = defaults();
        String rawMoveBlocks = config.get("target.move-activity-min-blocks", "");
        return builder()
                .bridge(BridgeSettings.from(config))
                .targetServer(config.get("target.server", fallback.targetServer))
                .requireSensor(config.getBoolean("target.require-sensor", fallback.requireSensor))
                .sensorTimeoutSeconds(config.getLong("target.sensor-timeout-seconds", fallback.sensorTimeoutSeconds))
                .snapshotIntervalSeconds(config.getInt("snapshot.interval-seconds", fallback.snapshotIntervalSeconds))
                .probeIntervalSeconds(config.getInt("target.probe-interval-seconds", fallback.probeIntervalSeconds))
                .topologyEnabled(config.getBoolean("topology.enabled", fallback.topologyEnabled))
                .topologyIntervalSeconds(config.getInt("topology.interval-seconds", fallback.topologyIntervalSeconds))
                .topologyAddresses(config.getList("topology.addresses"))
                .moveActivityMinBlocks(rawMoveBlocks.isEmpty()
                        ? fallback.moveActivityMinBlocks
                        : parseDouble(rawMoveBlocks, fallback.moveActivityMinBlocks))
                .admins(config.getList("command.admins"))
                .commandPermission(config.get("command.permission", fallback.commandPermission))
                .blockedLoginNames(config.getList("login.blocked-names"))
                .build();
    }

    /** Fills in any proxy key the file does not define yet. */
    public static void applyDefaults(ConfigFile config, VelocitySettings settings) {
        BridgeSettings.applyDefaults(config, settings.bridge);
        config.setIfAbsent("target.server", settings.targetServer);
        config.setIfAbsent("target.require-sensor", settings.requireSensor);
        config.setIfAbsent("target.sensor-timeout-seconds", settings.sensorTimeoutSeconds);
        config.setIfAbsent("target.probe-interval-seconds", settings.probeIntervalSeconds);
        config.setIfAbsent("target.move-activity-min-blocks", settings.moveActivityMinBlocks);
        config.setIfAbsent("snapshot.interval-seconds", settings.snapshotIntervalSeconds);
        config.setIfAbsent("topology.enabled", settings.topologyEnabled);
        config.setIfAbsent("topology.interval-seconds", settings.topologyIntervalSeconds);
        config.setIfAbsent("topology.addresses", String.join(",", settings.topologyAddresses));
        config.setIfAbsent("command.permission", settings.commandPermission);
        config.setIfAbsent("command.admins", String.join(",", settings.admins));
        config.setIfAbsent("login.blocked-names", String.join(",", settings.blockedLoginNames));
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public BridgeSettings bridge() {
        return bridge;
    }

    /**
     * The default / login-entry downstream server name, or an empty string when none is marked.
     *
     * <p>Reporting does not depend on it: all downstream servers are always reported.
     */
    public String targetServer() {
        return targetServer;
    }

    public boolean hasTarget() {
        return !targetServer.isEmpty();
    }

    /**
     * When true, a sub-server is not reported until <em>its own</em> Fabric sensor has said hello.
     *
     * <p>Applied per sub-server, never once for the whole proxy: a backend that lost the mod stops
     * reporting while every instrumented backend keeps reporting normally.
     */
    public boolean requireSensor() {
        return requireSensor;
    }

    public long sensorTimeoutSeconds() {
        return sensorTimeoutSeconds;
    }

    public int snapshotIntervalSeconds() {
        return snapshotIntervalSeconds;
    }

    public int probeIntervalSeconds() {
        return probeIntervalSeconds;
    }

    /**
     * Whether this proxy periodically reports its downstream-server list to YuDream Admin.
     *
     * <p>On by default: a proxy's Server List Ping does not expose its backends, so the bridge is the
     * only component that can tell Admin what a group server contains.
     */
    public boolean topologyEnabled() {
        return topologyEnabled;
    }

    public int topologyIntervalSeconds() {
        return topologyIntervalSeconds;
    }

    /**
     * The proxy's own public addresses, used to match this report to an Admin server entry.
     *
     * <p>Velocity only exposes its bind address, which is usually {@code 0.0.0.0} and therefore
     * useless for matching. Leaving this empty falls back to the bind address; set it to the
     * hostname players actually connect to when that differs, which is the normal case behind NAT.
     */
    public List<String> topologyAddresses() {
        return topologyAddresses;
    }

    public double moveActivityMinBlocks() {
        return moveActivityMinBlocks;
    }

    public List<String> admins() {
        return admins;
    }

    public String commandPermission() {
        return commandPermission;
    }

    /**
     * Account names refused before Velocity asks the authentication server about them.
     *
     * <p>Velocity authenticates first and only then fires {@code LoginEvent}, so a client that
     * retries every few seconds makes one Yggdrasil call per attempt. Left alone it burns the API's
     * rate limit for this IP, and real players then see "too many login attempts". Rejecting at
     * {@code PreLoginEvent} costs nothing.
     */
    public List<String> blockedLoginNames() {
        return blockedLoginNames;
    }

    /** Returns a copy whose bridge settings and target have been replaced. */
    public VelocitySettings with(BridgeSettings newBridge, String newTarget) {
        return builder()
                .bridge(newBridge)
                .targetServer(newTarget)
                .requireSensor(requireSensor)
                .sensorTimeoutSeconds(sensorTimeoutSeconds)
                .snapshotIntervalSeconds(snapshotIntervalSeconds)
                .probeIntervalSeconds(probeIntervalSeconds)
                .topologyEnabled(topologyEnabled)
                .topologyIntervalSeconds(topologyIntervalSeconds)
                .topologyAddresses(topologyAddresses)
                .moveActivityMinBlocks(moveActivityMinBlocks)
                .admins(admins)
                .commandPermission(commandPermission)
                .blockedLoginNames(blockedLoginNames)
                .build();
    }

    /** Mutable builder; only {@link VelocitySettings} produces an immutable instance. */
    public static final class Builder {

        private BridgeSettings bridge = BridgeSettings.defaults();
        private String targetServer = "";
        private boolean requireSensor = true;
        private long sensorTimeoutSeconds = 90L;
        private int snapshotIntervalSeconds = 60;
        private int probeIntervalSeconds = 30;
        private boolean topologyEnabled = true;
        private int topologyIntervalSeconds = 60;
        private List<String> topologyAddresses = new ArrayList<String>();
        private double moveActivityMinBlocks = 1.0D;
        private List<String> admins = new ArrayList<String>();
        private String commandPermission = "yudreammc.admin";
        private List<String> blockedLoginNames = new ArrayList<String>();

        public Builder bridge(BridgeSettings value) {
            this.bridge = value;
            return this;
        }

        public Builder targetServer(String value) {
            this.targetServer = value;
            return this;
        }

        public Builder requireSensor(boolean value) {
            this.requireSensor = value;
            return this;
        }

        public Builder sensorTimeoutSeconds(long value) {
            this.sensorTimeoutSeconds = value;
            return this;
        }

        public Builder snapshotIntervalSeconds(int value) {
            this.snapshotIntervalSeconds = value;
            return this;
        }

        public Builder probeIntervalSeconds(int value) {
            this.probeIntervalSeconds = value;
            return this;
        }

        public Builder topologyEnabled(boolean value) {
            this.topologyEnabled = value;
            return this;
        }

        public Builder topologyIntervalSeconds(int value) {
            this.topologyIntervalSeconds = value;
            return this;
        }

        public Builder topologyAddresses(List<String> value) {
            this.topologyAddresses = value == null ? new ArrayList<String>() : new ArrayList<String>(value);
            return this;
        }

        public Builder moveActivityMinBlocks(double value) {
            this.moveActivityMinBlocks = value;
            return this;
        }

        public Builder admins(List<String> value) {
            this.admins = value == null ? new ArrayList<String>() : new ArrayList<String>(value);
            return this;
        }

        public Builder commandPermission(String value) {
            this.commandPermission = value;
            return this;
        }

        public Builder blockedLoginNames(List<String> value) {
            this.blockedLoginNames = value == null ? new ArrayList<String>() : new ArrayList<String>(value);
            return this;
        }

        public VelocitySettings build() {
            return new VelocitySettings(this);
        }
    }
}
