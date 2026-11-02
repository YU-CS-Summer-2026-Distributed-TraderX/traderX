package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.ordermatcher.lmax.SwapConventions;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-CUI-24: the members' /otc/contracts read of the engine's contract store. */
class OtcContractsJsonTest {
    private static final long EFF = LocalDate.parse("2026-11-03").toEpochDay();
    private static final long MAT = LocalDate.parse("2031-11-03").toEpochDay();

    @Test
    void rendersSwapsAndSwaptionsInTheRegulatoryReportsTermsAndFiltersByAccount() throws Exception {
        final List<long[]> store = List.of(
            new long[] {6830, 65000, 1, 10_000_000, 42_000, 0, EFF, MAT, MatchingEngineClusteredService.PRODUCT_SWAP, 0, 0},
            new long[] {6831, 65001, 0, 5_000_000, 38_500, 0, EFF, MAT, MatchingEngineClusteredService.PRODUCT_SWAPTION,
                LocalDate.parse("2027-11-03").toEpochDay(), 0});
        final JsonNode all = new ObjectMapper().readTree(ClusterNodeMain.otcContractsJson(store, -1, 2, 7000));
        assertEquals(2, all.get("member").asInt());
        assertEquals(2, all.get("contracts").size());
        final JsonNode swap = all.get("contracts").get(0);
        assertEquals("SW-6830", swap.get("ref").asText());
        assertEquals("PAY_FIXED", swap.get("payReceive").asText());
        assertEquals("0.042000", swap.get("fixedRate").asText());
        assertEquals(SwapConventions.at(0).name(), swap.get("convention").asText());
        assertEquals("2031-11-03", swap.get("maturityDate").asText());
        assertFalse(swap.has("expiryDate"));
        final JsonNode swaption = all.get("contracts").get(1);
        assertEquals("SWPT-6831", swaption.get("ref").asText());
        assertEquals("RECEIVE_FIXED", swaption.get("payReceive").asText());
        assertEquals("2027-11-03", swaption.get("expiryDate").asText());
        assertTrue(swaption.has("exerciseStyle"));

        final JsonNode one = new ObjectMapper().readTree(ClusterNodeMain.otcContractsJson(store, 65001, 0, 7000));
        assertEquals(1, one.get("contracts").size());
        assertEquals(65001, one.get("contracts").get(0).get("accountId").asLong());
    }
}
