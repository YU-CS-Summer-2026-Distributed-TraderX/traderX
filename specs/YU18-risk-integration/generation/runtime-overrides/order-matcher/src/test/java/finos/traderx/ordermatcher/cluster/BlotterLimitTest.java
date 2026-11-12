package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** FR-DRS-56: the blotter serves the caller's {@code limit}, bounded by the member's RECON_PAGE_MAX. */
class BlotterLimitTest {
    @Test
    void theCallersLimitIsBoundedByTheMembersCeiling() {
        assertEquals(1_000, ClusterNodeMain.blotterLimit(1_000, 50_000), "no limit asked: the page size passes through");
        assertEquals(20_000, ClusterNodeMain.blotterLimit(20_000, 50_000));
        assertEquals(50_000, ClusterNodeMain.blotterLimit(10_000_000, 50_000));
        assertEquals(1, ClusterNodeMain.blotterLimit(-5, 50_000));
    }
}
