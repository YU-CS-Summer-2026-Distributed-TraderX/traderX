package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-104 (FR-XOD-19..22) under the cluster apply-path gate (NFR-AC01): FIX NEWs tagged for the engine's session ring,
 * through the real {@code onSessionMessage} entry on one thread, two consecutive exact-zero windows, run in
 * {@code clusterAllocationGateTest} with ClusterServiceAllocationGateTest's flags. The mix covers every ring decision in
 * steady state, with the ring full and evicting on every new order:
 * <ul>
 *   <li>a new ClOrdID (miss: books, fills against the venue, its answer recorded; the oldest entry evicted);</li>
 *   <li>a PossResend of the previous ClOrdID (hit: answered from the ring, never executed);</li>
 *   <li>a PossResend of a ClOrdID long evicted (AGED_OUT: refused, no ref);</li>
 *   <li>ticks and a cancel of an unknown ref, as the inherited gate.</li>
 * </ul>
 * Each message is encoded in place every time (codec and tail into one presized buffer), since each new order needs its
 * own key. The session's arrays are allocated on its first order, in warm-up, as on a member.
 */
class SessionRingAllocationGateTest {
    private static final long PX = 1_000_000L;
    private static final int ACCOUNT = 11;
    private static final int SECURITY = 1;
    private static final int PRESIZE_REF = (1 << 21) - 1;
    private static final long SESSION = 0x5E55_10E0L;
    private static final long KEY = 0x0C10_0000_0000L;

    @Test
    void taggedFixNewsAreAllocationFreeInSteadyState() throws Exception {
        final var baseMx = ManagementFactory.getThreadMXBean();
        assumeTrue(baseMx instanceof com.sun.management.ThreadMXBean);
        final var threadMx = (com.sun.management.ThreadMXBean) baseMx;
        assumeTrue(threadMx.isThreadAllocatedMemorySupported());
        threadMx.setThreadAllocatedMemoryEnabled(true);

        final int warmupEvents = Integer.getInteger("gate.warmupEvents", 250_000);
        final int steadyStateEvents = Integer.getInteger("gate.steadyStateEvents", 1_000_000);

        final AtomicLong threadId = new AtomicLong(-1);
        final int[] consecutiveZero = { 0 };
        final long[] lastDelta = { -1 };
        final long[] decisions = new long[3];   // hits answered, aged refusals, ring sessions: the mix really ran
        final Thread gate = new Thread(() -> {
            threadId.set(Thread.currentThread().threadId());
            final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
            service.initEngine();
            final Mix mix = new Mix(service);

            for (int i = 0; i < warmupEvents; i++) {
                mix.apply(i);
            }
            assertTrue(service.risk().sessionRing().aged(SESSION), "the ring is full and evicting before measuring");

            final long ownId = Thread.currentThread().threadId();
            for (int window = 0; window < 4 && consecutiveZero[0] < 2; window++) {
                threadMx.getThreadAllocatedBytes(ownId);
                final long before = threadMx.getThreadAllocatedBytes(ownId);
                for (int i = 0; i < steadyStateEvents; i++) {
                    mix.apply(i);
                }
                final long delta = threadMx.getThreadAllocatedBytes(ownId) - before;
                lastDelta[0] = delta;
                consecutiveZero[0] = delta == 0 ? consecutiveZero[0] + 1 : 0;
            }
            decisions[0] = mix.hits;
            decisions[1] = mix.agedOut;
            decisions[2] = service.risk().sessionRing().size();
        }, "session-ring-gate");

        gate.start();
        gate.join(300_000);
        assertEquals(1, decisions[2], "one session ring");
        assertTrue(decisions[0] > 0 && decisions[1] > 0, "hits " + decisions[0] + ", aged " + decisions[1]);
        assertEquals(2, consecutiveZero[0],
            "tagged FIX apply path did not produce two consecutive exact-zero windows; last delta=" + lastDelta[0] + " bytes");
    }

