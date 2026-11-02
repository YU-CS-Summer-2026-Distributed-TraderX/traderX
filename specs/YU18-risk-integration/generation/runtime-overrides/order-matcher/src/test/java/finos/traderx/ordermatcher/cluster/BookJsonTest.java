package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** FR-CUI-25: the members' /book read, Level 1 (first row, last) and Level 2 (the ladder). */
class BookJsonTest {
    @Test
    void rendersBestFirstLevelsInTheBboScaleAndOnlyTheLevelsRead() throws Exception {
        final JsonNode b = new ObjectMapper().readTree(ClusterNodeMain.bookJson("AAPL",
            new long[] {99_000_000, 98_000_000, 0}, new long[] {15, 7, 0}, 2,
            new long[] {101_000_000, 0, 0}, new long[] {3, 0, 0}, 1, 100_500_000, 1, 42));
        assertEquals("AAPL", b.get("ticker").asText());
        assertEquals(100.5, b.get("last").asDouble());
        assertEquals(2, b.get("bids").size());
        assertEquals(99.0, b.get("bids").get(0).get("px").asDouble());
        assertEquals(15, b.get("bids").get(0).get("qty").asLong());
        assertEquals(1, b.get("asks").size());
        assertEquals(3, b.get("asks").get(0).get("qty").asLong());
        final JsonNode empty = new ObjectMapper().readTree(ClusterNodeMain.bookJson("A\"B", new long[0], new long[0], 0,
            new long[0], new long[0], 0, 0, 0, 0));
        assertEquals("A\"B", empty.get("ticker").asText());
        assertFalse(empty.has("last"), "never traded: no last");
        assertEquals(0, empty.get("bids").size());
    }
}
