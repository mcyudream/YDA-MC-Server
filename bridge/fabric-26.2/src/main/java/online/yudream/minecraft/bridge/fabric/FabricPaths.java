package online.yudream.minecraft.bridge.fabric;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/** Resolves the paths the sensor writes to. */
public final class FabricPaths {

    private FabricPaths() {
    }

    public static Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
