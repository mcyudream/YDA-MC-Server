package online.yudream.minecraft.bridge.common.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A dependency-free JSON tree with a real recursive-descent parser.
 *
 * <p>The bridge ships into three different runtimes (a Velocity proxy, a Fabric dedicated server
 * and a plain JDK test run), so pulling in Gson or Jackson would mean shading or reloading
 * libraries on the proxy. This type is deliberately small: it parses, builds and serialises the
 * handful of shapes the bridge exchanges with YuDream Admin and with the backend sensor.
 *
 * <p>Integral literals are kept as {@link Long} and everything else as {@link Double}, so epoch
 * millisecond timestamps survive a round trip without floating point drift.
 */
public final class JsonValue {

    public enum Kind {
        OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL
    }

    private static final JsonValue NULL = new JsonValue(Kind.NULL, null);

    private final Kind kind;
    private final Object raw;

    private JsonValue(Kind kind, Object raw) {
        this.kind = kind;
        this.raw = raw;
    }

    // ---------------------------------------------------------------- factories

    public static JsonValue object() {
        return new JsonValue(Kind.OBJECT, new LinkedHashMap<String, JsonValue>());
    }

    public static JsonValue array() {
        return new JsonValue(Kind.ARRAY, new ArrayList<JsonValue>());
    }

    public static JsonValue of(String value) {
        return value == null ? NULL : new JsonValue(Kind.STRING, value);
    }

    public static JsonValue of(boolean value) {
        return new JsonValue(Kind.BOOLEAN, Boolean.valueOf(value));
    }

    public static JsonValue of(long value) {
        return new JsonValue(Kind.NUMBER, Long.valueOf(value));
    }

    public static JsonValue of(double value) {
        return new JsonValue(Kind.NUMBER, Double.valueOf(value));
    }

    public static JsonValue nul() {
        return NULL;
    }

    // ---------------------------------------------------------------- inspection

    public Kind kind() {
        return kind;
    }

    public boolean isObject() {
        return kind == Kind.OBJECT;
    }

    public boolean isArray() {
        return kind == Kind.ARRAY;
    }

    public boolean isNull() {
        return kind == Kind.NULL;
    }

    public boolean isNumber() {
        return kind == Kind.NUMBER;
    }

    public boolean isString() {
        return kind == Kind.STRING;
    }

    // ---------------------------------------------------------------- building

    @SuppressWarnings("unchecked")
    public JsonValue put(String key, JsonValue value) {
        require(Kind.OBJECT, "put");
        ((Map<String, JsonValue>) raw).put(key, value == null ? NULL : value);
        return this;
    }

    public JsonValue put(String key, String value) {
        return put(key, of(value));
    }

    public JsonValue put(String key, long value) {
        return put(key, of(value));
    }

    public JsonValue put(String key, boolean value) {
        return put(key, of(value));
    }

    @SuppressWarnings("unchecked")
    public JsonValue add(JsonValue value) {
        require(Kind.ARRAY, "add");
        ((List<JsonValue>) raw).add(value == null ? NULL : value);
        return this;
    }

    // ---------------------------------------------------------------- reading

    @SuppressWarnings("unchecked")
    public JsonValue get(String key) {
        if (kind != Kind.OBJECT) {
            return null;
        }
        return ((Map<String, JsonValue>) raw).get(key);
    }

    public boolean has(String key) {
        JsonValue value = get(key);
        return value != null && !value.isNull();
    }

    /** Returns the member value, or {@code fallback} when the key is missing or JSON null. */
    public JsonValue getOr(String key, JsonValue fallback) {
        JsonValue value = get(key);
        return value == null || value.isNull() ? fallback : value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, JsonValue> members() {
        if (kind != Kind.OBJECT) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap((Map<String, JsonValue>) raw);
    }

    @SuppressWarnings("unchecked")
    public List<JsonValue> items() {
        if (kind != Kind.ARRAY) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList((List<JsonValue>) raw);
    }

    public String asString(String fallback) {
        return kind == Kind.STRING ? (String) raw : fallback;
    }

    public boolean asBoolean(boolean fallback) {
        if (kind != Kind.BOOLEAN) {
            return fallback;
        }
        return ((Boolean) raw).booleanValue();
    }

    public long asLong(long fallback) {
        if (kind != Kind.NUMBER) {
            return fallback;
        }
        return ((Number) raw).longValue();
    }

    public int asInt(int fallback) {
        if (kind != Kind.NUMBER) {
            return fallback;
        }
        return ((Number) raw).intValue();
    }

    public double asDouble(double fallback) {
        if (kind != Kind.NUMBER) {
            return fallback;
        }
        return ((Number) raw).doubleValue();
    }

    private void require(Kind expected, String operation) {
        if (kind != expected) {
            throw new IllegalStateException(operation + " needs a " + expected + " but this value is " + kind);
        }
    }

    // ---------------------------------------------------------------- writing

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        writeTo(out);
        return out.toString();
    }

