package online.yudream.minecraft.bridge.fabric;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Resolves the paths the bridge writes to.
 *
 * <p>The config lives with every other mod's, so an operator finds it where they expect. Runtime state
 * that is not hand-edited — currently the persisted report queue — lives in its own directory next to
 * the world instead, so it is never mistaken for configuration.
 */
public final class FabricPaths {

    /** Directory under the server run directory that holds bridge runtime state. */
    private static final String DATA_DIRECTORY = "yudream-bridge";

    private FabricPaths() {
    }

    public static Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static Path dataDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve(DATA_DIRECTORY);
    }
}
