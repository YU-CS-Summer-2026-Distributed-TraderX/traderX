package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LimitBook.retick} — implementation gate V1 of the format-8 price-derived grid
 * ({@code format-8-price-derived-grid-design.md} section 7).
 *
 * <p>The design claims a re-tick needs NO ARRAY WORK because an emptied book is already fully
 * clean: {@code remove} clears each level's head, tail, aggregate quantity and occupancy bit as it
 * empties it, and the best-price pointers follow. That claim is what keeps the empty-admission
 * branch allocation-free and O(1), and it is the kind of claim that is true right up until someone
 * adds a field. So it is measured here rather than read: FILL a book across several levels and both
 * sides, DRAIN it, re-tick, and then assert full placement behaviour on the NEW grid — including
 * that no ghost of the old occupancy survives.
 */
class LimitBookRetickTest {
    private static final int LEVELS = 128;
    private static final long CENT = 10_000L;
    private static final long MILLI = 1_000L;

    private RestingOrder order(final byte side, final int ref, final int qty) {
        final RestingOrder o = new RestingOrder();
        o.orderRef = ref;
        o.side = side;
        o.quantity = qty;
        o.remaining = qty;
        return o;
    }

    @Test
    void aDrainedBookRetickesCleanlyAndPlacesOnTheNewGrid() {
        final LimitBook book = new LimitBook(LEVELS, CENT);
        book.anchorAround(100 * 1_000_000L);
        final long base = book.baseLevel();

        // Fill: several levels, both sides, more than one order per level (so the FIFO links,
        // the aggregates and the occupancy bitmap all have real state in them).
        final RestingOrder[] resting = new RestingOrder[8];
        int n = 0;
        for (int slot : new int[] { 10, 11, 40 }) {
            for (int i = 0; i < 2; i++) {
                final RestingOrder o = order(InputEvent.SIDE_BUY, ++n, 100);
                book.append(o, slot);
                resting[n - 1] = o;
            }
        }
        for (int slot : new int[] { 90 }) {
            for (int i = 0; i < 2; i++) {
                final RestingOrder o = order(InputEvent.SIDE_SELL, ++n, 50);
                book.append(o, slot);
                resting[n - 1] = o;
            }
        }
        assertEquals(8, book.openOrders());
        assertEquals(40, book.bestBidSlot());
        assertEquals(90, book.bestAskSlot());

        // Drain.
        for (final RestingOrder o : resting) {
            book.remove(o);
        }
        assertEquals(0, book.openOrders());
        assertEquals(LimitBook.NO_LEVEL, book.bestBidSlot(), "best bid must clear as the book empties");
        assertEquals(LimitBook.NO_LEVEL, book.bestAskSlot(), "best ask must clear as the book empties");
        for (int slot = 0; slot < LEVELS; slot++) {
            assertEquals(0L, book.quantityAt(InputEvent.SIDE_BUY, slot), "bid aggregate at " + slot);
            assertEquals(0L, book.quantityAt(InputEvent.SIDE_SELL, slot), "ask aggregate at " + slot);
        }
        assertEquals(LimitBook.NO_LEVEL, book.occupiedBelow(InputEvent.SIDE_BUY, LEVELS),
            "no occupancy bit may survive the drain — a ghost bit would move with the re-tick");
        assertEquals(LimitBook.NO_LEVEL, book.occupiedBelow(InputEvent.SIDE_SELL, LEVELS));

        // Re-tick: new grid, un-anchored.
        book.retick(MILLI);
        assertEquals(MILLI, book.tickTicks());
        assertFalse(book.anchored(), "a re-ticked book must re-anchor on the reference at the new scale");
        assertTrue(base != book.baseLevel() || book.baseLevel() == -1);

        // ...and it behaves as a brand-new book on the new grid.
        assertTrue(book.onGrid(100_500_000L), "a 0.0005 price is on a 1000-Px grid");
        assertFalse(book.onGrid(100_000_500L), "and a 0.0000005 price is not");
        final int slot = book.slotFor(100_000_000L);
        assertEquals(100_000_000L / MILLI - (LEVELS >> 1), book.baseLevel(),
            "the new anchor is computed in the NEW unit");
        final RestingOrder fresh = order(InputEvent.SIDE_BUY, 99, 7);
        book.append(fresh, slot);
        assertEquals(1, book.openOrders());
        assertEquals(slot, book.bestBidSlot());
        assertEquals(7L, book.quantityAt(InputEvent.SIDE_BUY, slot));
        assertEquals(100_000_000L, book.priceAt(slot), "and priceAt reads back in the new unit");
    }

    @Test
    void aRetickOfAnOccupiedBookThrows() {
        // Unconditional, and deliberately a THROW rather than a no-op: every resting order's
        // bookLevel is an index into a band denominated in the OLD tick, so re-ticking with orders
        // in it would silently reinterpret every one of them at a different scale. The engine only
        // calls retick when openOrders() == 0, so reaching this is a caller bug, not a data state.
        final LimitBook book = new LimitBook(LEVELS, CENT);
        book.anchorAround(100 * 1_000_000L);
        book.append(order(InputEvent.SIDE_BUY, 1, 10), 10);
        assertThrows(IllegalStateException.class, () -> book.retick(MILLI));
        assertEquals(CENT, book.tickTicks(), "and the grid is untouched by the refused re-tick");
    }

    @Test
    void aNonPositiveTickIsRefused() {
        final LimitBook book = new LimitBook(LEVELS, CENT);
        assertThrows(IllegalArgumentException.class, () -> book.retick(0L));
        assertThrows(IllegalArgumentException.class, () -> book.retick(-1L));
        assertEquals(CENT, book.tickTicks());
    }
}
