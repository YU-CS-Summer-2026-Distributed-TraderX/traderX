package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.risk.StreamDedupe;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Arrays;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Measures the leader's snapshot pause by part (ADR-094 decomposition): the service's writeSnapshot time and bytes per
 * record type, for both framings, on the 400K-hour state shape: a full 262,144-key idempotency table, retained
 * terminal order rows, resting orders, and 0 / 2,048 / 8,192 ADR-090 streams with full 256-answer rings. Then
 * onTakeSnapshot over a real IPC snapshot publication, so the offer cost is included. The cost run is printed, not
 * asserted (wall clock on a shared runner), and runs only when asked: SNAPSHOT_PAUSE_COST=1 ./gradlew test --tests
 * '*SnapshotPauseCostTest*' -i. The real-channel restore of full streams (ADR-090 FR-XOD-18) is asserted and always runs.
 */
class SnapshotPauseCostTest {
    static final int RUNS = 7;

    @Test
    @EnabledIfEnvironmentVariable(named = "SNAPSHOT_PAUSE_COST", matches = "1")
    void decomposeTheSnapshotPause() throws Exception {
        final MatchingEngineClusteredService s = SnapshotPackedRoundTripTest.populated(400_000);
        for (final int streams : new int[] {0, 2_048, 8_192}) {
            fillStreams(s.risk().streams(), streams);
            System.out.printf("%n=== streams=%d (full %d-answer rings) ===%n", streams, s.risk().streams().window());
            for (final boolean packed : new boolean[] {false, true}) {
                MatchingEngineClusteredService.SNAPSHOT_PACKED = packed;
                MatchingEngineClusteredService.SNAPSHOT_RING = true;
                try {
                    byType(s, packed);
                } catch (final IllegalStateException e) {
                    System.out.println((packed ? "packed+ring" : "unpacked") + " writeSnapshot REFUSED: " + e.getMessage());
                }
            }
            for (final boolean packed : new boolean[] {false, true}) {
                MatchingEngineClusteredService.SNAPSHOT_PACKED = packed;
                try {
                    overIpc(s, packed);
                } catch (final Exception e) {
                    System.out.println((packed ? "packed+ring" : "unpacked") + " onTakeSnapshot over IPC FAILED: " + e);
                }
            }
        }
        MatchingEngineClusteredService.SNAPSHOT_PACKED = false;
    }

