package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import io.aeron.DirectBufferVector;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The packed snapshot framing (CLUSTER_SNAPSHOT_PACKED) is complete and deterministic iff the records reaching
 * onSnapshotRecord are the unpacked writer's, byte for byte and in order; the record CONTENT (format 13) is unchanged
 * and covered by SnapshotCompletenessAuditTest. Checked here on a populated state (both sides of a crossing flow,
 * full idempotency table, retained terminals, resting orders):
 *   1. packing: every message <= the payload bound, starts T_PACKED, and unpacks to exactly the unpacked stream that
 *      the flag-off (allocating) writer produced, so the zero-allocation idempotency path is checked against it too;
 *   2. restore: a service restored from the packed stream re-snapshots (unpacked) byte-identical to the original;
 *   3. behaviour: the same tail inputs - new orders, crossing fills, and a RETRY of a pre-snapshot client key - give
 *      byte-identical egress and byte-identical snapshots on original and restored (order refs continue, the retry
 *      answers from idempotency);
 *   4. fail closed: a pack missing its last message, an overrunning length, and records after END are all refused;
 *   5. mixed: the unpacked stream still restores through the same reader (flag-off members, old snapshots);
 *   6. real transport: onTakeSnapshot into an ExclusivePublication on the cluster's snapshot channel, restored by the
 *      member's own private loadSnapshot(Image) from the subscription, for both framings.
 * Every check must hold; the ring checks (7) also cover evictions, a key retry and the restore path.
 */
class SnapshotPackedRoundTripTest {
    static final int A = 42422, B = 22214;
    static final long PX = 150_000_000L;
    static final int PAYLOAD = 1376;   // IPC/UDP maxPayloadLength at the default 1408 MTU
    static int failures;
    static long ts = 1_000_000_000_000L;

