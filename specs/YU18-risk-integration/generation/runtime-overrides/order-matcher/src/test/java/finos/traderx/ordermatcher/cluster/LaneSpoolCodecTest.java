package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/** FR-DRS-57: a spooled record is the record. Every declared field of a bridge's Rec is set to its own value by
 *  reflection, so a field added to a Rec and not to its codec fails here. */
class LaneSpoolCodecTest {
    @Test
    void everyFieldOfATradeSideSurvivesTheSpool() throws Exception {
        roundTrip(new TradeNatsPublisher.Rec(), new TradeNatsPublisher.Rec(), TradeNatsPublisher.SPOOL);
    }

    @Test
    void everyFieldOfAnOrderEventSurvivesTheSpool() throws Exception {
        roundTrip(new OrderNatsPublisher.Rec(), new OrderNatsPublisher.Rec(), OrderNatsPublisher.SPOOL);
    }

    private static <R extends ReceiptLanes.Record> void roundTrip(final R r, final R blank, final LaneSpool.Codec<R> codec) throws Exception {
        int n = 1, fields = 0;
        for (final Field f : r.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            n++;
            fields++;
            final Class<?> t = f.getType();
            if (t == long.class) { f.setLong(r, 1_000_000_000_000L + n); }
            else if (t == int.class) { f.setInt(r, 100_000 + n); }
            else if (t == byte.class) { f.setByte(r, (byte) n); }
            else if (t == boolean.class) { f.setBoolean(r, true); }
            else if (t == String.class) { f.set(r, "s" + n); }
            else { throw new AssertionError("a field type the spool codecs do not carry: " + f); }
        }
        final ByteBuffer b = ByteBuffer.allocate(4096);
        codec.write(r, b);
        b.flip();
        final R back = codec.read(b);
        assertEquals(0, b.remaining(), "read consumes what write wrote");
        for (final Field f : r.getClass().getDeclaredFields()) {
            assertEquals(f.get(r), f.get(back), f.getName());
        }
        assertEquals(true, fields > 5);
        // null strings (an order event without a gateway) survive too
        final ByteBuffer nulls = ByteBuffer.allocate(4096);
        codec.write(blank, nulls);
        nulls.flip();
        final R blankBack = codec.read(nulls);
        for (final Field f : r.getClass().getDeclaredFields()) { assertEquals(f.get(blank), f.get(blankBack), f.getName()); }
    }
}
