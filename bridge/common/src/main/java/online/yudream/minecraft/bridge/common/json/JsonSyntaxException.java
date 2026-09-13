package online.yudream.minecraft.bridge.common.json;

/** Thrown when JSON text cannot be parsed. */
public final class JsonSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonSyntaxException(String message) {
        super(message);
    }
}