    @Test
    void packedAndRingSnapshotsAreByteIdenticalAndRestore() throws Exception {
        failures = 0;
        final MatchingEngineClusteredService original = populated(400_000);

        MatchingEngineClusteredService.SNAPSHOT_PACKED = false;
        final List<byte[]> plain = new ArrayList<>();
        original.writeSnapshot((b, o, l) -> plain.add(copy(b, o, l)));
        MatchingEngineClusteredService.SNAPSHOT_PACKED = true;   // the zero-allocation idempotency write path
        final List<byte[]> packed = new ArrayList<>();
        final MatchingEngineClusteredService.SnapshotPacker packer =
            new MatchingEngineClusteredService.SnapshotPacker(PAYLOAD, (b, o, l) -> packed.add(copy(b, o, l)));
        original.writeSnapshot(packer);
        packer.flush();
        System.out.printf("state: %d records / %d bytes unpacked -> %d packed messages%n", plain.size(),
            plain.stream().mapToInt(x -> x.length).sum(), packed.size());

        System.out.println("1. packing");
        final List<byte[]> unpacked = new ArrayList<>();
        boolean bounded = true;
        for (final byte[] m : packed) {
            final UnsafeBuffer u = new UnsafeBuffer(m);
            bounded &= m.length <= PAYLOAD && u.getInt(0) == MatchingEngineClusteredService.T_PACKED;
            for (int at = 4; at < m.length; ) { final int n = u.getInt(at); unpacked.add(Arrays.copyOfRange(m, at + 4, at + 4 + n)); at += 4 + n; }
        }
        check(bounded, "every message <= " + PAYLOAD + " bytes and a T_PACKED container");
        check(same(plain, unpacked), "unpacks to exactly the unpacked stream (" + unpacked.size() + " records)");
        check(packed.size() * 20 < plain.size(), "at least 20x fewer publication offers (" + plain.size() + " -> " + packed.size() + ")");

        System.out.println("2. restore from packed");
        final MatchingEngineClusteredService restored = restore(packed);
        check(Arrays.equals(flat(original), flat(restored)), "restored re-snapshots byte-identical to the original");

        System.out.println("3. behaviour after restore: same tail, same egress, same state");
        final long tsAtSnapshot = ts;
        final List<byte[]> egressOriginal = tail(original, tsAtSnapshot);
        final List<byte[]> egressRestored = tail(restored, tsAtSnapshot);
        check(!egressOriginal.isEmpty() && same(egressOriginal, egressRestored),
            "tail egress byte-identical (" + egressOriginal.size() + " records, incl. a pre-snapshot key retry)");
        check(Arrays.equals(flat(original), flat(restored)), "post-tail snapshots byte-identical");

        System.out.println("4. fail closed");
        check(refused(packed.subList(0, packed.size() - 1), "truncated"), "a pack missing its last message is refused");
        final List<byte[]> overrun = new ArrayList<>(packed);
        final byte[] bad = overrun.get(1).clone();
        new UnsafeBuffer(bad).putInt(4, bad.length);   // first record claims more bytes than the message holds
        overrun.set(1, bad);
        check(refused(overrun, "overruns"), "an overrunning record length is refused");
        final List<byte[]> afterEnd = new ArrayList<>(packed);
        final byte[] last = afterEnd.get(afterEnd.size() - 1);
        final byte[] extended = Arrays.copyOf(last, last.length + 8);
        new UnsafeBuffer(extended).putInt(last.length, 4);
        new UnsafeBuffer(extended).putInt(last.length + 4, MatchingEngineClusteredService.T_END);
        afterEnd.set(afterEnd.size() - 1, extended);
        check(refused(afterEnd, "after END"), "records after END in a pack are refused");

        System.out.println("5. the unpacked stream still restores through the same reader");
        final MatchingEngineClusteredService fromPlain = restore(plain);
        final MatchingEngineClusteredService ref = restore(packed);
        check(Arrays.equals(flat(fromPlain), flat(ref)), "unpacked and packed restores agree");

        System.out.println("6. real transport: onTakeSnapshot -> snapshot channel -> loadSnapshot(Image)");
        final byte[] want = flat(original);
        try (io.aeron.driver.MediaDriver driver = io.aeron.driver.MediaDriver.launchEmbedded(new io.aeron.driver.MediaDriver.Context()
                 .threadingMode(io.aeron.driver.ThreadingMode.SHARED).dirDeleteOnShutdown(true));
             io.aeron.Aeron aeron = io.aeron.Aeron.connect(new io.aeron.Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()))) {
            for (final boolean p : new boolean[] {false, true}) {
                MatchingEngineClusteredService.SNAPSHOT_PACKED = p;
                final String ch = io.aeron.cluster.service.ClusteredServiceContainer.Configuration.SNAPSHOT_CHANNEL_DEFAULT;
                try (io.aeron.Subscription sub = aeron.addSubscription(ch, 4242 + (p ? 1 : 0));
                     io.aeron.ExclusivePublication pub = aeron.addExclusivePublication(ch, 4242 + (p ? 1 : 0))) {
                    while (!pub.isConnected() || sub.imageCount() == 0) Thread.onSpinWait();
                    final MatchingEngineClusteredService target = new MatchingEngineClusteredService();
                    target.initEngine();
                    final Thread reader = new Thread(() -> {
                        try {
                            final java.lang.reflect.Method m = MatchingEngineClusteredService.class.getDeclaredMethod("loadSnapshot", io.aeron.Image.class);
                            m.setAccessible(true);
                            m.invoke(target, sub.imageAtIndex(0));
                        } catch (final Exception e) { throw new RuntimeException(e); }
                    });
                    reader.start();
                    original.onTakeSnapshot(pub);
                    reader.join(60_000);
                    check(!reader.isAlive() && Arrays.equals(want, flat(target)), (p ? "packed" : "unpacked")
                        + " framing over a real publication (maxPayload " + pub.maxPayloadLength() + ") restores byte-identical");
                }
            }
        }

        System.out.println("7. ring walk (CLUSTER_SNAPSHOT_RING) writes the identical stream");
        final MatchingEngineClusteredService fresh = populated(400_000);
        final MatchingEngineClusteredService again = restore(packed);           // bootstrap path keeps the ring too
        for (final Object[] c : new Object[][] {{fresh, "populated (800,050 orders over a 262,144 table: evictions)"},
                                                {again, "restored from packed (bootstrapIdempotency)"}}) {
            final MatchingEngineClusteredService s = (MatchingEngineClusteredService) c[0];
            MatchingEngineClusteredService.SNAPSHOT_PACKED = true;
            MatchingEngineClusteredService.SNAPSHOT_RING = false; final byte[] probe = flat(s);
            MatchingEngineClusteredService.SNAPSHOT_RING = true; final byte[] ring = flat(s);
            check(Arrays.equals(probe, ring), "ring == hash-probe stream, " + c[1] + " (" + ring.length + " bytes)");
            tail(s, ts);
            MatchingEngineClusteredService.SNAPSHOT_RING = false; final byte[] probe2 = flat(s);
            MatchingEngineClusteredService.SNAPSHOT_RING = true; final byte[] ring2 = flat(s);
            check(Arrays.equals(probe2, ring2), "ring == hash-probe stream after a tail with a key retry, " + c[1]);
        }
        MatchingEngineClusteredService.SNAPSHOT_RING = false;

