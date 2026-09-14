package online.yudream.minecraft.bridge.common.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BridgeModeTest {

    @Test
    public void parsesTheTwoConfigIds() {
        assertEquals(BridgeMode.DOWNSTREAM, BridgeMode.fromId("downstream", BridgeMode.STANDALONE));
        assertEquals(BridgeMode.STANDALONE, BridgeMode.fromId("standalone", BridgeMode.DOWNSTREAM));
    }

    @Test
    public void ignoresCaseAndSurroundingWhitespace() {
        assertEquals(BridgeMode.STANDALONE, BridgeMode.fromId("  STANDALONE  ", BridgeMode.DOWNSTREAM));
        assertEquals(BridgeMode.DOWNSTREAM, BridgeMode.fromId("DownStream", BridgeMode.STANDALONE));
    }

    /**
     * The fallback is the caller's, not a fixed one: the Fabric mod has always been a sensor and the
     * Bukkit plugin has always uploaded directly, so a typo must leave each runtime on its own
     * historical behaviour rather than silently switching it.
     */
    @Test
    public void unknownValuesFallBackToTheCallersDefault() {
        assertEquals(BridgeMode.DOWNSTREAM, BridgeMode.fromId("sensor", BridgeMode.DOWNSTREAM));
        assertEquals(BridgeMode.STANDALONE, BridgeMode.fromId("sensor", BridgeMode.STANDALONE));
        assertEquals(BridgeMode.DOWNSTREAM, BridgeMode.fromId("", BridgeMode.DOWNSTREAM));
        assertEquals(BridgeMode.STANDALONE, BridgeMode.fromId(null, BridgeMode.STANDALONE));
    }

    /** A command that supplies no fallback can tell "unknown" apart from a real answer. */
    @Test
    public void aNullFallbackReportsUnknownValues() {
        assertEquals(null, BridgeMode.fromId("nonsense", null));
        assertEquals(BridgeMode.DOWNSTREAM, BridgeMode.fromId("downstream", null));
    }

    @Test
    public void modePredicatesMatchTheValue() {
        assertTrue(BridgeMode.DOWNSTREAM.isDownstream());
        assertFalse(BridgeMode.DOWNSTREAM.isStandalone());
        assertTrue(BridgeMode.STANDALONE.isStandalone());
        assertFalse(BridgeMode.STANDALONE.isDownstream());
    }

    @Test
    public void idsRoundTrip() {
        for (BridgeMode mode : BridgeMode.values()) {
            assertEquals(mode, BridgeMode.fromId(mode.getId(), null));
        }
    }
}
