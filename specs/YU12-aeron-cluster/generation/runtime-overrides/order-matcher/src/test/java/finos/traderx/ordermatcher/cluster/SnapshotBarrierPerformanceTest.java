package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Callback-cost gate for the synchronous Aeron snapshot barrier. The fixture deliberately
 * retains enough terminal rows to expose a per-terminal scan of the complete order index:
 * 50k open rows followed by 50k terminal rows.
 */
class SnapshotBarrierPerformanceTest {
    private static final int ORDER_COUNT = 100_000;
    private static final int TERMINAL_COUNT = 50_000;
    private static final long MAX_CALLBACK_NANOS = 50_000_000L;

    @Test
    void snapshotCallbackStaysWithinFiftyMillisecondsAtInflatedState() {
        final MatchingEngineClusteredService service = inflatedService();
        final CountingWriter writer = new CountingWriter();
        final int warmupRuns = Integer.getInteger("snapshot.gate.warmupRuns", 2);
        final int measuredRuns = Integer.getInteger("snapshot.gate.measuredRuns", 5);

        for (int i = 0; i < warmupRuns; i++) {
            writer.reset();
            service.writeSnapshot(writer);
        }

        final long[] samples = new long[measuredRuns];
        for (int i = 0; i < measuredRuns; i++) {
            writer.reset();
            final long before = System.nanoTime();
            service.writeSnapshot(writer);
            samples[i] = System.nanoTime() - before;
            assertTrue(writer.records >= ORDER_COUNT, "writer observed every order record");
        }
        Arrays.sort(samples);

        final long minNanos = samples[0];
        final long medianNanos = samples[samples.length / 2];
        final long maxNanos = samples[samples.length - 1];
        System.out.printf(
            "SNAPSHOT_CALLBACK_BENCH orders=%d terminals=%d runs=%d minMs=%.3f medianMs=%.3f maxMs=%.3f records=%d checksum=%d%n",
            ORDER_COUNT, TERMINAL_COUNT, measuredRuns,
            minNanos / 1_000_000.0, medianNanos / 1_000_000.0, maxNanos / 1_000_000.0,
            writer.records, writer.checksum);

        assertTrue(maxNanos <= MAX_CALLBACK_NANOS,
            "snapshot callback max " + (maxNanos / 1_000_000.0) + " ms exceeds 50 ms");
    }

    private MatchingEngineClusteredService inflatedService() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine(ORDER_COUNT, TERMINAL_COUNT);

        final int openCount = ORDER_COUNT - TERMINAL_COUNT;
        for (int ref = 1; ref <= openCount; ref++) {
            service.engine().bootstrapOrder(ref, 11, 1, InputEvent.SIDE_BUY,
                10, 10, 100_000_000L, RestingOrder.STATUS_NEW, 0L, 0, 1L, 1L);
        }
        for (int ref = openCount + 1; ref <= ORDER_COUNT; ref++) {
            service.engine().bootstrapOrder(ref, 11, 1, InputEvent.SIDE_BUY,
                10, 0, 100_000_000L, RestingOrder.STATUS_CANCELED, 0L, 0, 1L, 2L);
        }

        assertEquals(ORDER_COUNT, service.engine().allOrderTuples().size());
        assertEquals(TERMINAL_COUNT, service.engine().terminalOrderRefsFifo().length);
        return service;
    }

    private static final class CountingWriter implements MatchingEngineClusteredService.SnapshotWriter {
        private int records;
        private long checksum;

        @Override
        public void write(final DirectBuffer buffer, final int offset, final int length) {
            records++;
            checksum = checksum * 31 + buffer.getInt(offset);
            checksum = checksum * 31 + buffer.getByte(offset + length - 1);
        }

        private void reset() {
            records = 0;
            checksum = 0;
        }
    }
}