    /**
     * ADR-090 FR-XOD-18 regression: a full stream's T_STREAM record (4 + 8 x (7 + 256) = 2,108 bytes) is larger than one
     * 1,376-byte snapshot message. Before the fix the unpacked writer's record arrived fragmented and loadSnapshot
     * refused it ("snapshot corrupt: stream 0"), and the packer refused to write it. Through the member's own
     * onTakeSnapshot, the real IPC snapshot channel and its own loadSnapshot(Image), both framings must restore the
     * snapshot byte-identical, streams included.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "packed={0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aSnapshotHoldingFullStreamsRestoresOverTheRealChannel(final boolean packed) throws Exception {
        final MatchingEngineClusteredService original = SnapshotPackedRoundTripTest.populated(1_000);
        fillStreams(original.risk().streams(), 3);
        final String framing = packed ? "packed" : "unpacked";
        MatchingEngineClusteredService.SNAPSHOT_PACKED = packed;
        try {
            final byte[] want = SnapshotPackedRoundTripTest.flat(original);
            final MatchingEngineClusteredService target = restoreOverIpc(original, 4300 + (packed ? 1 : 0));
            assertEquals(3, target.risk().streams().size(), framing + ": streams restored");
            for (int i = 0; i < 3; i++) {
                assertArrayEquals(original.risk().streams().tuple(i), target.risk().streams().tuple(i),
                    framing + ": stream " + i + " ring restored exactly");
            }
            assertArrayEquals(want, SnapshotPackedRoundTripTest.flat(target),
                framing + ": restored state re-snapshots byte-identical");
        } finally {
            MatchingEngineClusteredService.SNAPSHOT_PACKED = false;
        }
    }

    /**
     * FR-XOD-18: Image.poll hands a handler's exception to the client's error handler and keeps polling, so before the
     * fix a refused record was logged and skipped and loadSnapshot returned normally with that state missing. Over the
     * real channel, a stream the reader must refuse (a too-new format header, then END) must make loadSnapshot throw.
     */
    @Test
    void aRefusedRecordFailsTheRealChannelRestoreInsteadOfBeingSkipped() throws Exception {
        final MatchingEngineClusteredService source = SnapshotPackedRoundTripTest.populated(10);
        final java.util.List<byte[]> records = new java.util.ArrayList<>();
        source.writeSnapshot((b, o, l) -> records.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        final byte[] header = records.get(0).clone();
        new org.agrona.concurrent.UnsafeBuffer(header).putInt(4, MatchingEngineClusteredService.SNAPSHOT_FORMAT + 1);
        final byte[] end = records.get(records.size() - 1);
        final AssertionError e = org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
            () -> restoreOverIpc(pub -> {
                for (final byte[] r : new byte[][] {header, end}) {
                    while (pub.offer(new org.agrona.concurrent.UnsafeBuffer(r), 0, r.length) < 0) Thread.onSpinWait();
                }
            }, 4310));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(e.getMessage()).contains("NEWER"),
            "the reader's own refusal must surface: " + e.getMessage());
    }

    static MatchingEngineClusteredService restoreOverIpc(final MatchingEngineClusteredService source, final int streamId)
            throws Exception {
        return restoreOverIpc(source::onTakeSnapshot, streamId);
    }

    /** {@code write} fills a real exclusive publication on the cluster snapshot channel, read by the member's own private
     *  loadSnapshot(Image) on another thread, as the container does on restart; rethrows the reader's failure. */
    static MatchingEngineClusteredService restoreOverIpc(final java.util.function.Consumer<io.aeron.ExclusivePublication> write,
                                                         final int streamId) throws Exception {
        final String ch = io.aeron.cluster.service.ClusteredServiceContainer.Configuration.SNAPSHOT_CHANNEL_DEFAULT;
        try (io.aeron.driver.MediaDriver driver = io.aeron.driver.MediaDriver.launchEmbedded(new io.aeron.driver.MediaDriver.Context()
                 .threadingMode(io.aeron.driver.ThreadingMode.SHARED).dirDeleteOnShutdown(true));
             io.aeron.Aeron aeron = io.aeron.Aeron.connect(new io.aeron.Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
             io.aeron.Subscription sub = aeron.addSubscription(ch, streamId);
             io.aeron.ExclusivePublication pub = aeron.addExclusivePublication(ch, streamId)) {
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
            reader.join(30_000);
            if (reader.isAlive()) {
                reader.interrupt();
                throw new AssertionError("loadSnapshot did not reach END within 30 s");
            }
            if (failure.get() != null) {
                throw new AssertionError("loadSnapshot refused the member's own snapshot: " + failure.get(), failure.get());
            }
            return target;
        }
    }

    /** Every stream admitted and answered window + 44 times, so its ring is full and its record carries window answers. */
    static void fillStreams(final StreamDedupe d, final int target) {
        for (int i = d.size(); i < target; i++) {
            for (long seq = 1; seq <= d.window() + 44; seq++) {
                if (d.admit(SnapshotPackedRoundTripTest.A, 1_000_000L + i, seq, 1_000L) != StreamDedupe.ADMIT) {
                    throw new IllegalStateException("stream " + i + " seq " + seq + " not admitted");
                }
                d.record((int) (seq + i), (byte) 1);
            }
        }
    }

    /** Median per-type nanoseconds over RUNS writes into a writer that copies the bytes (as an offer would). */
    static void byType(final MatchingEngineClusteredService s, final boolean packed) {
        final TreeMap<Integer, long[]> nanos = new TreeMap<>(), bytes = new TreeMap<>(), count = new TreeMap<>();
        final long[] total = new long[RUNS];
        final byte[] sink = new byte[1 << 16];
        for (int warm = 0; warm < 3; warm++) write(s, packed, sink, null, null, null, 0);
        for (int r = 0; r < RUNS; r++) total[r] = write(s, packed, sink, nanos, bytes, count, r);
        Arrays.sort(total);
        long allBytes = 0;
        for (final long[] b : bytes.values()) allBytes += b[0];
        System.out.printf("%-12s writeSnapshot median %.2f ms (min %.2f, max %.2f), %.1f MB%n",
            packed ? "packed+ring" : "unpacked", total[RUNS / 2] / 1e6, total[0] / 1e6, total[RUNS - 1] / 1e6, allBytes / 1e6);
        for (final var e : nanos.entrySet()) {
            final long[] n = e.getValue().clone();
            Arrays.sort(n);
            if (n[RUNS / 2] > 50_000 || bytes.get(e.getKey())[0] > 100_000) {
                System.out.printf("    type %2d  %-14s %8d records %8.2f MB  %6.2f ms%n", e.getKey(), name(e.getKey()),
                    count.get(e.getKey())[0], bytes.get(e.getKey())[0] / 1e6, n[RUNS / 2] / 1e6);
            }
        }
    }

    /** One timed write; time is charged to the record type being produced (the gap since the previous record). */
    static long write(final MatchingEngineClusteredService s, final boolean packed, final byte[] sink,
                      final TreeMap<Integer, long[]> nanos, final TreeMap<Integer, long[]> bytes,
                      final TreeMap<Integer, long[]> count, final int run) {
        // The clock is read only where the record type changes (a run of one type is one interval), so 263K records
        // do not pay two nanoTime calls each; the interval is charged to the type that just ended.
        final long[] last = {System.nanoTime()};
        final int[] prevType = {-1};
        final long start = last[0];
        final MatchingEngineClusteredService.SnapshotWriter recordSink = (b, o, l) -> {
            if (nanos != null) {
                final int type = b.getInt(o);
                if (type != prevType[0]) {
                    final long now = System.nanoTime();
                    if (prevType[0] >= 0) nanos.computeIfAbsent(prevType[0], k -> new long[RUNS])[run] += now - last[0];
                    last[0] = now;
                    prevType[0] = type;
                }
                if (run == 0) {
                    bytes.computeIfAbsent(type, k -> new long[1])[0] += l;
                    count.computeIfAbsent(type, k -> new long[1])[0]++;
                }
            }
            b.getBytes(o, sink, 0, Math.min(l, sink.length));
        };
        if (packed) {
            // Charge per record: pack into a real packer, but observe each record on its way in.
            final MatchingEngineClusteredService.SnapshotPacker packer = new MatchingEngineClusteredService.SnapshotPacker(
                SnapshotPackedRoundTripTest.PAYLOAD, (b, o, l) -> b.getBytes(o, sink, 0, Math.min(l, sink.length)));
            s.writeSnapshot((b, o, l) -> {
                recordSink.write(b, o, l);
                packer.write(b, o, l);
            });
            packer.flush();
        } else {
            s.writeSnapshot(recordSink);
        }
        return System.nanoTime() - start;
    }

    /** onTakeSnapshot into a real IPC exclusive publication on the cluster snapshot channel, drained by a reader. */
    static void overIpc(final MatchingEngineClusteredService s, final boolean packed) throws Exception {
        final long[] took = new long[RUNS];
        try (io.aeron.driver.MediaDriver driver = io.aeron.driver.MediaDriver.launchEmbedded(new io.aeron.driver.MediaDriver.Context()
                 .threadingMode(io.aeron.driver.ThreadingMode.SHARED).dirDeleteOnShutdown(true));
             io.aeron.Aeron aeron = io.aeron.Aeron.connect(new io.aeron.Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()))) {
            final String ch = io.aeron.cluster.service.ClusteredServiceContainer.Configuration.SNAPSHOT_CHANNEL_DEFAULT;
            for (int r = -2; r < RUNS; r++) {
                try (io.aeron.Subscription sub = aeron.addSubscription(ch, 5000 + r + 2);
                     io.aeron.ExclusivePublication pub = aeron.addExclusivePublication(ch, 5000 + r + 2)) {
                    while (!pub.isConnected() || sub.imageCount() == 0) Thread.onSpinWait();
                    final boolean[] stop = {false};
                    final Thread drain = new Thread(() -> {
                        final io.aeron.logbuffer.FragmentHandler h = (DirectBuffer b, int o, int l, io.aeron.logbuffer.Header hd) -> { };
                        while (!stop[0]) if (sub.poll(h, 256) == 0) Thread.onSpinWait();
                        while (sub.poll(h, 256) > 0) { }
                    });
                    drain.start();
                    final long t0 = System.nanoTime();
                    s.onTakeSnapshot(pub);
                    final long t = System.nanoTime() - t0;
                    stop[0] = true;
                    drain.join();
                    if (r >= 0) took[r] = t;
                }
            }
        }
        Arrays.sort(took);
        System.out.printf("%-12s onTakeSnapshot over IPC median %.2f ms (min %.2f, max %.2f)%n",
            packed ? "packed+ring" : "unpacked", took[RUNS / 2] / 1e6, took[0] / 1e6, took[RUNS - 1] / 1e6);
    }

    static String name(final int type) {
        for (final java.lang.reflect.Field f : MatchingEngineClusteredService.class.getDeclaredFields()) {
            try {
                if (f.getName().startsWith("T_") && f.getType() == int.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    if (f.getInt(null) == type) return f.getName();
                }
            } catch (final IllegalAccessException ignored) { }
        }
        return "?";
    }
}
