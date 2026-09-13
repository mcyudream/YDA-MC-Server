package online.yudream.minecraft.bukkit.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites a single top-level key in a YAML file without disturbing anything else.
 *
 * <p>{@code JavaPlugin.saveConfig()} would be the obvious way to persist {@code /yudreammc mode}, but
 * it regenerates the file from the in-memory map and drops every comment and blank line. Operators
 * read those comments, so the file is edited line by line instead — the same approach the Velocity
 * bridge uses for {@code target.server}.
 */
public final class YamlPatcher {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private YamlPatcher() {
    }

    /**
     * Sets an unindented top-level key, replacing its line in place or appending a new one.
     *
     * @return true when the file was rewritten
     */
    public static boolean setTopLevelValue(File file, String key, String value) throws IOException {
        List<String> lines = readLines(file);
        String rendered = key + ": " + value;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty() || Character.isWhitespace(line.charAt(0)) || line.charAt(0) == '#') {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator > 0 && line.substring(0, separator).trim().equals(key)) {
                lines.set(i, rendered);
                writeLines(file, lines);
                return true;
            }
        }
        lines.add(rendered);
        writeLines(file, lines);
        return true;
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<String>();
        if (!file.isFile()) {
            return lines;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            reader.close();
        }
        return lines;
    }

    private static void writeLines(File file, List<String> lines) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), UTF_8);
        try {
            for (String line : lines) {
                writer.write(line);
                writer.write(System.getProperty("line.separator", "\n"));
            }
        } finally {
            writer.close();
        }
    }
}