        MatchingEngineClusteredService.SNAPSHOT_RING = true;
        MatchingEngineClusteredService.SNAPSHOT_PACKED = false;
        assertEquals(0, failures, "snapshot round-trip checks failed (see output)");
    }

    /** Crossing pairs (qty 1) with distinct client keys, then a few resting orders at other prices. */
    static MatchingEngineClusteredService populated(final int pairs) {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        for (final int acct : new int[] {A, B}) {
            final InputEvent e = new InputEvent();
            e.type = InputEvent.TYPE_ACCOUNT_CONTROL; e.accountId = acct; e.setControlEnabled(true); e.setControlVersion(1);
            apply(s, null, e);
        }
        final InputEvent sec = new InputEvent();
        sec.type = InputEvent.TYPE_SECURITY_CONTROL; sec.securityId = 0; sec.setControlEnabled(true); sec.setControlVersion(2);
        apply(s, null, sec);
        final InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK; tick.securityId = 0; tick.priceTicks = PX;
        apply(s, null, tick);
        for (int i = 0; i < 2 * pairs; i++) apply(s, null, order(i, PX, 1));
        for (int i = 0; i < 50; i++) apply(s, null, order(2 * pairs + i, PX - (1 + i % 5) * 1_000_000L, 3));  // resting bids
        return s;
    }

    static InputEvent order(final int i, final long px, final int qty) {
        final InputEvent o = new InputEvent();
        o.type = InputEvent.TYPE_ORDER_NEW; o.accountId = (i & 1) == 0 ? A : B; o.securityId = 0;
        o.side = (i & 1) == 0 || px != PX ? InputEvent.SIDE_BUY : InputEvent.SIDE_SELL; o.qty = qty; o.limitPx = px;
        o.setClientOrderKey(1L + i);
        return o;
    }

    /** New crossing orders, one that hits the resting bids, and a retry of the LAST pre-snapshot key. */
    static List<byte[]> tail(final MatchingEngineClusteredService s, final long fromTs) {
        ts = fromTs;
        final Capture c = new Capture();
        for (int i = 0; i < 40; i++) apply(s, c, order(10_000_000 + i, PX, 1 + i % 3));
        final InputEvent sweep = order(10_000_100, PX - 10_000_000L, 20);
        sweep.side = InputEvent.SIDE_SELL; sweep.accountId = B;
        apply(s, c, sweep);
        apply(s, c, order(2 * 400_000 + 49, PX - 5_000_000L, 3));   // retry: same key as the last resting bid
        return c.egress;
    }

    static MatchingEngineClusteredService restore(final List<byte[]> messages) {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        boolean done = false;
        for (final byte[] m : messages) {
            if (done) throw new IllegalStateException("message after END");
            done = s.onSnapshotMessage(new UnsafeBuffer(m), 0, m.length);
        }
        if (!done) throw new IllegalStateException("snapshot truncated: end of stream before END record");
        return s;
    }

    static boolean refused(final List<byte[]> messages, final String why) {
        try {
            restore(messages);
            return false;
        } catch (final IllegalStateException e) {
            System.out.println("     refused: " + e.getMessage());
            return e.getMessage().contains(why);
        }
    }

    static byte[] flat(final MatchingEngineClusteredService s) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.writeSnapshot((b, o, l) -> out.writeBytes(copy(b, o, l)));
        return out.toByteArray();
    }

    static void apply(final MatchingEngineClusteredService s, final ClientSession session, final InputEvent e) {
        final AeronReplicationCodec codec = new AeronReplicationCodec();
        final UnsafeBuffer in = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
        codec.encodeInput(in, 0, e, 0L, 0, 0);
        s.onSessionMessage(session, ++ts, in, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }

    static boolean same(final List<byte[]> a, final List<byte[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    static byte[] copy(final DirectBuffer b, final int o, final int l) {
        final byte[] x = new byte[l];
        b.getBytes(o, x);
        return x;
    }

    static void check(final boolean ok, final String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static final class Capture implements ClientSession {
        final List<byte[]> egress = new ArrayList<>();
        @Override public long id() { return 7; }
        @Override public int responseStreamId() { return 0; }
        @Override public String responseChannel() { return "test"; }
        @Override public byte[] encodedPrincipal() { return new byte[0]; }
        @Override public void close() { }
        @Override public boolean isClosing() { return false; }
        @Override public long offer(final DirectBuffer buffer, final int offset, final int length) { egress.add(copy(buffer, offset, length)); return 1; }
        @Override public long offer(final DirectBufferVector[] vectors) { throw new UnsupportedOperationException(); }
        @Override public long tryClaim(final int length, final BufferClaim claim) { throw new UnsupportedOperationException(); }
    }
}