    /**
     * ADR-104 format 16 (NFR-XOD-05): the snapshot's ring section, 600 full rings (600 heads, 19,200 entry records,
     * ~26 MB) through the production SnapshotPacker into a copying sink, allocates nothing: two consecutive exact-zero
     * windows after warm-up. Format 15 built a 776-long {@code long[]} per record here (~30 MB per snapshot).
     */
    @Test
    void theSnapshotRingSectionAllocatesNothing() throws Exception {
        final var threadMx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threadMx.isThreadAllocatedMemorySupported());
        threadMx.setThreadAllocatedMemoryEnabled(true);
        final long[] result = { -1, -1, 0, 0 };   // last delta, consecutive zero windows, records, bytes
        final Thread gate = new Thread(() -> {
            final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
            service.initEngine();
            final finos.traderx.ordermatcher.risk.SessionRing ring = service.risk().sessionRing();
            for (int s = 0; s < 600; s++) {
                for (int i = 0; i < ring.n() + 9; i++) {
                    ring.admit(SESSION + s, KEY + i, i, i + 1, false, false, i);
                    ring.record(i + 1, (byte) 1);
                }
            }
            final byte[] sink = new byte[1 << 12];
            final MatchingEngineClusteredService.SnapshotWriter copy = (b, o, l) -> {
                result[2]++;
                result[3] += l;
                b.getBytes(o, sink, 0, l);
            };
            final MatchingEngineClusteredService.SnapshotPacker packer =
                new MatchingEngineClusteredService.SnapshotPacker(SnapshotPackedRoundTripTest.PAYLOAD, copy);
            for (int i = 0; i < 5; i++) {
                service.writeSessionRings(packer);
                packer.flush();
            }
            final long own = Thread.currentThread().threadId();
            for (int window = 0; window < 4 && result[1] < 2; window++) {
                result[2] = result[3] = 0;
                threadMx.getThreadAllocatedBytes(own);
                final long before = threadMx.getThreadAllocatedBytes(own);
                service.writeSessionRings(packer);
                packer.flush();
                result[0] = threadMx.getThreadAllocatedBytes(own) - before;
                result[1] = result[0] == 0 ? result[1] + 1 : 0;
            }
        }, "snapshot-ring-gate");
        gate.start();
        gate.join(300_000);
        assertTrue(result[3] > 25_000_000L, "600 full rings went through the packer: " + result[3] + " bytes");
        assertEquals(2, result[1], "the ring section allocated; last delta=" + result[0] + " bytes");
    }

    /** The event mix, encoded in place; counts the ring's answers off its egress acks (reason, ack byte 22). */
    private static final class Mix implements io.aeron.cluster.service.ClientSession {
        static final byte AGED_OUT = (byte) finos.traderx.ordermatcher.risk.RiskReason.SESSION_AGED_OUT.ordinal();
        final MatchingEngineClusteredService service;
        final AeronReplicationCodec codec = new AeronReplicationCodec();
        final InputEvent event = new InputEvent();
        final UnsafeBuffer buf = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES + AeronReplicationCodec.SESSION_TAIL_BYTES]);
        long timestamp = 1_000_000_000_000L;
        int msgSeq = 1;
        long lastKey;
        long bookedRef, ackRef;
        byte ackReason;
        long hits, agedOut;

        Mix(final MatchingEngineClusteredService service) {
            this.service = service;
            event.type = InputEvent.TYPE_ACCOUNT_CONTROL;
            event.accountId = ACCOUNT;
            event.setControlEnabled(true);
            event.setControlVersion(1L);
            send(0);
            event.type = InputEvent.TYPE_SECURITY_CONTROL;
            event.accountId = 0;
            event.securityId = SECURITY;
            event.setControlVersion(2L);
            send(0);
            service.engine().bootstrapOrder(PRESIZE_REF, ACCOUNT, SECURITY + 1, InputEvent.SIDE_BUY,
                1, 1, PX, RestingOrder.STATUS_NEW, PX, 0, 0L, 0L);
        }

        void apply(final int i) {
            switch (i % 6) {
                case 0 -> { lastKey = KEY + i; order(lastKey, 0); bookedRef = ackRef; }
                case 2 -> {   // a hit is answered with the original's ref; nothing new is booked
                    order(lastKey, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);
                    if (bookedRef > 0 && ackRef == bookedRef) hits++;
                }
                case 3 -> {
                    order(KEY - 1, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);
                    if (ackReason == AGED_OUT) agedOut++;
                }
                case 5 -> {
                    event.type = InputEvent.TYPE_ORDER_CANCEL;
                    event.orderRef = 1;   // long since evicted: the not-found path
                    send(0);
                }
                default -> {
                    event.type = InputEvent.TYPE_PRICE_TICK;
                    event.side = 0;
                    event.securityId = SECURITY;
                    event.priceTicks = 150 * PX;
                    send(0);
                }
            }
        }

        /** A marketable buy (fills fully, goes terminal, recycles), tagged with the session tail. */
        void order(final long key, final int poss) {
            event.type = InputEvent.TYPE_ORDER_NEW;
            event.side = InputEvent.SIDE_BUY;
            event.orderRef = 0;
            event.accountId = ACCOUNT;
            event.securityId = SECURITY;
            event.qty = 10;
            event.limitPx = 200 * PX;
            event.priceTicks = key;
            codec.encodeInput(buf, 0, event, 0, 0, AeronReplicationCodec.INPUT_FLAG_SESSION_KEYED | poss);
            final int len = AeronReplicationCodec.appendSessionTail(buf, 0, AeronReplicationCodec.INPUT_BYTES, SESSION,
                (int) (key * 0x9E3779B97F4A7C15L >>> 32), msgSeq++);
            service.onSessionMessage(this, ++timestamp, buf, 0, len, null);
        }

        void send(final int flags) {
            service.onSessionMessage(this, ++timestamp, buf, 0, codec.encodeInput(buf, 0, event, 0, 0, flags), null);
        }

        @Override public long offer(final org.agrona.DirectBuffer b, final int o, final int l) {
            ackRef = MatchingEngineClusteredService.ackRef(b, o);   // one 40-byte ack per apply, offered synchronously
            ackReason = b.getByte(o + 22);
            return 1;
        }
        @Override public long id() { return 7; }
        @Override public int responseStreamId() { return 0; }
        @Override public String responseChannel() { return "gate"; }
        @Override public byte[] encodedPrincipal() { return PRINCIPAL; }
        @Override public void close() { }
        @Override public boolean isClosing() { return false; }
        @Override public long offer(final io.aeron.DirectBufferVector[] v) { throw new UnsupportedOperationException(); }
        @Override public long tryClaim(final int length, final io.aeron.logbuffer.BufferClaim claim) { throw new UnsupportedOperationException(); }
        private static final byte[] PRINCIPAL = new byte[0];
    }
}
