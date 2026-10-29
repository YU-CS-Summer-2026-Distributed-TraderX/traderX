package finos.traderx.ordermatcher.cluster;

import io.aeron.ExclusivePublication;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot reader fails closed on the REAL restore path (FR-XOD-18's reader half). Image.poll catches a fragment
 * handler's exception, hands it to the Aeron client's error handler and keeps polling. So a record the reader refuses
 * used to be logged and skipped, and loadSnapshot reached END with that state missing; a refused header left the load
 * waiting forever for a header. Every check here goes through a real IPC snapshot publication and the member's own
 * loadSnapshot(Image), as the container does on restart. The in-memory codec tests never pass through Image.poll.
 */
class SnapshotRealChannelRestoreTest {

    @Test
    void anIntactSnapshotRestoresByteIdentical() throws Exception {
        final MatchingEngineClusteredService source = SnapshotPackedRoundTripTest.populated(1_000);
        final MatchingEngineClusteredService target = restoreOverIpc(source::onTakeSnapshot, 4400);
        assertArrayEquals(SnapshotPackedRoundTripTest.flat(source), SnapshotPackedRoundTripTest.flat(target));
    }

    /** A security whose multiplier is 0 is refused by the reader; the load must fail, not restore without it. */
    @Test
    void aRefusedRecordMidStreamFailsTheLoadInsteadOfBeingSkipped() throws Exception {
        final List<byte[]> records = records(SnapshotPackedRoundTripTest.populated(1_000));
        boolean corrupted = false;
        for (final byte[] r : records) {
            if (new UnsafeBuffer(r).getInt(0) == MatchingEngineClusteredService.T_SECURITY) {
                new UnsafeBuffer(r).putLong(44, 0L);   // multiplier 0: refused ("must never enforce un-multiplied caps")
                corrupted = true;
                break;
            }
        }
        assertTrue(corrupted, "the fixture must hold a security record");
        final AssertionError e = assertThrows(AssertionError.class, () -> restoreOverIpc(offerAll(records), 4401),
            "a refused record was skipped and the load completed without it");
        assertTrue(String.valueOf(e.getMessage()).contains("refused"), e.getMessage());
    }

    /** A snapshot from a newer build is refused at the header; the load must fail with that reason, not hang. */
    @Test
    void aTooNewHeaderFailsTheLoadInsteadOfHanging() throws Exception {
        final List<byte[]> records = records(SnapshotPackedRoundTripTest.populated(10));
        new UnsafeBuffer(records.get(0)).putInt(4, MatchingEngineClusteredService.SNAPSHOT_FORMAT + 1);
        final AssertionError e = assertThrows(AssertionError.class, () -> restoreOverIpc(offerAll(records), 4402));
        assertTrue(String.valueOf(e.getMessage()).contains("NEWER"), "the reader's own refusal must surface: " + e.getMessage());
    }

    static List<byte[]> records(final MatchingEngineClusteredService s) {
        final List<byte[]> out = new ArrayList<>();
        s.writeSnapshot((b, o, l) -> out.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        return out;
    }

    static Consumer<ExclusivePublication> offerAll(final List<byte[]> records) {
        return pub -> {
            for (final byte[] r : records) {
                final UnsafeBuffer u = new UnsafeBuffer(Arrays.copyOf(r, r.length));
                while (pub.offer(u, 0, r.length) < 0) Thread.onSpinWait();
            }
        };
    }

    /** {@code write} fills a real exclusive publication on the cluster snapshot channel, read by the member's own private
     *  loadSnapshot(Image) on another thread; rethrows the reader's failure, and fails if it does not finish. */
    static MatchingEngineClusteredService restoreOverIpc(final Consumer<ExclusivePublication> write, final int streamId)
            throws Exception {
        final String ch = io.aeron.cluster.service.ClusteredServiceContainer.Configuration.SNAPSHOT_CHANNEL_DEFAULT;
        try (io.aeron.driver.MediaDriver driver = io.aeron.driver.MediaDriver.launchEmbedded(new io.aeron.driver.MediaDriver.Context()
                 .threadingMode(io.aeron.driver.ThreadingMode.SHARED).dirDeleteOnShutdown(true));
             io.aeron.Aeron aeron = io.aeron.Aeron.connect(new io.aeron.Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
             io.aeron.Subscription sub = aeron.addSubscription(ch, streamId);
             ExclusivePublication pub = aeron.addExclusivePublication(ch, streamId)) {
            while (!pub.isConnected() || sub.imageCount() == 0) Thread.onSpinWait();
            final MatchingEngineClusteredService target = new MatchingEngineClusteredService();
            target.initEngine();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final Thread reader = new Thread(() -> {
                try {
                    final java.lang.reflect.Method m = MatchingEngineClusteredService.class.getDeclaredMethod("loadSnapshot", io.aeron.Image.class);
                    m.setAccessible(true);
                    m.invoke(target, sub.imageAtIndex(0));
                } catch (final java.lang.reflect.InvocationTargetException e) {
                    failure.set(e.getCause());
                } catch (final Throwable e) {
                    failure.set(e);
                }
            });
            reader.start();
            write.accept(pub);
            reader.join(20_000);
            if (reader.isAlive()) {
                reader.interrupt();
                throw new AssertionError("loadSnapshot did not finish within 20 s (a swallowed refusal?)");
            }
            if (failure.get() != null) {
                throw new AssertionError("loadSnapshot refused the snapshot: " + failure.get(), failure.get());
            }
            return target;
        }
    }
}
