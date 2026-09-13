package online.yudream.minecraft.bridge.fabric;

import net.fabricmc.api.DedicatedServerModInitializer;
import online.yudream.minecraft.bridge.common.config.ConfigFile;
import online.yudream.minecraft.bridge.fabric.config.FabricConfigTemplate;
import online.yudream.minecraft.bridge.fabric.config.FabricSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Backend sensor for the YuDream bridge.
 *
 * <p>This mod never talks to YuDream Admin. It observes player activity on the backend it is
 * installed on and forwards it to the Velocity proxy over the {@code yudream:bridge} plugin message
 * channel; the proxy owns presence, AFK state and every HTTP call.
 *
 * <p>It is a {@link DedicatedServerModInitializer} rather than a plain {@code ModInitializer}
 * because {@code fabric.mod.json} registers it under the {@code server} entrypoint, which requires
 * exactly this interface — a plain {@code ModInitializer} fails to load at startup.
 */
public final class YudreamFabricMod implements DedicatedServerModInitializer {

    public static final String MOD_ID = "yudream_minecraft_server";
    public static final String VERSION = "1.0.0";

    private static FabricBridge bridge;

    /** The running sensor state, or {@code null} before the entrypoint runs. */
    public static FabricBridge bridge() {
        return bridge;
    }

    @Override
    public void onInitializeServer() {
        Path configPath = FabricPaths.configDirectory().resolve("yudream-bridge.properties");
        FabricSettings settings;
        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ConfigFile file = ConfigFile.loadOrCreate(configPath, FabricConfigTemplate.render());
            FabricSettings loaded = FabricSettings.from(file);
            FabricSettings.applyDefaults(file, loaded);
            file.save(configPath);
            settings = FabricSettings.from(file);
        } catch (IOException e) {
            FabricLog.LOGGER.warn("Could not read the YuDream sensor config at {}; using defaults.", configPath, e);
            settings = FabricSettings.defaults();
        }

        bridge = new FabricBridge(settings, configPath, new FabricLogSink(settings.isDebug()));
        bridge.start();
        new FabricSensor(bridge).install();
    }
}
