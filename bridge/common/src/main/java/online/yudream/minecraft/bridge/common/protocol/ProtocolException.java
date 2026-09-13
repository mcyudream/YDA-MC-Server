package online.yudream.minecraft.bridge.common.protocol;

/** Thrown when a plugin message payload is not a bridge message this build understands. */
public final class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
