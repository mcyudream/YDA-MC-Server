package online.yudream.minecraft.bridge.common.config;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigFileTest {

    @Test
    public void readsValuesAndSkipsCommentsAndBlanks() {
        ConfigFile config = ConfigFile.ofText(String.join("\n",
                "# a comment",
                "",
                "enabled=true",
                "api.base-url=http://127.0.0.1:8080",
                "  api.server-id = survival  ",
                "quoted=\"value with spaces\"",
                "afk.timeout-seconds=300"));
        assertTrue(config.getBoolean("enabled", false));
        assertEquals("http://127.0.0.1:8080", config.get("api.base-url", ""));
        assertEquals("survival", config.get("api.server-id", ""));
        assertEquals("value with spaces", config.get("quoted", ""));
        assertEquals(300L, config.getLong("afk.timeout-seconds", 0L));
    }

    @Test
    public void keepsCommentsWhenEditingOneKey() {
        ConfigFile config = ConfigFile.ofText(String.join("\n",
                "# the target downstream server",
                "target.server=",
                "# trailing comment"));
        config.set("target.server", "survival");
        String rendered = config.render();
        assertTrue(rendered.contains("# the target downstream server"));
        assertTrue(rendered.contains("target.server=survival"));
        assertTrue(rendered.contains("# trailing comment"));
        assertEquals("survival", ConfigFile.ofText(rendered).get("target.server", ""));
    }

    @Test
    public void appendsUnknownKeysInsteadOfRewriting() {
        ConfigFile config = ConfigFile.ofText("target.server=lobby");
        config.set("target.server", "survival");
        config.set("new.key", "1");
        String rendered = config.render();
        assertEquals(1, occurrences(rendered, "target.server="));
        assertTrue(rendered.contains("new.key=1"));
    }

    @Test
    public void setIfAbsentLeavesExistingValuesAlone() {
        ConfigFile config = ConfigFile.ofText("enabled=false");
        config.setIfAbsent("enabled", true);
        config.setIfAbsent("other", true);
        assertFalse(config.getBoolean("enabled", true));
        assertTrue(config.getBoolean("other", false));
    }

    @Test
    public void parsesCommaSeparatedLists() {
        ConfigFile config = ConfigFile.ofText("command.admins=Alice, bob ,,carol");
        List<String> admins = config.getList("command.admins");
        assertEquals(3, admins.size());
        assertEquals("Alice", admins.get(0));
        assertEquals("bob", admins.get(1));
        assertEquals("carol", admins.get(2));
        assertTrue(config.getList("missing").isEmpty());
    }

    @Test
    public void fallsBackForInvalidNumbers() {
        ConfigFile config = ConfigFile.ofText("http.retry-attempts=lots");
        assertEquals(3, config.getInt("http.retry-attempts", 3));
        assertEquals(1500L, config.getLong("http.retry-delay-ms", 1500L));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
