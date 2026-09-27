package online.yudream.minecraft.bridge.core.json;

/** Thrown when JSON text cannot be parsed. */
public final class JsonSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonSyntaxException(String message) {
        super(message);
    }
}
