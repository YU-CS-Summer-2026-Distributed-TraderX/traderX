package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.ordermatcher.risk.RiskReason;
import org.junit.jupiter.api.Test;

/**
 * The regulatory report used to record THAT an order was refused and never why: from the audit
 * surface you could not tell the risk gate refusing an unknown account from the price collar
 * refusing a good one, and the difference had to be reconstructed by inference from accepted and
 * rejected price ranges per security — which is not a record.
 *
 * <p>The reason was never missing, only unrendered: the engine decides it, {@code
 * OutputPublisher.emitRequestRejected} writes it onto {@code OutputEvent.riskReason}, and the
 * shadow replay hands that same event to {@code ClusterRecon.auditRow}, which dropped it.
 *
 * <p>The property worth pinning is DISCRIMINATION. A check that renders the same string for two
 * genuinely different causes has verified nothing and would pass — it is the very failure the
 * column exists to fix — so every assertion here is that two causes differ, never that one is
 * merely non-empty. The Spring tier's mirror of this mapping is pinned by {@code
 * AuditLogHandlerTest}; the two tiers duplicate the mapping deliberately, so each needs its own.
 */
class ClusterReconAuditReasonTest {

    @Test
    void differentCausesRenderAsDifferentNames() {
        final String unknownAccount = ClusterRecon.reasonName((byte) RiskReason.UNKNOWN_ACCOUNT.ordinal());
        final String collar = ClusterRecon.reasonName((byte) RiskReason.PRICE_COLLAR.ordinal());

        assertEquals("UNKNOWN_ACCOUNT", unknownAccount);
        assertEquals("PRICE_COLLAR", collar);
        assertNotEquals(unknownAccount, collar,
            "an unknown account and a good account outside the band are the two causes this "
                + "column exists to tell apart");
    }

    @Test
    void aNonRejectionCarriesTheEnginesOwnAcceptedByteNotABlank() {
        // OutputPublisher sets riskReason = 0 on the accepted paths, and ordinal 0 IS ACCEPTED, so
        // this is the engine's value rendered rather than a placeholder chosen here. A blank or a
        // synthesized NONE would be a value the enum does not have, and indistinguishable from
        // "the byte could not be read".
        assertEquals("ACCEPTED", ClusterRecon.reasonName((byte) 0));
        assertEquals(RiskReason.ACCEPTED, RiskReason.values()[0]);
    }

    @Test
    void aCancelCarriesAReasonToo() {
        // The column is not gated on ORDER_REJECTED. SELF_TRADE_PREVENTED rides a cancel today,
        // and ADR-069 adds a second cancel cause (the session transition at the open) to this same
        // surface — a trader whose resting order vanished deserves to see which one did it.
        assertNotEquals(ClusterRecon.reasonName((byte) RiskReason.SELF_TRADE_PREVENTED.ordinal()),
            ClusterRecon.reasonName((byte) RiskReason.ACCEPTED.ordinal()));
        assertEquals("SELF_TRADE_PREVENTED",
            ClusterRecon.reasonName((byte) RiskReason.SELF_TRADE_PREVENTED.ordinal()));
    }

    @Test
    void anOrdinalFromALaterBuildIsNamedOpaquelyNotFatal() {
        // The ordinal arrives off the wire and out of snapshots, so a build that appended a reason
        // this one does not know yields an out-of-range byte. regulatoryReport renders the whole
        // range in one pass: throwing here would blank every row over one byte of one of them.
        assertTrue(ClusterRecon.reasonName((byte) (RiskReason.values().length)).startsWith("UNKNOWN_"));
        assertTrue(ClusterRecon.reasonName((byte) 127).startsWith("UNKNOWN_"));
        assertTrue(ClusterRecon.reasonName((byte) -1).startsWith("UNKNOWN_"));
    }
}
