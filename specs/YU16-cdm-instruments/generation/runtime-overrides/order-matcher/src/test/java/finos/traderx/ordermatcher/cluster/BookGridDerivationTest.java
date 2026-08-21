package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-060: the book grid is DERIVED from the committed ticker and stored nowhere, so it must be a
 * pure, total function of that string — identical on every member, on replay and on restore.
 *
 * <p>This exists because the rig proof for the grid ({@code yu16-book-grid},
 * {@code yu16-bond-position} step 7) needs a live three-member cluster and a fresh epoch, which is
 * a slow and scarce thing to run. The predicate itself is pure and can be pinned here in
 * milliseconds, so a mistake in it fails at build time rather than after a PVC wipe.
 *
 * <p>The SCOPE half is the load-bearing one. Widening the grid to "everything" satisfies every
 * bond assertion in this repo and silently multiplies the equity price band by a thousand — so the
 * negative cases below matter more than the positive ones.
 */
class BookGridDerivationTest {

    @Test
    void everyFractionOfParInstrumentGetsTheFineGrid() {
        // Both bond families, across every shape the seeded universe actually contains.
        for (final String ticker : new String[] {
            "UST-20280630", "UST-20560515",                 // notes and bonds
            "UST-BILL-20270812", "UST-STRIP-20560515",      // zero-coupon
            "CORP-IBM-20330215", "CORP-F-20320512" }) {     // corporates, IG and high yield
            assertTrue(MatchingEngineClusteredService.isFractionOfParTicker(ticker),
                ticker + " is quoted as a fraction of par and needs the six-decimal grid");
        }
    }

    @Test
    void nothingElseGetsIt() {
        // THE SCOPE ASSERTION. An equity on grid 1 would have its price band widened a
        // thousandfold, which no bond test in this repo can see.
        for (final String ticker : new String[] {
            "IBM", "AAPL", "SPY",                           // equities and ETFs
            "AAPL260918C00220000",                          // an OCC option
            "USTINCT", "CORPORATE", "CORP", "UST",          // near-misses that must NOT match
            "MUNI-NYC-20350601",                            // a bond class the predicate does not know
            "" }) {
            assertFalse(MatchingEngineClusteredService.isFractionOfParTicker(ticker),
                ticker + " must keep the 0.001 grid");
        }
        // Total, not partial: a null ticker must answer false rather than throw on the apply path.
        assertFalse(MatchingEngineClusteredService.isFractionOfParTicker(null));
    }

    @Test
    void theChangeIsAStrICTSUPERSETOfTheTreasuryOnlyRuleItReplaced() {
        // Why this matters for a DETERMINISTIC-CORE change: the old build derived the grid as
        // startsWith("UST-"). If the new predicate disagreed with it on any ticker OTHER than a
        // CORP- one, the blast radius would be every existing instrument rather than the new
        // family — and a mixed-version window would split members on ordinary equity flow.
        // Pinning the delta to exactly the CORP- prefix is what bounds the change.
        for (final String ticker : new String[] {
            "UST-20280630", "UST-BILL-20270812", "IBM", "AAPL260918C00220000", "MUNI-X", "" }) {
            assertTrue(ticker.startsWith("UST-")
                    == MatchingEngineClusteredService.isFractionOfParTicker(ticker),
                ticker + ": old and new must agree on everything that is not CORP-");
        }
        // ...and disagree on exactly the intended family, or the change did nothing.
        assertFalse("CORP-GS-20360315".startsWith("UST-"));
        assertTrue(MatchingEngineClusteredService.isFractionOfParTicker("CORP-GS-20360315"));
    }
}
