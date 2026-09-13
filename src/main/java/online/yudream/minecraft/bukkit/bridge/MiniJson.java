package online.yudream.minecraft.bukkit.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, correct JSON reader for the bridge envelope.
 *
 * <p>The proxy only ever sends two tiny objects to a backend ({@code probe} and {@code hello_ack}),
 * but they still have to be parsed properly: this plugin is compiled to Java 8 bytecode so it can run
 * on 1.8.8 and newer, which rules out reusing the shared bridge core on the proxy side. Counting
 * substrings the way the old player-summary reader did would break on any player name containing a
 * quote, so this is a real recursive-descent parser.
 */
public final class MiniJson {

    private MiniJson() {
    }

    /**
     * Parses a complete JSON document.
     *
     * @return a {@link Map}, {@link List}, {@link String}, {@link Long}, {@link Double},
     *         {@link Boolean} or {@code null}
     * @throws IllegalArgumentException when the text is malformed or has trailing content
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON text is null");
        }
        Parser parser = new Parser(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Unexpected trailing content at index " + parser.index);
        }
        return value;
    }

    /** Parses an object, returning {@code null} instead of throwing for anything malformed. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        try {
            Object value = parse(text);
            return value instanceof Map ? (Map<String, Object>) value : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String string(Map<String, Object> object, String key, String fallback) {
        Object value = object == null ? null : object.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    public static int integer(Map<String, Object> object, String key, int fallback) {
        Object value = object == null ? null : object.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    public static long longValue(Map<String, Object> object, String key, long fallback) {
        Object value = object == null ? null : object.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    public static boolean bool(Map<String, Object> object, String key, boolean fallback) {
        Object value = object == null ? null : object.get(key);
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    private static final class Parser {

        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
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
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            return text.charAt(index);
        }

        private void expect(char expected) {
            if (atEnd() || text.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at index " + index);
            }
            index++;
        }

        private Object readValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    readLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    readLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    readLiteral("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<String, Object>();
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
                throw new IllegalArgumentException("Expected ',' or '}' at index " + index);
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<Object>();
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
                throw new IllegalArgumentException("Expected ',' or ']' at index " + index);
            }
        }

        private void readLiteral(String literal) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException("Expected '" + literal + "' at index " + index);
            }
            index += literal.length();
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("Unterminated string starting before index " + index);
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
                    throw new IllegalArgumentException("Unterminated escape sequence");
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
                            throw new IllegalArgumentException("Truncated \\u escape at index " + index);
                        }
                        String hex = text.substring(index, index + 4);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid \\u escape '" + hex + "' at index " + index);
                        }
                        index += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported escape '\\" + escape + "' at index " + (index - 1));
                }
            }
        }

        private Object readNumber() {
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
                throw new IllegalArgumentException("Expected a value at index " + start);
            }
            String literal = text.substring(start, index);
            if (integral) {
                try {
                    return Long.valueOf(Long.parseLong(literal));
                } catch (NumberFormatException ignored) {
                    // Falls through to a double for values outside the long range.
                }
            }
            try {
                return Double.valueOf(Double.parseDouble(literal));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number '" + literal + "' at index " + start);
            }
        }
    }
}
