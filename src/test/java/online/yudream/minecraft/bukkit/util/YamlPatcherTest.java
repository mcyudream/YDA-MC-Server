package online.yudream.minecraft.bukkit.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class YamlPatcherTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void rewritesTheValueAndKeepsEveryComment() throws IOException {
        File file = write(String.join("\n",
                "# the mode comment",
                "mode: standalone",
                "",
                "# another comment",
                "enabled: true"));
        assertTrue(YamlPatcher.setTopLevelValue(file, "mode", "downstream"));

        String text = read(file);
        assertTrue(text.contains("# the mode comment"));
        assertTrue(text.contains("mode: downstream"));
        assertTrue(text.contains("# another comment"));
        assertTrue(text.contains("enabled: true"));
        assertFalse(text.contains("mode: standalone"));
    }

    @Test
    public void appendsAKeyThatIsNotThereYet() throws IOException {
        File file = write("enabled: true");
        assertTrue(YamlPatcher.setTopLevelValue(file, "mode", "downstream"));

        String text = read(file);
        assertTrue(text.contains("enabled: true"));
        assertTrue(text.contains("mode: downstream"));
    }

    @Test
    public void ignoresIndentedKeysInsideOtherSections() throws IOException {
        File file = write(String.join("\n",
                "downstream:",
                "  mode: not-this-one",
                "  ack-timeout-seconds: 90"));
        assertTrue(YamlPatcher.setTopLevelValue(file, "mode", "downstream"));

        String text = read(file);
        // The nested key must survive untouched, and a new top-level key must have been appended.
        assertTrue(text.contains("  mode: not-this-one"));
        assertTrue(text.contains("mode: downstream"));
    }

    @Test
    public void ignoresCommentedOutKeys() throws IOException {
        File file = write(String.join("\n",
                "# mode: standalone",
                "enabled: true"));
        assertTrue(YamlPatcher.setTopLevelValue(file, "mode", "downstream"));

        String text = read(file);
        assertTrue(text.contains("# mode: standalone"));
        assertTrue(text.contains("mode: downstream"));
    }

    @Test
    public void handlesAMissingFile() throws IOException {
        File file = new File(folder.getRoot(), "config.yml");
        assertFalse(file.exists());
        assertTrue(YamlPatcher.setTopLevelValue(file, "mode", "downstream"));
        assertTrue(read(file).contains("mode: downstream"));
    }

    private File write(String content) throws IOException {
        File file = folder.newFile("config.yml");
        Files.write(file.toPath(), content.getBytes(Charset.forName("UTF-8")));
        return file;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
    }
}
