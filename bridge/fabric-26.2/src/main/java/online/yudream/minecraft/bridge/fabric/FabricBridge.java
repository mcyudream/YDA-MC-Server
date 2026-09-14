package online.yudream.minecraft.bridge.fabric;

import online.yudream.minecraft.bridge.common.config.BridgeMode;
import online.yudream.minecraft.bridge.common.config.ConfigFile;
import online.yudream.minecraft.bridge.common.log.LogSink;
import online.yudream.minecraft.bridge.common.protocol.BridgeMessage;
import online.yudream.minecraft.bridge.fabric.config.FabricConfigTemplate;
import online.yudream.minecraft.bridge.fabric.config.FabricSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

/**
 * The bridge's runtime state.
 *
 * <p>Kept free of Minecraft types so config handling, the mode decision and the proxy-link
 * bookkeeping can be reasoned about (and unit tested) without a server; the platform wiring lives in
 * {@link FabricSensor}.
 *
 * <p>The mode decides which half is live. In {@code downstream} mode this object only tracks the link
 * to the proxy, and {@link #reporter()} is {@code null}. In {@code standalone} mode there is no proxy
 * and {@link #reporter()} owns the Admin pipeline instead.
 */
public final class FabricBridge {

    private final Path configPath;
    private final Path dataDirectory;
    private final LogSink log;
    private volatile FabricSettings settings;
    private volatile FabricReporter reporter;

    private volatile long lastAckAt;
    private volatile String proxyVersion = "";
    private volatile String proxyTarget = "";
    private volatile boolean proxyAccepting;
    private volatile int proxyProtocolVersion = -1;

    public FabricBridge(FabricSettings settings, Path configPath, Path dataDirectory, LogSink log) {
        this.settings = settings;
        this.configPath = configPath;
        this.dataDirectory = dataDirectory;
        this.log = log;
    }

    public void start() {
        applyMode(settings);
    }

    /**
     * Re-reads the config file and reconfigures whichever half the mode selects.
     *
     * <p>Switching modes at runtime cannot unregister the {@code yudream:bridge} payload type, because
     * Fabric has no API for that. The channel therefore stays registered while standalone is active;
     * {@link FabricSensor} simply stops sending on it, which is what actually matters.
     */
    public synchronized void reload() {
        try {
            ConfigFile file = ConfigFile.loadOrCreate(configPath, FabricConfigTemplate.render());
            FabricSettings loaded = FabricSettings.from(file);
            FabricSettings.applyDefaults(file, loaded);
            file.save(configPath);
            settings = FabricSettings.from(file);
            log.info("YuDream bridge config reloaded from " + configPath + ".");
        } catch (IOException e) {
            log.warn("Could not reload " + configPath + ": " + e.getMessage(), e);
            return;
        }
        applyMode(settings);
    }

    /** Brings the live half in line with {@code loaded}. */
    private void applyMode(FabricSettings loaded) {
        if (loaded.isStandalone()) {
            FabricReporter current = reporter;
            if (current == null) {
                current = new FabricReporter(loaded, dataDirectory, log);
                reporter = current;
            }
            // start() owns every log line about the standalone pipeline, including the warnings for a
            // disabled or half-configured server.
            current.start(loaded);
            return;
        }
        // Downstream: the proxy uploads, so drop any local pipeline instead of leaving it running in
        // parallel with a proxy that also reports this server's players.
        FabricReporter current = reporter;
        if (current != null) {
            current.shutdown(Collections.emptyList());
            reporter = null;
        }
        if (!loaded.isEnabled()) {
            log.warn("The YuDream sensor is disabled by config (enabled=false); nothing will be forwarded.");
        } else {
            log.info("YuDream sensor ready. Player activity is forwarded to the Velocity proxy; "
                    + "this server never contacts YuDream Admin directly.");
        }
    }

    /**
     * Switches mode, writes it back to the config file and reconfigures whichever half is now live.
     *
     * <p>The file is patched through {@link ConfigFile} rather than rewritten, so the comments in the
     * shipped config survive — the same reason the proxy plugin patches its own file.
     *
     * @return true when the new mode was written and applied
     */
    public synchronized boolean setMode(BridgeMode mode) {
        try {
            ConfigFile file = ConfigFile.load(configPath);
            file.set("mode", mode.getId());
            file.save(configPath);
        } catch (IOException e) {
            log.warn("Could not write mode=" + mode.getId() + " to " + configPath + ": " + e.getMessage(), e);
            return false;
        }
        reload();
        return true;
    }

    /** Records the proxy's reply to a hello. */
    public void onHelloAck(BridgeMessage.HelloAck ack) {        this.lastAckAt = System.currentTimeMillis();
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

    /** True when this server reports to Admin itself instead of forwarding to a proxy. */
    public boolean isStandalone() {
        return settings.isStandalone();
    }

    /** The standalone reporting pipeline, or {@code null} while the downstream sensor is active. */
    public FabricReporter reporter() {
        return reporter;
    }

    /** One line for {@code /yudreammc status}. */
    public String describeStatus() {
        FabricReporter current = reporter;
        if (current != null) {
            return "YuDream bridge " + YudreamFabricMod.VERSION + " [standalone]: " + current.describeTarget()
                    + " (config " + configPath + ")";
        }
        long lastAck = lastAckAt;
        if (lastAck <= 0L) {
            return "YuDream bridge " + YudreamFabricMod.VERSION
                    + " [downstream]: no reply from the Velocity proxy yet. A proxy only becomes reachable"
                    + " once a player has connected to it through this backend.";
        }
        long ageMs = System.currentTimeMillis() - lastAck;
        return "YuDream bridge " + YudreamFabricMod.VERSION
                + " [downstream]: proxy " + (proxyVersion.isEmpty() ? "?" : proxyVersion)
                + ", protocol=" + proxyProtocolVersion
                + ", accepting=" + proxyAccepting
                + ", reported downstream=" + (proxyTarget.isEmpty() ? "<unset>" : proxyTarget)
                + ", lastReplyMsAgo=" + ageMs
                + " (config " + configPath + ")";
    }
}
