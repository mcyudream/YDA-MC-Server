package online.yudream.minecraft.bridge.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small {@code key=value} config file that keeps its comments and its layout.
 *
 * <p>{@link java.util.Properties} would work for reading, but {@code Properties.store} rewrites the
 * whole file and drops every comment and blank line. Operators edit these files by hand, and the
 * bridge has to write a single key back when {@code /yudreammc target} changes the reported
 * downstream server, so the file is edited line by line instead.
 */
public final class ConfigFile {

    private final List<String> lines = new ArrayList<String>();

    private ConfigFile() {
    }

    public static ConfigFile empty() {
        return new ConfigFile();
    }

    /** Reads a config file, treating a missing file as an empty one. */
    public static ConfigFile load(Path path) throws IOException {
        ConfigFile config = new ConfigFile();
        if (path != null && Files.isRegularFile(path)) {
            config.lines.addAll(Files.readAllLines(path, StandardCharsets.UTF_8));
        }
        return config;
    }

    /** Parses text into a config file, used by tests and by generated templates. */
    public static ConfigFile ofText(String text) {
        ConfigFile config = new ConfigFile();
        if (text != null && !text.isEmpty()) {
            for (String line : text.split("\r\n|\r|\n", -1)) {
                config.lines.add(line);
            }
            while (!config.lines.isEmpty() && config.lines.get(config.lines.size() - 1).isEmpty()) {
                config.lines.remove(config.lines.size() - 1);
            }
        }
        return config;
    }

    private static String parseKey(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return null;
        }
        int separator = trimmed.indexOf('=');
        if (separator < 0) {
            separator = trimmed.indexOf(':');
        }
        if (separator <= 0) {
            return null;
        }
        return trimmed.substring(0, separator).trim();
    }

    private static String parseValue(String line) {
        String trimmed = line.trim();
        int separator = trimmed.indexOf('=');
        if (separator < 0) {
            separator = trimmed.indexOf(':');
        }
        if (separator < 0) {
            return "";
        }
        String value = trimmed.substring(separator + 1).trim();
        if (value.length() >= 2
                && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Every key currently present, in file order. */
    public Map<String, String> entries() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String line : lines) {
            String key = parseKey(line);
            if (key != null && !result.containsKey(key)) {
                result.put(key, parseValue(line));
            }
        }
        return result;
    }

    public boolean contains(String key) {
        return entries().containsKey(key);
    }

    public String get(String key, String fallback) {
        String value = entries().get(key);
        return value == null ? fallback : value;
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = entries().get(key);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value);
    }

    public int getInt(String key, int fallback) {
        try {
            String value = entries().get(key);
            return value == null || value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public long getLong(String key, long fallback) {
        try {
            String value = entries().get(key);
            return value == null || value.isEmpty() ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Reads a comma separated list, trimming blanks. An empty value yields an empty list. */
    public List<String> getList(String key) {
        List<String> result = new ArrayList<String>();
        String value = entries().get(key);
        if (value == null) {
            return result;
        }
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Replaces the value of an existing key on its current line, or appends a new line.
     *
     * @return this config, for chaining
     */
    public ConfigFile set(String key, String value) {
        String rendered = key + "=" + (value == null ? "" : value);
        for (int i = 0; i < lines.size(); i++) {
            String existing = parseKey(lines.get(i));
            if (key.equals(existing)) {
                lines.set(i, rendered);
                return this;
            }
        }
        lines.add(rendered);
        return this;
    }

    public ConfigFile setIfAbsent(String key, String value) {
        return contains(key) ? this : set(key, value);
    }

    public ConfigFile setIfAbsent(String key, boolean value) {
        return setIfAbsent(key, Boolean.toString(value));
    }

    public ConfigFile setIfAbsent(String key, long value) {
        return setIfAbsent(key, Long.toString(value));
    }

    public ConfigFile setIfAbsent(String key, double value) {
        return setIfAbsent(key, Double.toString(value));
    }

    public ConfigFile addComment(String comment) {
        lines.add(comment == null || comment.isEmpty() ? "#" : "# " + comment);
        return this;
    }

    public ConfigFile addRawLine(String line) {
        lines.add(line == null ? "" : line);
        return this;
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append(System.lineSeparator());
        }
        return out.toString();
    }

    public void save(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, render().getBytes(StandardCharsets.UTF_8));
    }

    /** Creates the file with the given template text when it does not exist yet. */
    public static ConfigFile loadOrCreate(Path path, String template) throws IOException {
        if (path != null && Files.isRegularFile(path)) {
            return load(path);
        }
        ConfigFile config = ofText(template);
        if (path != null) {
            config.save(path);
        }
        return config;
    }
}
