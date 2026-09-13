package online.yudream.minecraft.bridge.fabric;

import online.yudream.minecraft.bridge.common.config.ConfigFile;
import online.yudream.minecraft.bridge.common.log.LogSink;
import online.yudream.minecraft.bridge.common.protocol.BridgeMessage;
import online.yudream.minecraft.bridge.fabric.config.FabricConfigTemplate;
import online.yudream.minecraft.bridge.fabric.config.FabricSettings;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The sensor's runtime state.
 *
 * <p>Kept free of Minecraft types so config handling and the proxy-link bookkeeping can be reasoned
 * about (and unit tested) without a server; the platform wiring lives in {@link FabricSensor}.
 */
public final class FabricBridge {

    private final Path configPath;
    private final LogSink log;
    private volatile FabricSettings settings;

    private volatile long lastAckAt;
    private volatile String proxyVersion = "";
    private volatile String proxyTarget = "";
    private volatile boolean proxyAccepting;
    private volatile int proxyProtocolVersion = -1;

    public FabricBridge(FabricSettings settings, Path configPath, LogSink log) {
        this.settings = settings;
        this.configPath = configPath;
        this.log = log;
    }

    public void start() {
        if (!settings.isEnabled()) {
            log.warn("The YuDream sensor is disabled by config (enabled=false); nothing will be forwarded.");
        } else {
            log.info("YuDream sensor ready. Player activity is forwarded to the Velocity proxy; "
                    + "this server never contacts YuDream Admin directly.");
        }
    }

    /** Re-reads the config file. */
    public synchronized void reload() {
        try {
            ConfigFile file = ConfigFile.loadOrCreate(configPath, FabricConfigTemplate.render());
            FabricSettings loaded = FabricSettings.from(file);
            FabricSettings.applyDefaults(file, loaded);
            file.save(configPath);
            settings = FabricSettings.from(file);
            log.info("YuDream sensor config reloaded from " + configPath + ".");
        } catch (IOException e) {
            log.warn("Could not reload " + configPath + ": " + e.getMessage(), e);
        }
    }

    /** Records the proxy's reply to a hello. */
    public void onHelloAck(BridgeMessage.HelloAck ack) {
        this.lastAckAt = System.currentTimeMillis();
        this.proxyVersion = ack.proxyVersion();
        this.proxyTarget = ack.targetServer();
        this.proxyAccepting = ack.accepting();
        this.proxyProtocolVersion = ack.protocolVersion();
    }

    public FabricSettings settings() {
        return settings;
    }

    public Path configPath() {
        return configPath;
    }

    public LogSink log() {
        return log;
    }

    /** One line for {@code /yudreammc status} on the backend. */
    public String describeProxyLink() {
        long lastAck = lastAckAt;
        if (lastAck <= 0L) {
            return "YuDream sensor " + YudreamFabricMod.VERSION
                    + ": no reply from the Velocity proxy yet. A proxy only becomes reachable once a player"
                    + " has connected to it through this backend.";
        }
        long ageMs = System.currentTimeMillis() - lastAck;
        return "YuDream sensor " + YudreamFabricMod.VERSION
                + ": proxy " + (proxyVersion.isEmpty() ? "?" : proxyVersion)
                + ", protocol=" + proxyProtocolVersion
                + ", accepting=" + proxyAccepting
                + ", reported downstream=" + (proxyTarget.isEmpty() ? "<unset>" : proxyTarget)
                + ", lastReplyMsAgo=" + ageMs
                + " (config " + configPath + ")";
    }
}
