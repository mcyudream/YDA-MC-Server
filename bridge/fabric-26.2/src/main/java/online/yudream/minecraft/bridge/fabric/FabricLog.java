package online.yudream.minecraft.bridge.fabric;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The mod's own logger, so every bridge line can be filtered on the mod id. */
public final class FabricLog {

    public static final Logger LOGGER = LoggerFactory.getLogger(YudreamFabricMod.MOD_ID);

    private FabricLog() {
    }
}
