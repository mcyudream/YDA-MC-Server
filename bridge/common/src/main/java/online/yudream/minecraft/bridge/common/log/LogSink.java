package online.yudream.minecraft.bridge.common.log;

/** The bridge never touches a platform logger directly, so the core stays runtime-agnostic. */
public interface LogSink {

    void info(String message);

    void warn(String message);

    void warn(String message, Throwable cause);

    default void debug(String message) {
        // Most sinks treat debug as opt-in.
    }
}
