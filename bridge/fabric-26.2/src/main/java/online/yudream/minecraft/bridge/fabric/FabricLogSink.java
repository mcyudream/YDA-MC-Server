package online.yudream.minecraft.bridge.fabric;

import online.yudream.minecraft.bridge.common.log.LogSink;
import org.slf4j.Logger;

/** Routes bridge log lines into the server log. */
public final class FabricLogSink implements LogSink {

    private final Logger logger;
    private final boolean debug;

    public FabricLogSink(boolean debug) {
        this.logger = FabricLog.LOGGER;
        this.debug = debug;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warn(message);
    }

    @Override
    public void warn(String message, Throwable cause) {
        logger.warn(message, cause);
    }

    @Override
    public void debug(String message) {
        if (debug) {
            logger.info("[debug] " + message);
        }
    }
}
