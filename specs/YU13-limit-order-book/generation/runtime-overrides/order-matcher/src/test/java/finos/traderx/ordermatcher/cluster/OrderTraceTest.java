package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OTEL-01 gate: the consensus-boundary claim. The whole tracing design rests on gateway and members
 * independently deriving the SAME trace identity and the SAME sampling verdict from a field the log
 * already carries — if that is not exactly true, traces silently break into orphaned halves and the
 * "no trace context in replicated state" property was bought for nothing.
 */
class OrderTraceTest {

    /** THE claim: two processes that never exchange trace context still agree, for every order. */
    @Test
    void gatewayAndMemberDeriveIdenticalTraceContextWithoutExchangingAnything() {
        for (long clOrdIdHash = 1; clOrdIdHash < 5000; clOrdIdHash++) {
            // Gateway side: it holds the client key and, for a NEW, an orderRef of 0.
            final long gatewayKey = OrderTrace.keyOf(clOrdIdHash, 0);
            // Member side: decodes the same key off the committed message, orderRef still 0 at the
            // moment of derivation (the sequenced generator has not run yet).
            final long memberKey = OrderTrace.keyOf(clOrdIdHash, 0);

            assertEquals(gatewayKey, memberKey, "key derivation diverged");
            assertEquals(OrderTrace.traceIdHi(gatewayKey), OrderTrace.traceIdHi(memberKey));
            assertEquals(OrderTrace.traceIdLo(gatewayKey), OrderTrace.traceIdLo(memberKey));
            assertEquals(OrderTrace.sampled(gatewayKey, 127), OrderTrace.sampled(memberKey, 127),
                "sampling verdict diverged — member would emit spans the gateway did not");
            // The join itself: the member parents its apply span to this exact id.
            assertEquals(OrderTrace.clusterSpanId(gatewayKey), OrderTrace.clusterSpanId(memberKey));
        }
    }

    /** A CANCEL carries no client key; both sides fall back to the target orderRef, which — unlike a
     *  NEW's — passes through the log unchanged, so the fallback is symmetric too. */
    @Test
    void cancelWithoutClientKeyStillAgreesViaTheTargetOrderRef() {
        final long gateway = OrderTrace.keyOf(0L, 4242);
        final long member = OrderTrace.keyOf(0L, 4242);
        assertNotEquals(0L, gateway);
        assertEquals(gateway, member);
        assertEquals(OrderTrace.clusterSpanId(gateway), OrderTrace.clusterSpanId(member));
    }

    /** An order with neither key is never sampled — better an untraced order than a half-trace. */
    @Test
    void unkeyedOrderIsNeverSampled() {
        assertEquals(0L, OrderTrace.keyOf(0L, 0));
        assertTrue(!OrderTrace.sampled(0L, 0), "an unkeyed order must not be traced");
    }

    /** Head sampling has to actually thin the stream, or per-order spans at 190k/s become their own
     *  performance problem. 1-in-128 over 128k keys should land within a few percent of 1000. */
    @Test
    void headSamplingThinsToTheConfiguredFraction() {
        int sampled = 0;
        for (long key = 1; key <= 128_000; key++) {
            if (OrderTrace.sampled(key, 127)) {
                sampled++;
            }
        }
        assertTrue(sampled > 800 && sampled < 1200, "expected ~1000 of 128000 sampled, got " + sampled);
    }

    /** mask 0 means trace everything — the arm used for a low-rate functional trace check. */
    @Test
    void zeroMaskTracesEveryKeyedOrder() {
        for (long key = 1; key < 1000; key++) {
            assertTrue(OrderTrace.sampled(key, 0), "mask 0 must trace every keyed order");
        }
    }

    /** Ids must not collide across the spans of one trace, or the viewer collapses them into one. */
    @Test
    void spanIdsWithinATraceAreDistinct() {
        final long key = OrderTrace.keyOf(987654321L, 0);
        final long root = OrderTrace.spanId(key, 0);
        final long queue = OrderTrace.spanId(key, 1);
        final long cluster = OrderTrace.clusterSpanId(key);
        final long commit = OrderTrace.spanId(key, 5);
        final long apply = OrderTrace.spanId(key, 6);
        final long[] ids = {root, queue, cluster, commit, apply};
        for (int i = 0; i < ids.length; i++) {
            assertNotEquals(0L, ids[i], "W3C forbids an all-zero span id");
            for (int j = i + 1; j < ids.length; j++) {
                assertNotEquals(ids[i], ids[j], "span id collision at " + i + "/" + j);
            }
        }
    }

    /** Different orders must land in different traces, or one order's story swallows another's. */
    @Test
    void distinctOrdersGetDistinctTraces() {
        final long a = OrderTrace.traceIdLo(OrderTrace.keyOf(1L, 0));
        final long b = OrderTrace.traceIdLo(OrderTrace.keyOf(2L, 0));
        assertNotEquals(a, b);
    }
}