    private void writeTo(StringBuilder out) {
        switch (kind) {
            case OBJECT: {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, JsonValue> entry : members().entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(out, entry.getKey());
                    out.append(':');
                    entry.getValue().writeTo(out);
                }
                out.append('}');
                break;
            }
            case ARRAY: {
                out.append('[');
                boolean first = true;
                for (JsonValue item : items()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    item.writeTo(out);
                }
                out.append(']');
                break;
            }
            case STRING:
                writeString(out, (String) raw);
                break;
            case NUMBER: {
                if (raw instanceof Long) {
                    out.append(((Long) raw).longValue());
                } else {
                    double value = ((Double) raw).doubleValue();
                    if (value == Math.rint(value) && !Double.isInfinite(value)
                            && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
                        out.append((long) value);
                    } else {
                        out.append(value);
                    }
                }
                break;
            }
            case BOOLEAN:
                out.append(((Boolean) raw).booleanValue() ? "true" : "false");
                break;
            default:
                out.append("null");
                break;
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Parses a complete JSON document.
     *
     * @throws JsonSyntaxException when the text is malformed or has trailing content
     */
    public static JsonValue parse(String text) {
        if (text == null) {
            throw new JsonSyntaxException("JSON text is null");
        }
        Parser parser = new Parser(text);
        JsonValue value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonSyntaxException("Unexpected trailing content at index " + parser.index());
        }
        return value;
    }

    /** Parses the text, returning {@code null} instead of throwing when it is malformed. */
    public static JsonValue tryParse(String text) {
        try {
            return parse(text);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    private static final class Parser {

        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private int index() {
            return index;
        }

        private boolean atEnd() {
            return index >= text.length();
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonSyntaxException("Unexpected end of JSON input");
            }
            return text.charAt(index);
        }

        private void expect(char expected) {
            if (atEnd() || text.charAt(index) != expected) {
                throw new JsonSyntaxException("Expected '" + expected + "' at index " + index);
            }
            index++;
        }

        private JsonValue readValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return JsonValue.of(readString());
                case 't':
                    readLiteral("true");
                    return JsonValue.of(true);
                case 'f':
                    readLiteral("false");
                    return JsonValue.of(false);
                case 'n':
                    readLiteral("null");
                    return JsonValue.nul();
                default:
                    return readNumber();
            }
        }

        private JsonValue readObject() {
            expect('{');
            JsonValue result = JsonValue.object();
            skipWhitespace();
            if (!atEnd() && peek() == '}') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    index++;
                    continue;
                }
                if (next == '}') {
                    index++;
                    return result;
                }
                throw new JsonSyntaxException("Expected ',' or '}' at index " + index);
            }
        }

        private JsonValue readArray() {
            expect('[');
            JsonValue result = JsonValue.array();
            skipWhitespace();
            if (!atEnd() && peek() == ']') {
                index++;
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    index++;
                    continue;
                }
                if (next == ']') {
                    index++;
                    return result;
                }
                throw new JsonSyntaxException("Expected ',' or ']' at index " + index);
            }
        }

        private void readLiteral(String literal) {
            if (!text.startsWith(literal, index)) {
                throw new JsonSyntaxException("Expected '" + literal + "' at index " + index);
            }
            index += literal.length();
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonSyntaxException("Unterminated string starting before index " + index);
                }
                char c = text.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonSyntaxException("Unterminated escape sequence");
                }
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '/':
                        out.append('/');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (index + 4 > text.length()) {
                            throw new JsonSyntaxException("Truncated \\u escape at index " + index);
                        }
                        String hex = text.substring(index, index + 4);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonSyntaxException("Invalid \\u escape '" + hex + "' at index " + index);
                        }
                        index += 4;
                        break;
                    default:
                        throw new JsonSyntaxException("Unsupported escape '\\" + escape + "' at index " + (index - 1));
                }
            }
        }

        private JsonValue readNumber() {
            int start = index;
            if (!atEnd() && (peek() == '-' || peek() == '+')) {
                index++;
            }
            boolean integral = true;
            while (!atEnd()) {
                char c = text.charAt(index);
                if (c >= '0' && c <= '9') {
                    index++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    index++;
                } else {
                    break;
                }
            }
            if (start == index) {
                throw new JsonSyntaxException("Expected a value at index " + start);
            }
            String literal = text.substring(start, index);
            if (integral) {
                try {
                    return JsonValue.of(Long.parseLong(literal));
                } catch (NumberFormatException ignored) {
                    // Falls through to a double for values outside the long range.
                }
            }
            try {
                return JsonValue.of(Double.parseDouble(literal));
            } catch (NumberFormatException e) {
                throw new JsonSyntaxException("Invalid number '" + literal + "' at index " + start);
            }
        }
    }
}
