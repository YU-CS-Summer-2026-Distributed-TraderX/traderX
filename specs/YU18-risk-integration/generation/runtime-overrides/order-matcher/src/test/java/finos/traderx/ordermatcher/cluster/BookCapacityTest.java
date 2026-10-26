package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OrderTypes;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-079 §6: past MAX_ACTIVE_BOOKS, a NEW on a security with no book is refused BOOK_CAPACITY instead of allocating
 * one more ~5-7 MB book on every member. Below the cap nothing is refused, an existing book keeps trading at the cap,
 * and a member restored from a snapshot counts the restored books, so it gives the same decision as the original.
 */
class BookCapacityTest {
    static final long PX = 1_000_000L;
    static final int CAP = 3;
    final AeronReplicationCodec codec = new AeronReplicationCodec();
    final UnsafeBuffer in = new UnsafeBuffer(new byte[512]);
    long ts = 1_000_000_000_000L;
    int key;

    void apply(MatchingEngineClusteredService s, InputEvent e) {
        if (e.orderType != 0) {
            codec.encodeOrderInstruction(in, 0, e, 0, 0, 0);
            s.onSessionMessage(null, ++ts, in, 0, AeronReplicationCodec.ORDER_INSTRUCTION_BYTES, null);
        } else {
            codec.encodeInput(in, 0, e, 0, 0, 0);
            s.onSessionMessage(null, ++ts, in, 0, AeronReplicationCodec.INPUT_BYTES, null);
        }
    }

    InputEvent order(int sec, boolean typed) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW; e.accountId = 1; e.securityId = sec;
        e.side = InputEvent.SIDE_BUY; e.qty = 1; e.limitPx = 100 * PX;
        e.setClientOrderKey(++key);
        if (typed) { e.orderType = OrderTypes.LIMIT; e.tif = OrderTypes.GTC; }
        return e;
    }

    /** The reason byte of the order the NEW just created. */
    byte decide(MatchingEngineClusteredService s, int sec, boolean typed) {
        apply(s, order(sec, typed));
        return (byte) s.engine().orderState((int) s.nextOrderRef() - 1)[10];
    }

    MatchingEngineClusteredService member() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        InputEvent a = new InputEvent();
        a.type = InputEvent.TYPE_ACCOUNT_CONTROL; a.accountId = 1; a.setControlEnabled(true); a.setControlVersion(1L);
        apply(s, a);
        for (int sec = 0; sec < 6; sec++) {
            InputEvent c = new InputEvent();
            c.type = InputEvent.TYPE_SECURITY_CONTROL; c.securityId = sec; c.setControlEnabled(true); c.setControlVersion(2L + sec);
            apply(s, c);
            InputEvent t = new InputEvent();
            t.type = InputEvent.TYPE_PRICE_TICK; t.securityId = sec; t.priceTicks = 100 * PX;
            apply(s, t);
        }
        s.engine().maxActiveBooks(CAP);   // the run's configured cap, the same on every member
        return s;
    }

    static final byte ACCEPTED = (byte) RiskReason.ACCEPTED.ordinal();
    static final byte REFUSED = (byte) RiskReason.BOOK_CAPACITY.ordinal();

    @Test void refusedAtCapPlusOneAndTheSameAfterASnapshotRestore() {
        final MatchingEngineClusteredService s = member();
        for (int sec = 0; sec < CAP; sec++) {
            assertEquals(ACCEPTED, decide(s, sec, sec == 1), "below the cap: book " + (sec + 1) + " of " + CAP);
        }
        assertEquals(CAP, s.engine().booksAllocated());
        assertEquals(REFUSED, decide(s, CAP, false), "cap+1, legacy path");
        assertEquals(REFUSED, decide(s, CAP + 1, true), "cap+1, typed path");
        assertNull(s.engine().book(CAP), "a refused NEW allocates no book");
        assertNull(s.engine().book(CAP + 1));
        assertEquals(ACCEPTED, decide(s, 0, true), "a security that has a book keeps trading at the cap");
        assertEquals(CAP, s.engine().booksAllocated());

        final List<byte[]> records = new ArrayList<>();
        s.writeSnapshot((b, o, l) -> records.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        final MatchingEngineClusteredService r = SnapshotPackedRoundTripTest.restore(records);
        r.engine().maxActiveBooks(CAP);
        assertEquals(CAP, r.engine().booksAllocated(), "restored books are counted");
        for (final boolean typed : new boolean[] {false, true}) {
            assertEquals(decide(s, CAP + 2, typed), decide(r, CAP + 2, typed), "same refusal on original and restored");
            assertEquals(decide(s, 2, typed), decide(r, 2, typed), "same acceptance on original and restored");
        }
        assertEquals(REFUSED, decide(r, CAP + 2, false));
        assertNull(r.engine().book(CAP + 2));
    }

    @Test void noRefusalBelowTheCap() {
        final MatchingEngineClusteredService s = member();
        s.engine().maxActiveBooks(6);
        for (int sec = 0; sec < 6; sec++) assertEquals(ACCEPTED, decide(s, sec, (sec & 1) == 1), "security " + sec);
        assertEquals(6, s.engine().booksAllocated());
    }

    @Test void capIsDerivedFromTheHeapUnlessPinned() {
        final int levels = 1 << 17;
        final long g = 1L << 30;
        assertEquals(MatchingEngineClusteredService.MAX_SECURITIES,
            MatchingEngineClusteredService.maxActiveBooks(null, 12 * g, levels, true), "12 GB G1: all 1,024 fit in half the heap");
        assertEquals(873, MatchingEngineClusteredService.maxActiveBooks("auto", 12 * g, levels, false), "12 GB ZGC: ~7.4 MB books");
        assertEquals(218, MatchingEngineClusteredService.maxActiveBooks("", 3 * g, levels, false),
            "3 GB ZGC, the attempt that ran out of memory at 1,000 symbols: refused instead");
        assertEquals(500, MatchingEngineClusteredService.maxActiveBooks("500", 3 * g, levels, false), "pinned");
        assertEquals(MatchingEngineClusteredService.MAX_SECURITIES,
            MatchingEngineClusteredService.maxActiveBooks("5000", g, levels, true), "never above MAX_SECURITIES");
        assertEquals(1, MatchingEngineClusteredService.maxActiveBooks("auto", 1L << 20, levels, true), "never below 1");
    }
}
