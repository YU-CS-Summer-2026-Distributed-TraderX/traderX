package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.MatchingEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *
 * <p>YU17 (format-8) adds the second convention beside the ticker category: the PRICE-DERIVED decade
 * map ({@code MatchingEngine.decadeTickPx}). Two conventions, two homes; the category OUTRANKS the
 * map, and the map's own closure properties (gate V5) are pinned below. Both live in one class on
 * purpose — the pair is what a reader has to hold at once, and separating them is how one gets
 * changed without the other.
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

    // ----- the price-derived map (YU17, format-8 design section 2.1; gate V5) -------------------

    @Test
    void theMapPutsEveryDECADEOnItsOwnGrid() {
        // The measured 69-instrument table (design section 3), by segment. Prices are Px (1e-6 dollars).
        final long cap = MatchingEngine.DEFAULT_BOOK_TICK_PX;
        assertEquals(1L, MatchingEngine.decadeTickPx(504_000L, cap), "$0.504 option: sub-$1 -> tick 1");
        assertEquals(1L, MatchingEngine.decadeTickPx(215_580L, cap), "30Y STRIP at 0.2156 -> tick 1");
        assertEquals(10L, MatchingEngine.decadeTickPx(1_111_000L, cap), "FNMA at $1.111 -> tick 10");
        assertEquals(10L, MatchingEngine.decadeTickPx(1_010_420L, cap), "an above-par bond price -> tick 10 by the MAP");
        assertEquals(100L, MatchingEngine.decadeTickPx(35_177_000L, cap), "$35.18 option -> tick 100");
        assertEquals(100L, MatchingEngine.decadeTickPx(17_000_000L, cap), "DB at $17 -> tick 100");
        assertEquals(1000L, MatchingEngine.decadeTickPx(150_000_000L, cap), "$150 equity -> today's grid");
        assertEquals(1000L, MatchingEngine.decadeTickPx(916_000_000L, cap), "NVDA at $916 -> today's grid");
    }

    @Test
    void theMapIsCappedAtTheGlobalGridAndFlooredAtOne() {
        final long cap = MatchingEngine.DEFAULT_BOOK_TICK_PX;
        // COARSE side: nothing may ever get a coarser grid than the configured global, so the
        // change is monotone -- bands only ever tighten, and no existing band needs re-sizing.
        assertEquals(cap, MatchingEngine.decadeTickPx(1_500_000_000L, cap),
            "a hypothetical $1,500 instrument holds the cap, not a coarser grid");
        assertEquals(cap, MatchingEngine.decadeTickPx(Long.MAX_VALUE / 2, cap),
            "and the loop terminates at the cap for any price at all");
        // FINE side: 1 is the floor by construction, including for prices at or below zero, which
        // is what an unpriced security answers -- the caller treats that as "no reference" and
        // falls to the provisional global grid, but the map must still be total.
        assertEquals(1L, MatchingEngine.decadeTickPx(0L, cap));
        assertEquals(1L, MatchingEngine.decadeTickPx(-1L, cap));
        assertEquals(1L, MatchingEngine.decadeTickPx(1L, cap), "a sub-cent price is on the finest grid");
    }

    @Test
    void everyProducibleTickDividesACentAndACent() {
        // GATE V5, and it is the UI landmine in numeric form: a console ticket step derived from
        // this grid must never offer precision the engine refuses, and a cent must always be
        // on-grid or every quoted increment in the system becomes INVALID. Swept across the map's
        // whole producible range rather than at the values we happen to like.
        final long cap = MatchingEngine.DEFAULT_BOOK_TICK_PX;
        for (long px = 0; px <= 2_000_000_000L; px += 999_983L) {   // a prime-ish stride, all decades
            final long tick = MatchingEngine.decadeTickPx(px, cap);
            assertTrue(tick >= 1L && tick <= cap, "tick " + tick + " out of range at px " + px);
            assertEquals(0L, 10_000L % tick,
                "tick " + tick + " (px " + px + ") does not divide a cent: quoted increments would be off-grid");
            assertEquals(0L, 1_000_000L % tick, "tick " + tick + " does not divide a dollar");
        }
    }

    @Test
    void theMapIsMONOTONEInPrice() {
        // A cheaper instrument may never get a COARSER grid than a dearer one -- that inversion
        // would give the cheap end the wide band this whole change exists to remove, and it is the
        // shape of bug an off-by-one in the loop condition produces.
        final long cap = MatchingEngine.DEFAULT_BOOK_TICK_PX;
        long previous = 0L;
        for (long px = 0; px <= 2_000_000_000L; px += 997_711L) {
            final long tick = MatchingEngine.decadeTickPx(px, cap);
            assertTrue(tick >= previous, "tick went DOWN as price rose, at px " + px);
            previous = tick;
        }
    }

    @Test
    void aDeliberatelyOffConventionTickerFallsToTheMapNotToTheCategory() {
        // The loop-closer's own falsification arm (skill: a-prefix-is-not-a-category, step 5): an
        // instrument that does NOT match the fraction-of-par convention must be priced by the map,
        // whatever it is called. If this ever passes for a bond-shaped ticker, the category
        // predicate has silently widened.
        assertFalse(MatchingEngineClusteredService.isFractionOfParTicker("MUNI-NYC-20350601"),
            "precondition: this bond family is NOT in the category, by design (section 2.4 residual)");
        assertEquals(10L, MatchingEngine.decadeTickPx(1_010_420L, MatchingEngine.DEFAULT_BOOK_TICK_PX),
            "so an above-par MUNI is priced by the map like any other instrument — the stated"
                + " residual, not an accident");
    }
}
