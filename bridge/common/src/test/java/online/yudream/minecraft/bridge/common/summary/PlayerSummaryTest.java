package online.yudream.minecraft.bridge.common.summary;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlayerSummaryTest {

    @Test
    public void readsAPagedEnvelope() {
        String body = "{\"total\":3,\"items\":["
                + "{\"playerId\":\"a\",\"playerName\":\"Steve\",\"online\":true,\"afk\":false},"
                + "{\"playerId\":\"b\",\"playerName\":\"Alex\",\"online\":true,\"afk\":true},"
                + "{\"playerId\":\"c\",\"playerName\":\"Herobrine\",\"online\":false,\"afk\":false}]}";
        PlayerSummary summary = PlayerSummary.parse(body);
        assertEquals(3, summary.total());
        assertEquals(2, summary.online());
        assertEquals(1, summary.afk());
    }

    @Test
    public void readsABareArray() {
        String body = "[{\"playerId\":\"a\",\"online\":true,\"afk\":false},"
                + "{\"playerId\":\"b\",\"online\":false,\"afk\":false}]";
        PlayerSummary summary = PlayerSummary.parse(body);
        assertEquals(2, summary.total());
        assertEquals(1, summary.online());
    }

    @Test
    public void readsTheSpringPagedShape() {
        String body = "{\"content\":[{\"playerId\":\"a\",\"online\":true,\"afk\":true}],"
                + "\"totalElements\":9,\"totalPages\":9}";
        PlayerSummary summary = PlayerSummary.parse(body);
        assertEquals(9, summary.total());
        assertEquals(1, summary.online());
        assertEquals(1, summary.afk());
    }

    @Test
    public void isNotConfusedByNamesContainingFieldNames() {
        // The previous implementation counted substrings, so this name made the totals wrong.
        String body = "{\"items\":[{\"playerId\":\"a\",\"playerName\":\"\\\"playerId\\\":true\",\"online\":true,\"afk\":false}]}";
        PlayerSummary summary = PlayerSummary.parse(body);
        assertEquals(1, summary.total());
        assertEquals(1, summary.online());
        assertEquals(0, summary.afk());
    }

    @Test
    public void malformedInputYieldsZeros() {
        assertEquals(0, PlayerSummary.parse("not json").total());
        assertEquals(0, PlayerSummary.parse(null).total());
        assertEquals(0, PlayerSummary.parse("").online());
    }
}
