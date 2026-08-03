package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
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

    // ----- OTEL-01 follow-up: outcome escalation and the log join ---------------------------------

    /**
     * THE escalation claim, and the reason there is no collector-side tail sampler: an order the
     * head threw away is recovered by BOTH tiers, or by neither. The predicate is a pure function of
     * the committed ack kind — no key, no mask, no per-side state — so there is nothing for gateway
     * and member to disagree about, and a reject can never become a half-trace.
     */
    @Test
    void aRejectedOrderIsEscalatedByBothSidesOrNeither() {
        int escalatedOutsideTheSample = 0;
        for (long clOrdIdHash = 1; clOrdIdHash < 5000; clOrdIdHash++) {
            final long key = OrderTrace.keyOf(clOrdIdHash, 0);
            // Both sides run the same two predicates over the same two inputs.
            final boolean gatewaySampled = OrderTrace.sampled(key, 127);
            final boolean memberSampled = OrderTrace.sampled(key, 127);
            final boolean gatewayEscalates = OrderTrace.escalate(OutputEvent.KIND_ORDER_REJECTED);
            final boolean memberEscalates = OrderTrace.escalate(OutputEvent.KIND_ORDER_REJECTED);
            assertEquals(gatewaySampled || gatewayEscalates, memberSampled || memberEscalates,
                "one tier would emit spans for this reject and the other would not — half-trace");
            if (!gatewaySampled) {
                escalatedOutsideTheSample++;
            }
        }
        // The point of the feature: the overwhelming majority of rejects are NOT in the head sample,
        // which is exactly why the head verdict alone loses them.
        assertTrue(escalatedOutsideTheSample > 4000,
            "expected most rejects to fall outside a 1-in-128 head sample, got "
                + escalatedOutsideTheSample);
    }

    /** Only genuine rejections escalate. A fill or a cancel is a normal outcome and stays on the
     *  head verdict — otherwise "error sampling" quietly becomes "sample everything". */
    @Test
    void onlyRejectionsEscalate() {
        assertTrue(OrderTrace.escalate(OutputEvent.KIND_ORDER_REJECTED));
        assertTrue(OrderTrace.escalate(OutputEvent.KIND_ORDER_NOT_FOUND));
        assertTrue(!OrderTrace.escalate(OutputEvent.KIND_ORDER_ACCEPTED));
        assertTrue(!OrderTrace.escalate(OutputEvent.KIND_ORDER_FILLED));
        assertTrue(!OrderTrace.escalate(OutputEvent.KIND_ORDER_PARTIALLY_FILLED));
        assertTrue(!OrderTrace.escalate(OutputEvent.KIND_ORDER_CANCELED));
        assertTrue(!OrderTrace.escalate((byte) 0), "no direct ack must never escalate");
    }

    /**
     * THE log-join claim: the id a log line prints is character-for-character the id the spans were
     * emitted under. The exporter writes the trace id as two 16-char hex halves
     * ({@code SpanSink.appendHex16} over traceIdHi then traceIdLo); a log line that disagreed with
     * that by so much as a leading zero would query Loki and Tempo for two different traces while
     * looking perfectly correct on screen.
     */
    @Test
    void logLineTraceIdMatchesTheIdTheSpansAreEmittedUnder() {
        for (long clOrdIdHash = 1; clOrdIdHash < 5000; clOrdIdHash++) {
            final long key = OrderTrace.keyOf(clOrdIdHash, 0);
            final String onTheWire = String.format("%016x%016x",
                OrderTrace.traceIdHi(key), OrderTrace.traceIdLo(key));
            assertEquals(onTheWire, OrderTrace.traceIdHex(key), "log line and span id diverged");
        }
    }

    /** W3C/Tempo want exactly 32 lower-case hex characters, zero-padded — a short id is not a
     *  cosmetic problem, Tempo simply will not find the trace. */
    @Test
    void logLineTraceIdIsAlways32LowerCaseHexCharacters() {
        for (long clOrdIdHash = 1; clOrdIdHash < 2000; clOrdIdHash++) {
            final String hex = OrderTrace.traceIdHex(OrderTrace.keyOf(clOrdIdHash, 0));
            assertEquals(32, hex.length(), "trace id must be 32 chars, got " + hex);
            assertTrue(hex.matches("[0-9a-f]{32}"), "not lower-case hex: " + hex);
        }
    }
}
