package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-OPL-11: the per-order cost of the clustered service's apply path, on one thread and with no cluster, as the
 * allocation gate drives it: decode, risk with a keyed idempotency table at its retention frontier (every NEW evicts
 * one), a crossing match, and the output drain. Opt-in ({@code APPLY_COST_BENCH=1}); prints ns per order for each
 * window and the median, so a change is judged old against new on the same machine in the same hour.
 *
 * {@code APPLY_COST_VARIANT} switches the FR-OPL-12 knobs on for the run.
 *
 * Orders cross between two accounts (same-account self-trade prevention would cancel them) and alternate direction
 * each pair, so positions return to zero and no limit is reached. Every NEW carries its own key.
 */
class ServiceApplyCostBenchTest {
    private static final long PX = 1_000_000L;
    private static final int SELLER = 11, BUYER = 12, SECURITY = 1;

    @Test
    @EnabledIfEnvironmentVariable(named = "APPLY_COST_BENCH", matches = "1")
    void applyCostPerOrder() throws Exception {
        final int warmup = Integer.parseInt(System.getenv().getOrDefault("APPLY_COST_WARMUP", "1500000"));
        final int perWindow = Integer.parseInt(System.getenv().getOrDefault("APPLY_COST_ORDERS", "2000000"));
        final int windows = Integer.parseInt(System.getenv().getOrDefault("APPLY_COST_WINDOWS", "9"));
        final String variant = System.getenv().getOrDefault("APPLY_COST_VARIANT", "");   // "", "ring", "timing", "ring,timing"
        final long[] nsPerOrder = new long[windows];
        final long[] cpuNsPerOrder = new long[windows];   // the thread's own CPU time: a busy machine preempts wall time
        final var mx = java.lang.management.ManagementFactory.getThreadMXBean();
        final long[] trades = { 0 };
        final Thread bench = new Thread(() -> {
            final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
            if (variant.contains("ring")) service.outputRingSignal = false;      // CLUSTER_OUTPUT_RING_WAIT=none
            if (variant.contains("timing")) service.engineTiming = false;        // CLUSTER_ENGINE_TIMING=off
            service.initEngine();
            service.outputSink(out -> { if (out.kind == OutputEvent.KIND_TRADE_BOOKED) trades[0]++; });
            final AeronReplicationCodec codec = new AeronReplicationCodec();
            final InputEvent event = new InputEvent();
            final UnsafeBuffer buf = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
            long timestamp = 1_000_000_000_000L;
            long version = 0;
            for (final int account : new int[] { SELLER, BUYER }) {
                event.type = InputEvent.TYPE_ACCOUNT_CONTROL;
                event.accountId = account;
                event.setControlEnabled(true);
                event.setControlVersion(++version);
                codec.encodeInput(buf, 0, event, 0, 0, 0);
                service.onSessionMessage(null, ++timestamp, buf, 0, AeronReplicationCodec.INPUT_BYTES, null);
            }
            event.type = InputEvent.TYPE_SECURITY_CONTROL;
            event.accountId = 0;
            event.securityId = SECURITY;
            event.setControlVersion(++version);
            codec.encodeInput(buf, 0, event, 0, 0, 0);
            service.onSessionMessage(null, ++timestamp, buf, 0, AeronReplicationCodec.INPUT_BYTES, null);
            service.engine().bootstrapOrder((1 << 26) - 1, SELLER, SECURITY + 1, InputEvent.SIDE_BUY,
                1, 1, PX, RestingOrder.STATUS_NEW, PX, 0, 0L, 0L);
            event.type = InputEvent.TYPE_PRICE_TICK;
            event.side = 0;
            event.securityId = SECURITY;
            event.priceTicks = 150 * PX;
            codec.encodeInput(buf, 0, event, 0, 0, 0);
            service.onSessionMessage(null, ++timestamp, buf, 0, AeronReplicationCodec.INPUT_BYTES, null);

            long key = 0x9e3779b97f4a7c15L;
            long order = 0;
            for (int w = -1; w < windows; w++) {
                final int n = w < 0 ? warmup : perWindow;
                final long start = System.nanoTime(), cpuStart = mx.getCurrentThreadCpuTime();
                for (int i = 0; i < n; i++, order++) {
                    final boolean first = (order & 1) == 0;            // the resting half of a pair
                    final boolean flip = ((order >> 1) & 1) == 1;      // direction alternates per pair
                    event.type = InputEvent.TYPE_ORDER_NEW;
                    event.orderRef = 0;
                    event.accountId = first ? SELLER : BUYER;
                    event.side = first == flip ? InputEvent.SIDE_BUY : InputEvent.SIDE_SELL;
                    event.securityId = SECURITY;
                    event.qty = 10;
                    event.limitPx = 150 * PX;
                    key = key * 6364136223846793005L + 1442695040888963407L;
                    event.setClientOrderKey(key == 0 ? 1 : key);
                    codec.encodeInput(buf, 0, event, 0, 0, 0);
                    service.onSessionMessage(null, ++timestamp, buf, 0, AeronReplicationCodec.INPUT_BYTES, null);
                }
                if (w >= 0) { nsPerOrder[w] = (System.nanoTime() - start) / n; cpuNsPerOrder[w] = (mx.getCurrentThreadCpuTime() - cpuStart) / n; }
            }
        }, "apply-cost-bench");
        bench.start();
        bench.join(900_000);
        final long[] sorted = nsPerOrder.clone(), cpuSorted = cpuNsPerOrder.clone();
        Arrays.sort(sorted);
        Arrays.sort(cpuSorted);
        System.out.println("APPLY-COST variant=[" + variant + "] ns/order windows=" + Arrays.toString(nsPerOrder) + " median=" + sorted[windows / 2]
            + " min=" + sorted[0] + " cpu-median=" + cpuSorted[windows / 2] + " cpu-min=" + cpuSorted[0] + " trades=" + trades[0]);
        // every pair crosses: a bench that books nothing measures the reject path
        assertTrue(trades[0] > (long) (warmup + (long) perWindow * windows) / 2 * 9 / 10,
            "the bench did not cross its orders: " + trades[0] + " trades");
    }
}
