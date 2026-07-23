package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Stateless-forward order gateway (ADR-047): terminates REST and (optionally) FIX, screens
 * nothing away from the authoritative core (risk decides inside the cluster), and forwards
 * through the Aeron Cluster client — which follows the leader natively — answering each request
 * from the committed egress ack.
 *
 * The single-threaded Aeron Cluster client is owned by ONE loop thread; REST handler threads and
 * FIX session threads never touch it directly — they submit through {@link OrderSubmitter}, whose
 * work is serialized onto the owner thread (so there is no data race on the client). Because every
 * counterparty session lives on the front-end side of that seam, a leader-change reconnect on the
 * owner thread never disturbs a FIX session (ADR-047 failover transparency; proven by
 * {@code FixGatewaySurvivalTest}).
 *
 * <p>PIPELINED per-order ingress (NFR-AC02, extended from the batch path to single orders): the
 * owner thread OFFERS each order-lifecycle command (new/cancel/replace) into the log and returns
 * immediately — it does NOT block on that order's commit. The waiting moves off the owner thread
 * onto the submitting REST/FIX thread, which parks on a {@link CompletableFuture}. Acks stream back
 * on the ONE cluster session in FIFO offer order — exactly one direct (non-resting) lifecycle/
 * not-found ack per offer, the same invariant {@code handleBatch} counts on — so a FIFO of awaiting
 * requests reconciles them by position ({@link Inflight}). One owner thread thus keeps MANY orders
 * in flight instead of one commit-RTT at a time: the ~580/s/gateway synchronous ceiling was the
 * commit wait, not compute. Per-session FIX ordering is preserved for free — a session thread
 * offers its orders in order and blocks for each ack, so its acks return in that order; across
 * sessions they interleave, which is the whole win. The in-flight window is bounded by a permit
 * semaphore ({@code GATEWAY_MAX_INFLIGHT}) = client backpressure. Honesty: this raises throughput
 * and cuts latency UNDER LOAD by removing queueing; it does NOT cut unloaded single-order latency
 * — a client still waits one commit (~1.7ms) for its own order.
 *
 * <p>ponytail: FIFO correlation assumes reliable, ordered egress — true at the 1 MiB term geometry
 * the campaign settled on (Aeron NAK-repairs gaps in order). A reconnect drains outstanding pendings
 * to ambiguous, keeping the FIFO aligned with the fresh session. A mid-session dropped ack (only
 * possible below the repair window at tiny term sizes) would misalign the FIFO; the durable fix is
 * an echoed correlation id in the ack, which needs an ack-format field (a deterministic-core change,
 * out of scope for a gateway-only lever).
 *
 * Split readiness (ADR-045): {@code /ready} is 200 only while the cluster session is live.
 */
public final class ClusterGatewayMain implements OrderSubmitter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long ACK_TIMEOUT_MS = 10_000;
    private static final long BATCH_FENCE_RETRY_MS = 5;
    // Max order-lifecycle commands in flight per gateway before a submitter is backpressured. Set
    // well above any tested session count so the binding constraint is the consensus commit rate,
    // not the window; also caps how hard the ingress term / egress ring is filled.
    private static final int MAX_INFLIGHT = Integer.parseInt(env("GATEWAY_MAX_INFLIGHT", "4096"));

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final InputEvent event = new InputEvent();
    private final UnsafeBuffer orderBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer symbolBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
    private final Map<String, Integer> idByTicker = new HashMap<>();
    // Tasks that touch the cluster client; run ONLY on the owner thread.
    private final LinkedBlockingQueue<FutureTask<?>> tasks = new LinkedBlockingQueue<>();
    // Pipelined order-lifecycle correlation (new/cancel/replace). FIFO + inputSeq boundary are
    // owner-thread-confined (see Inflight); only the permit semaphore and each order's
    // CompletableFuture cross the thread seam.
    private final Inflight inflight = new Inflight(MAX_INFLIGHT);

    private String ingressEndpoints;
    private String aeronDir;
    private String[] endpointEntries;
    // Persists across reconnects so the single-endpoint fallback does not restart at the same
    // (possibly dead) endpoint every time — see connectCycling().
    private int connectRotation;
    private AeronCluster client;
    private volatile boolean connected;
    private volatile boolean running = true;

    // Owner-thread-only ack scratch (set by the egress listener between poll calls). Order-lifecycle
    // acks no longer use a single slot — they complete the head of the pipelined FIFO (see onEgress).
    private long[] lastTradeAck;   // {kind, riskReason} — market-trade (/trades) committed decision
    private long[] lastSymbolAck;  // {appliedSeq, symbolId, requestId}
    private long nextSymbolRequestId = 1;
    // Pipelined-batch ack accounting (owner thread only; pollEgress runs on the owner thread).
    // Acks per session are FIFO in log order, so counting order-lifecycle acks matches offers.
    // YU13 (FR-LOB07): a crossing book interleaves counterparty RESTING-order updates on the
    // same egress stream — every ack now carries a resting-class byte and correlation counts
    // only direct (non-resting) order-lifecycle acks, so the count matches offers exactly.
    private boolean batchActive;
    private int batchOutstanding;
    private int batchAccepted;
    // A sequenced cancel of reserved orderRef=0 is offered after the final batch order. New-order
    // batches can never emit KIND_ORDER_NOT_FOUND for ref 0, so that ack is an unambiguous fence
    // whose appliedSeq is beyond every earlier order on this session. Repeated cancel fences are
    // side-effect-free and cover a dropped fence ack without touching reference or risk state.
    private boolean batchFenceAwaiting;
    private long batchFenceAppliedSeq = -1;
    private volatile long batchFenceOffers;
    private volatile long batchHighWaterCompletions;
    private volatile long batchHighWaterTimeouts;
    // Bench metrics: every committed fill-kind egress ack is a booked order (run-gke-bench.sh
    // reads traderx_order_events_total{event="fill"}). Written on the owner thread, read racily
    // by the /metrics handler — plain volatile longs.
    private volatile long fillEvents;
    private volatile long acceptedOrders;
    private volatile long canceledOrders;
    private volatile long replacedOrders;
    // Market-trade (/trades, the UI create-order path) outcome counters — the market-trade path
    // emits KIND_TRADE_BOOKED/REJECTED, neither of which the order-lifecycle metrics above count,
    // so without these a stage mis-seed books nothing with no visible signal. Owner-thread writes.
    // Gated on the ack's market-trade byte: YU13 crossing fills also emit KIND_TRADE_BOOKED, and
    // counting those here would drown the market-trade signal in ordinary order flow.
    private volatile long marketTradesBooked;
    private volatile long marketTradesRejected;

    public static void main(final String[] args) throws Exception {
        new ClusterGatewayMain().run();
    }

    private void run() throws Exception {
        ingressEndpoints = env("GATEWAY_INGRESS_ENDPOINTS", "0=localhost:21802");
        endpointEntries = ingressEndpoints.split(",");
        aeronDir = env("GATEWAY_AERON_DIR", "/dev/shm/aeron-gateway");
        final int httpPort = Integer.parseInt(env("GATEWAY_HTTP_PORT", "18110"));

        final MediaDriver driver = MediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true));

        // Owner thread: it alone connects, offers, polls egress, and reconnects.
        final Thread owner = new Thread(this::ownerLoop, "cluster-client-owner");
        owner.setDaemon(true);
        owner.start();
        awaitConnected();

        final HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 64);
        // 64: under pipelined-batch load every in-flight batch parks one HTTP thread on its
        // owner-queue future (up to ~12s each); with only 8 threads the readiness probe starved
        // behind them and k8s pulled the gateway out of the Service mid-bench.
        server.setExecutor(Executors.newFixedThreadPool(64));
        server.createContext("/orders/batch", this::handleBatch);
        server.createContext("/orders", this::handleOrder);
        // Deliberately NOT /orders/cancel. HttpServer routes by longest prefix, so during a rolling
        // gateway update an older replica has no /orders/cancel context and would hand the request
        // to /orders — measured: it sequenced the cancel body as a NEW order and returned an
        // orderRef. A cancel that silently books an order is the worst available failure mode; a
        // sibling path 404s on old replicas instead.
        server.createContext("/cancel", this::handleCancel);
        server.createContext("/replace", this::handleReplace);
        server.createContext("/trades", this::handleTrade);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/seed", this::handleSeed);
        server.createContext("/ready", exchange ->
            respond(exchange, connected ? 200 : 503, "{\"connected\":" + connected + "}"));
        server.createContext("/health", exchange ->
            respond(exchange, 200, "{\"connected\":" + connected + "}"));
        server.start();

        final String fixPortEnv = env("FIX_ACCEPTOR_PORT", "");
        if (!fixPortEnv.isEmpty()) {
            final List<String> compIds = Arrays.asList(env("FIX_SESSION_COMPIDS", "CLIENT1").split(","));
            final FixGatewayAcceptor fix = new FixGatewayAcceptor(this, Integer.parseInt(fixPortEnv),
                env("FIX_TARGET_COMP_ID", "TRADERX"),
                Integer.parseInt(env("FIX_DEFAULT_ACCOUNT", "11")), compIds);
            fix.start();
            Runtime.getRuntime().addShutdownHook(new Thread(fix::stop));
        }
        System.out.println("GATEWAY up: http=" + httpPort + " ingress=" + ingressEndpoints
            + (fixPortEnv.isEmpty() ? "" : " fix=" + fixPortEnv));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            server.stop(0);
            CloseHelper.quietCloseAll(client, driver);
        }));
        Thread.currentThread().join();
    }

    // ----- owner thread: sole cluster-client user --------------------------------------------

    private void ownerLoop() {
        connectCycling();
        long lastReconnect = 0;
        while (running) {
            try {
                // While orders are in flight their acks arrive ONLY via pollEgress, so the owner must
                // never block on the task queue then: a 50ms block with the queue drained (every
                // session parked on its own future) gates the whole pipeline at ~depth/0.05 orders/s
                // — measured as a hard 1.2k/s ceiling before this. So poll non-blocking and spin
                // pollEgress whenever the window is non-empty; block briefly ONLY when truly idle
                // (depth 0, nothing to drain) to avoid a busy-spin at rest.
                final FutureTask<?> task = inflight.depth() == 0
                    ? tasks.poll(50, TimeUnit.MILLISECONDS)
                    : tasks.poll();
                if (task != null) {
                    task.run(); // does its own offer + pollEgress; exceptions captured in the future
                }
                if (client != null) {
                    client.pollEgress(); // drain committed acks -> complete pending futures each pass
                }
                if (client != null && client.isClosed()) {
                    final long now = System.currentTimeMillis();
                    if (now - lastReconnect > 1000) {
                        lastReconnect = now;
                        connected = false;
                        connectCycling();
                    }
                }
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (final Exception e) {
                connected = false;
                connectCycling();
            }
        }
    }

    /**
     * Reconnect to the cluster (owner thread only).
     *
     * The first attempt hands Aeron the COMPLETE member list so the cluster client resolves the
     * leader itself. This used to cycle single endpoints starting at a local {@code attempt = 0}
     * — i.e. always endpoint 0 first — so whenever member 0 was the member that died, every
     * reconnect blocked on the dead endpoint's connect timeout before trying a live one. Measured
     * on GKE: killing member 0 cost a 1270ms gateway-session gap vs 41ms killing member 2, a 31x
     * penalty decided purely by WHICH pod died, and the sole cause of the bimodal failover
     * distribution (~85-180ms fast mode vs ~670-850ms slow mode). Single-endpoint cycling is kept
     * as the fallback, with a rotating start so a dead endpoint is not retried first every time.
     */
    private void connectCycling() {
        // A fresh cluster session will not deliver the old session's outstanding egress, so complete
        // every in-flight pending as ambiguous (post-publish ambiguity: the order may have committed,
        // so the submitter must not claim rejection). Keeps the FIFO aligned with the new session.
        // No-op at startup (empty). Owner thread only — safe, no pollEgress runs inside here. drain()
        // also resets the inputSeq boundary in case a fresh epoch restarts appliedSeq.
        inflight.drain();
        int attempt = 0;
        while (running) {
            final String entry = attempt == 0
                ? ingressEndpoints
                : endpointEntries[(connectRotation + attempt) % endpointEntries.length];
            attempt++;
            try {
                CloseHelper.quietClose(client);
                client = AeronCluster.connect(new AeronCluster.Context()
                    .aeronDirectoryName(aeronDir)
                    .ingressChannel("aeron:udp?term-length=1m")
                    .ingressEndpoints(entry)
                    .egressChannel("aeron:udp?term-length=1m|endpoint="
                        + env("GATEWAY_EGRESS_HOST", env("POD_IP", "localhost")) + ":"
                        + env("GATEWAY_EGRESS_PORT", "0"))
                    .egressListener(this::onEgress));
                connectRotation = (connectRotation + 1) % endpointEntries.length;
                connected = true;
                return;
            } catch (final Exception e) {
                connected = false;
            }
        }
    }

    private void awaitConnected() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 60_000;
        while (!connected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    private void onEgress(final long clusterSessionId, final long timestamp, final DirectBuffer buffer,
                          final int offset, final int length, final io.aeron.logbuffer.Header header) {
        final byte kind = buffer.getByte(offset + 12);
        if (kind == MatchingEngineClusteredService.KIND_SYMBOL_REGISTERED) {
            lastSymbolAck = new long[] {
                buffer.getLong(offset), buffer.getInt(offset + 8), buffer.getLong(offset + 13) };
        } else if (OutputEvent.isOrderLifecycleKind(kind) || kind == OutputEvent.KIND_ORDER_NOT_FOUND) {
            if (batchActive && batchFenceAwaiting && kind == OutputEvent.KIND_ORDER_NOT_FOUND
                    && buffer.getInt(offset + 8) == 0) {
                batchFenceAppliedSeq = Math.max(batchFenceAppliedSeq, buffer.getLong(offset));
                return;
            }
            // Resting-class byte (FR-LOB07): 1 = counterparty resting-order update from someone
            // else's cross — never the direct response to an offer, so it must not complete a
            // pending or decrement batch accounting.
            final boolean restingUpdate = buffer.getByte(offset + 21) != 0;
            if (!restingUpdate) {
                if (batchActive) {
                    // Batch mode counts acks against outstanding offers (handleBatch holds the owner
                    // thread for the whole batch, so no pipelined single order can be in flight).
                    if (batchOutstanding > 0) {
                        batchOutstanding--;
                        if (kind != OutputEvent.KIND_ORDER_REJECTED && kind != OutputEvent.KIND_ORDER_NOT_FOUND) {
                            batchAccepted++;
                            acceptedOrders++;
                        }
                    }
                } else {
                    // Pipelined mode: the FIRST direct ack of each input (by inputSeq at offset 0)
                    // is that order's entry ack and answers the FIFO head; later direct acks with the
                    // same inputSeq are continuation fills of the SAME order (a crossing order emits
                    // ACCEPTED, then per-match-step FILLs, all under one appliedSeq) and must not pop
                    // again — that would shift every later order onto the wrong request. Mirrors the
                    // old sync path's first-ack-wins, now with many orders in flight.
                    final PendingOrder head = inflight.onDirectAck(buffer.getLong(offset));
                    if (head != null) {
                        completePipelinedHead(head, buffer, offset, kind);
                    }
                }
            }
        }
        // Booked-order metric: count every fill-kind ack — both sides of a cross count, direct
        // and resting alike; each is a booked trade (the engine's trade counter is the truth).
        if (kind == OutputEvent.KIND_ORDER_FILLED || kind == OutputEvent.KIND_ORDER_PARTIALLY_FILLED) {
            fillEvents++;
        }
        // Market-trade decision (/trades): KIND_TRADE_BOOKED (fresh accept) or KIND_TRADE_ACCEPTED
        // (idempotent replay) = booked; KIND_TRADE_REJECTED carries the RiskReason ordinal at 22.
        // Byte 23 gates this to outputs of a TYPE_TRADE_NEW apply — YU13's crossing book emits
        // KIND_TRADE_BOOKED for BOTH sides of every ordinary order match, so kind alone would let
        // a foreign fill answer (and inflate) the market-trade path. handleTrade offers with
        // lastTradeAck=null, so the first market-trade decision after its offer wins.
        if (buffer.getByte(offset + 23) != 0
                && (kind == OutputEvent.KIND_TRADE_BOOKED || kind == OutputEvent.KIND_TRADE_ACCEPTED
                    || kind == OutputEvent.KIND_TRADE_REJECTED)) {
            if (lastTradeAck == null) {
                lastTradeAck = new long[] {kind, buffer.getByte(offset + 22)};
            }
            if (kind == OutputEvent.KIND_TRADE_REJECTED) {
                marketTradesRejected++;
            } else {
                marketTradesBooked++;
            }
        }
    }

    /** Run a client-touching callable on the owner thread and wait for its result. */
    private <T> T onOwner(final java.util.concurrent.Callable<T> callable) throws Exception {
        return onOwner(callable, ACK_TIMEOUT_MS + 2_000);
    }

    private <T> T onOwner(final java.util.concurrent.Callable<T> callable, final long timeoutMs)
            throws Exception {
        final FutureTask<T> ft = new FutureTask<>(callable);
        tasks.add(ft);
        return ft.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Duplicate suppression key for a client order id (FR-IMRG14).
     *
     * <p>This deliberately does NOT port YU10's {@code ClOrdIdLedger}. That ledger is gateway-local,
     * file-backed and unbounded, and it lives on the Spring acceptor that does not run. The engine
     * already carries the authoritative mechanism: {@code BlpRiskState} keeps a bounded, LRU-evicted
     * {@code clientOrderKey -> (decision, orderRef)} table INSIDE the replicated state machine, it is
     * written to every snapshot in retention order, and {@code MatchingEngine.onNewOrder} answers a
     * repeat key by re-emitting the ORIGINAL order rather than creating a second one. All that was
     * missing was a gateway that supplies a key instead of the hardcoded 0. Consequences that a
     * gateway-local ledger could not give: three members agree, the verdict survives gateway restart
     * AND a reconnect onto a different gateway replica, and there is no rehydration step to cost.
     *
     * <p>Zero-allocation FNV-1a over the chars — no {@code getBytes}, no intermediate String. 64 bits
     * because a collision here means a DISTINCT order is silently answered with a previous order's
     * outcome; 32 bits would collide around 77k live keys.
     *
     * <p>Returns 0 for a null/blank id, and 0 is the engine's "no idempotency key" sentinel. That is
     * what keeps every existing bench harness behaving exactly as before: the batch path never sets a
     * key, and a REST order that omits {@code clientOrderId} stays key-less rather than colliding with
     * every other key-less order on a shared default.
     */
    /** {@code ,"reason":"CREDIT_LIMIT"} on a rejected result, empty otherwise. Cold path (one
     *  synchronous REST response), so the allocation here never reaches the batch ingress. */
    private static String reasonField(final ExecResult r) {
        if (r.accepted() || r.riskReason() < 0 || r.riskReason() >= RiskReason.values().length) {
            return "";
        }
        return ",\"reason\":\"" + RiskReason.values()[r.riskReason()] + "\"";
    }

    private static long clientOrderKey(final String clOrdId) {
        if (clOrdId == null || clOrdId.isEmpty()) {
            return 0L;
        }
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < clOrdId.length(); i++) {
            hash = (hash ^ clOrdId.charAt(i)) * 0x100000001b3L;
        }
        return hash == 0L ? 1L : hash;   // never collide with the "no key" sentinel
    }

    // ----- OrderSubmitter (called by REST + FIX threads) -------------------------------------

    @Override
    public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                  final char side, final int qty, final long limitPxTicks) {
        return submitPipelined(new PendingOrder(InputEvent.TYPE_ORDER_NEW, accountId, ticker, side,
            qty, limitPxTicks, clientOrderKey(clOrdId), 0));
    }

    /**
     * Cancel ingress (FR-LOB09). No new SBE template and no engine change: {@code TYPE_ORDER_CANCEL}
     * already rides {@link InputEvent}'s {@code orderRef} slot — it is the very message this gateway
     * offers as the pipelined-batch high-water fence — and {@code MatchingEngine.onCancel} already
     * unlinks the resting order, releases its risk reservation exactly once, and emits either
     * {@code KIND_ORDER_CANCELED} or {@code KIND_ORDER_NOT_FOUND}. What was missing was only a
     * caller that supplies a real orderRef instead of the reserved 0.
     *
     * <p>Correlation safety: {@code emitOrderNotFound} hardcodes orderRef 0 in its ack, so a
     * cancel-of-unknown cannot be correlated by ref — but it does not need to be, because the
     * pipelined FIFO correlates by POSITION: this cancel is registered at the FIFO tail when offered
     * and its (possibly ref-0 NOT_FOUND) ack completes the head in that same order. The batch fence
     * is untouched: {@code handleBatch} holds the owner thread for a whole batch, so no pipelined
     * cancel can interleave with the fence's own orderRef-0 NOT_FOUND ack.
     */
    @Override
    public ExecResult submitCancel(final int orderRef) {
        if (orderRef <= 0) {
            return new ExecResult(false, orderRef, OutputEvent.KIND_ORDER_NOT_FOUND); // 0 is the reserved fence ref
        }
        return submitPipelined(new PendingOrder(InputEvent.TYPE_ORDER_CANCEL, 0, null, (char) 0,
            0, 0L, 0L, orderRef));
    }

    /**
     * Atomic replace ingress (ADR-058). One sequenced {@code TYPE_ORDER_REPLACE}; the engine does
     * cancel-and-add in a single apply and the order keeps its orderRef, so there is no committed
     * state in which the client's order is gone but its replacement has not been accepted.
     *
     * <p>No SBE template was added. {@code AeronReplicationCodec} copies {@code commandType}
     * through without interpreting it, so a new {@link InputEvent} type rides template 1 exactly as
     * cancel does — which also avoids claiming a template id from a worktree that cannot see the
     * whole lineage (8 is already YU15's {@code RiskExtractMessage}).
     */
    @Override
    public ExecResult submitReplace(final int orderRef, final String clOrdId, final int qty,
                                    final long limitPxTicks) {
        if (orderRef <= 0) {
            return new ExecResult(false, orderRef, OutputEvent.KIND_ORDER_NOT_FOUND); // reserved fence ref
        }
        return submitPipelined(new PendingOrder(InputEvent.TYPE_ORDER_REPLACE, 0, null, (char) 0,
            qty, limitPxTicks, clientOrderKey(clOrdId), orderRef));
    }

    // ----- pipelined order-lifecycle ingress -------------------------------------------------

    /**
     * Submit one order-lifecycle command and block (on THIS thread, not the owner) for its committed
     * ack. The owner thread only offers and registers it on the FIFO; the ack completes it later.
     * The permit semaphore bounds in-flight orders and IS the client backpressure — a full window
     * parks the submitter here until a slot frees. Returns null on any ambiguity (window saturated
     * for the whole timeout, no committed ack, or a reconnect drained it): post-publish, the caller
     * must not claim rejection.
     */
    private ExecResult submitPipelined(final PendingOrder p) {
        try {
            if (!inflight.acquire(ACK_TIMEOUT_MS)) {
                return null; // window saturated: treat as ambiguous backpressure, never a false reject
            }
            // Fire-and-forget on the owner thread: offer + register, no per-order wait.
            tasks.add(new FutureTask<>(() -> offerPipelined(p), null));
            return p.future.get(ACK_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS);
        } catch (final Exception e) {
            return null; // ambiguous/timeout: caller must not claim rejection
        }
    }

    /**
     * Owner thread: encode {@code p} into the shared scratch, offer it (backpressure via pollEgress,
     * which also drains earlier pendings), then register it at the FIFO tail so its ack — the next
     * direct lifecycle ack in offer order — completes it. Registration happens AFTER the successful
     * offer and before any further pollEgress, so this order's own ack can never be processed before
     * it is registered. On an unresolvable ticker or an offer that never clears, complete ambiguous
     * and release the permit without registering (keeps the FIFO exactly one entry per live offer).
     */
    private void offerPipelined(final PendingOrder p) {
        try {
            if (p.type == InputEvent.TYPE_ORDER_NEW) {
                final int securityId = resolveSecurityId(p.ticker);
                if (securityId < 0) {
                    p.future.complete(null); // unresolvable ticker: ambiguous, exactly as the old sync path
                    inflight.release();
                    return;
                }
                event.type = InputEvent.TYPE_ORDER_NEW;
                event.side = p.side == 'S' ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY;
                event.orderRef = 0;
                event.accountId = p.accountId;
                event.securityId = securityId;
            } else { // cancel / replace: engine reads account+security off the original order
                event.type = p.type;
                event.side = 0;
                event.orderRef = p.orderRef;
                event.accountId = 0;
                event.securityId = 0;
            }
            event.qty = p.qty;
            event.limitPx = p.limitPx;
            event.priceTicks = p.clientKey;
            event.eventTimeMillis = 0;
            codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
            final long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
            while (client.offer(orderBuffer, 0, AeronReplicationCodec.INPUT_BYTES) < 0) {
                client.pollEgress(); // drains earlier acks (frees the ingress window) while backpressured
                if (System.currentTimeMillis() > deadline) {
                    p.future.complete(null); // never cleared the ingress: ambiguous, do not register
                    inflight.release();
                    return;
                }
            }
            inflight.register(p); // offer order == ack order
        } catch (final Exception e) {
            p.future.complete(null);
            inflight.release();
        }
    }

    /**
     * Owner thread (from onEgress): {@code p} is the FIFO head that {@link Inflight#onDirectAck}
     * just popped for this entry ack. Build its committed outcome and release its permit.
     */
    private void completePipelinedHead(final PendingOrder p, final DirectBuffer buffer,
                                       final int offset, final byte kind) {
        final byte riskReason = buffer.getByte(offset + 22);
        // NEW: orderRef is engine-assigned and carried in the ack. CANCEL/REPLACE: the ack echoes the
        // target ref, but emitOrderNotFound hardcodes 0, so trust the request's ref for those.
        final int ref = p.type == InputEvent.TYPE_ORDER_NEW ? buffer.getInt(offset + 8) : p.orderRef;
        final boolean accepted;
        if (p.type == InputEvent.TYPE_ORDER_CANCEL) {
            // Gone from the book. A retried cancel of an already-CANCELED order also reports CANCELED
            // (the engine re-publishes a terminal order unchanged, 009 parity) — idempotent, not an error.
            accepted = kind == OutputEvent.KIND_ORDER_CANCELED;
            if (accepted) {
                canceledOrders++;
            }
        } else {
            // NEW / REPLACE: any non-reject, non-not-found lifecycle kind means it stands (ACCEPTED,
            // or FILLED/PARTIALLY_FILLED if it crossed on the way in).
            accepted = kind != OutputEvent.KIND_ORDER_REJECTED && kind != OutputEvent.KIND_ORDER_NOT_FOUND;
            if (accepted) {
                if (p.type == InputEvent.TYPE_ORDER_REPLACE) {
                    replacedOrders++;
                } else {
                    acceptedOrders++;
                }
            }
        }
        p.future.complete(new ExecResult(accepted, ref, kind, riskReason));
        inflight.release();
    }

    // ----- REST -------------------------------------------------------------------------------

    /**
     * POST /replace {"orderRef":N,"quantity":Q,"limitPrice":P} — 200 replaced, 422 rejected (the
     * order still stands unchanged), 409 already terminal, 404 unknown.
     *
     * <p>A sibling path, deliberately NOT /orders/replace, for the same measured reason /cancel is:
     * {@code HttpServer} routes by longest prefix, so a replica rolled forward before its peers
     * would hand /orders/replace to /orders and book the replace body as a NEW order.
     */
    private void handleReplace(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            if (!body.hasNonNull("orderRef") || !body.hasNonNull("quantity")
                || !body.hasNonNull("limitPrice")) {
                respond(exchange, 400, "{\"error\":\"orderRef, quantity and limitPrice required\"}");
                return;
            }
            final int orderRef = body.get("orderRef").asInt();
            final ExecResult r = submitReplace(orderRef, body.path("clientOrderId").asText(""),
                body.get("quantity").asInt(),
                Math.round(body.get("limitPrice").asDouble() * 1_000_000d));
            if (r == null) {
                respond(exchange, 504, "{\"error\":\"no committed ack\"}");
                return;
            }
            final int code = r.accepted() ? 200
                : r.kind() == OutputEvent.KIND_ORDER_NOT_FOUND ? 404
                : r.kind() == OutputEvent.KIND_ORDER_REJECTED ? 422 : 409;
            respond(exchange, code, "{\"orderRef\":" + orderRef + ",\"kind\":" + r.kind()
                + ",\"replaced\":" + r.accepted() + reasonField(r) + "}");
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** POST /cancel {"orderRef":N} — 200 canceled, 409 already terminal, 404 unknown. */
    private void handleCancel(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            if (!body.hasNonNull("orderRef")) {
                respond(exchange, 400, "{\"error\":\"orderRef required\"}");
                return;
            }
            final int orderRef = body.get("orderRef").asInt();
            final ExecResult r = submitCancel(orderRef);
            if (r == null) {
                respond(exchange, 504, "{\"error\":\"no committed ack\"}");
                return;
            }
            final int code = r.accepted() ? 200
                : r.kind() == OutputEvent.KIND_ORDER_NOT_FOUND ? 404 : 409;
            respond(exchange, code, "{\"orderRef\":" + orderRef + ",\"kind\":" + r.kind()
                + ",\"canceled\":" + r.accepted() + "}");
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    private void handleOrder(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            final String ticker = body.hasNonNull("securityId")
                ? "#" + body.get("securityId").asInt() : body.path("ticker").asText("");
            final char side = "Sell".equalsIgnoreCase(body.path("side").asText("Buy")) ? 'S' : 'B';
            final int qty = body.path("quantity").asInt();
            final long px = Math.round(body.path("limitPrice").asDouble() * 1_000_000d);
            // Defaulted to "" rather than the old constant "rest": now that clOrdId feeds the
            // idempotency key, a shared default would make every key-less REST order a duplicate of
            // the first one. Empty means "no key", which is the pre-existing behaviour exactly.
            final ExecResult r = submitOrder(body.path("clientOrderId").asText(""),
                body.path("accountId").asInt(), ticker, side, qty, px);
            if (r == null) {
                respond(exchange, 504, "{\"error\":\"no committed ack\"}");
                return;
            }
            respond(exchange, r.accepted() ? 200 : 422,
                "{\"orderRef\":" + r.orderRef() + ",\"kind\":" + r.kind() + reasonField(r) + "}");
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** Bench load path: a JSON array of {accountId, security, side, quantity, limitPrice}.
     *  PIPELINED (NFR-AC02): the owner thread offers every order into the consensus log without
     *  per-order round trips and counts the acks as they stream back — the per-order committed-ack
     *  wait (~1.2ms each) was the ~1k/s ceiling; amortizing it is where YU11's 25k+/s came from. */
    private void handleBatch(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode arr = JSON.readTree(exchange.getRequestBody());
            final int total = arr.size();
            final int[] accounts = new int[total];
            final String[] tickers = new String[total];
            final char[] sides = new char[total];
            final int[] qtys = new int[total];
            final long[] pxs = new long[total];
            int i = 0;
            for (final JsonNode body : arr) {
                tickers[i] = body.hasNonNull("securityId")
                    ? "#" + body.get("securityId").asInt() : body.path("security").asText("");
                sides[i] = "Sell".equalsIgnoreCase(body.path("side").asText("Buy")) ? 'S' : 'B';
                accounts[i] = body.path("accountId").asInt();
                qtys[i] = body.path("quantity").asInt();
                pxs[i] = Math.round(body.path("limitPrice").asDouble() * 1_000_000d);
                i++;
            }
            final long batchBudgetMs = ACK_TIMEOUT_MS + total * 5L;
            final Integer batchResult = onOwner(() -> {
                final long deadline = System.currentTimeMillis() + batchBudgetMs;
                // Batch and pipelined single-order ingress are mutually exclusive: onEgress routes
                // direct acks to batch counting while batchActive, so any single order still in the
                // FIFO would never be completed. The bench never mixes them; drain defensively so a
                // mixed workload degrades to ambiguous singles rather than a stuck FIFO.
                inflight.drain();
                batchActive = true;
                batchOutstanding = 0;
                batchAccepted = 0;
                batchFenceAwaiting = false;
                batchFenceAppliedSeq = -1;
                try {
                    for (int n = 0; n < total; n++) {
                        final int securityId = resolveSecurityId(tickers[n]);
                        if (securityId < 0) {
                            continue; // unresolvable ticker: never offered, never acked
                        }
                        event.type = InputEvent.TYPE_ORDER_NEW;
                        event.side = sides[n] == 'S' ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY;
                        event.orderRef = 0;
                        event.accountId = accounts[n];
                        event.securityId = securityId;
                        event.qty = qtys[n];
                        event.limitPx = pxs[n];
                        event.priceTicks = 0L;
                        event.eventTimeMillis = 0;
                        codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
                        while (client.offer(orderBuffer, 0, AeronReplicationCodec.INPUT_BYTES) < 0) {
                            client.pollEgress(); // drains acks (frees ingress window) while backpressured
                            if (System.currentTimeMillis() > deadline) {
                                return batchAccepted;
                            }
                        }
                        batchOutstanding++;
                        client.pollEgress();
                    }
                    if (batchOutstanding > 0) {
                        event.type = InputEvent.TYPE_ORDER_CANCEL;
                        event.side = 0;
                        event.orderRef = 0;
                        event.accountId = 0;
                        event.securityId = 0;
                        event.qty = 0;
                        event.limitPx = 0;
                        event.priceTicks = 0;
                        event.eventTimeMillis = 0;
                        codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
                        batchFenceAwaiting = true;
                        long nextFenceAt = 0;
                        while (System.currentTimeMillis() < deadline) {
                            client.pollEgress();
                            if (batchFenceAppliedSeq >= 0) {
                                batchHighWaterCompletions++;
                                return batchAccepted;
                            }
                            final long now = System.currentTimeMillis();
                            if (now >= nextFenceAt) {
                                if (client.offer(orderBuffer, 0, AeronReplicationCodec.INPUT_BYTES) > 0) {
                                    batchFenceOffers++;
                                    nextFenceAt = now + BATCH_FENCE_RETRY_MS;
                                }
                            }
                            Thread.yield();
                        }
                        batchHighWaterTimeouts++;
                        return batchAccepted;
                    }
                    while (batchOutstanding > 0 && System.currentTimeMillis() < deadline) {
                        client.pollEgress();
                    }
                    return batchAccepted;
                } finally {
                    batchFenceAwaiting = false;
                    batchActive = false;
                }
            }, batchBudgetMs + 2_000);
            final int accepted = batchResult == null ? 0 : batchResult;
            // 201: the inherited bench harness (batch-load.mjs) counts a batch as accepted
            // only on 201 Created.
            respond(exchange, 201, "{\"accepted\":" + accepted + ",\"total\":" + total + "}");
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** Bench/ops seeding: POST {accountId, tickers:"JPM,GS,...", price} enables the account,
     *  registers+enables each ticker, and publishes a price — all through the sequenced
     *  ingress path (ADR-045: the consensus log is the only input). */
    /** Market trade from the trade ticket (FR-09B08), the path the web UI's create-order button
     *  takes: trade-service validates ticker + account, then POSTs the TradeOrder here. The engine
     *  books it at the security's last trade price (seeded by market-data ticks until the book
     *  first crosses — ADR-051) with no order and no matching, so the payload carries no price.
     *  SYNCHRONOUS by design: this path is the UI create-order button ONLY (one human click — the
     *  bench never touches it, it drives /orders + /orders/batch), so waiting for the committed
     *  decision costs no throughput that matters and lets us answer 200 booked / 422 + RiskReason
     *  on reject. Fire-and-forget here returned a green 200 on a risk-rejected trade and the order
     *  silently vanished — a reject leaves NO NATS/DB/UI trace (only a booked trade rides the
     *  /trades bridge), so the 200 was the only signal and it lied.
     *  422 and 504 are NOT interchangeable: 422 is a committed business rejection carrying its
     *  RiskReason; 504 means no committed decision arrived (a failover/timeout — ambiguous), and
     *  the trade may still commit. Never report an ambiguous outcome as a rejection. */
    private void handleTrade(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            final int accountId = body.path("accountId").asInt();
            final String ticker = body.hasNonNull("securityId")
                ? "#" + body.get("securityId").asInt() : body.path("security").asText("");
            final char side = "Sell".equalsIgnoreCase(body.path("side").asText("Buy")) ? 'S' : 'B';
            final int qty = body.path("quantity").asInt();
            final long[] ack = onOwner(() -> {
                final int securityId = resolveSecurityId(ticker);
                if (securityId < 0) { // unresolvable ticker: never offered — answer as the risk gate would
                    return new long[] {OutputEvent.KIND_TRADE_REJECTED, RiskReason.UNKNOWN_SECURITY.ordinal()};
                }
                event.type = InputEvent.TYPE_TRADE_NEW;
                event.side = side == 'S' ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY;
                event.orderRef = 0;
                event.accountId = accountId;
                event.securityId = securityId;
                event.qty = qty;
                event.limitPx = 0L;   // market trade: the engine stamps the BLP's last trade price
                event.priceTicks = 0L; // clientOrderKey slot; ticket dedup is deferred
                event.eventTimeMillis = 0;
                codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
                lastTradeAck = null;
                if (!offerAndAwait(orderBuffer, AeronReplicationCodec.INPUT_BYTES, () -> lastTradeAck != null)) {
                    return null; // no committed decision within timeout: ambiguous, NOT a reject
                }
                return lastTradeAck;
            });
            if (ack == null) {
                respond(exchange, 504, "{\"error\":\"no committed decision\"}");
                return;
            }
            if (ack[0] != OutputEvent.KIND_TRADE_REJECTED) {
                respond(exchange, 200, "{\"booked\":true}");
            } else {
                respond(exchange, 422, "{\"booked\":false,\"reason\":\"" + RiskReason.values()[(int) ack[1]] + "\"}");
            }
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    private void handleSeed(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            final int accountId = body.path("accountId").asInt();
            final String[] tickers = body.path("tickers").asText("").split(",");
            final long priceTicks = Math.round(body.path("price").asDouble(150) * 1_000_000d);
            final Boolean ok = onOwner(() -> {
                long version = System.currentTimeMillis(); // monotonic across re-seeds
                event.type = InputEvent.TYPE_ACCOUNT_CONTROL;
                event.accountId = accountId;
                event.securityId = 0;
                event.setControlEnabled(true);
                event.setControlVersion(version++);
                offerBlocking();
                for (final String ticker : tickers) {
                    final int id = resolveSecurityId(ticker.trim());
                    if (id < 0) {
                        return false;
                    }
                    event.type = InputEvent.TYPE_SECURITY_CONTROL;
                    event.accountId = 0;
                    event.securityId = id;
                    event.setControlEnabled(true);
                    event.setControlVersion(version++);
                    offerBlocking();
                    event.type = InputEvent.TYPE_PRICE_TICK;
                    event.side = 0;
                    event.securityId = id;
                    event.priceTicks = priceTicks;
                    offerBlocking();
                }
                return true;
            });
            respond(exchange, Boolean.TRUE.equals(ok) ? 200 : 422, "{\"seeded\":" + ok + "}");
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** Offer the current {@code event} with backpressure retry (owner thread only). */
    private void offerBlocking() {
        codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
        final long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
        while (client.offer(orderBuffer, 0, AeronReplicationCodec.INPUT_BYTES) < 0) {
            client.pollEgress();
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("ingress offer timed out");
            }
            Thread.yield();
        }
    }

    /** Prometheus-text metrics the inherited bench harness reads (booked/s = delta of the fill
     *  counter / elapsed). Format matches the order-matcher's traderx_order_events_total family. */
    private void handleMetrics(final HttpExchange exchange) {
        final String body = "traderx_order_events_total{event=\"fill\"} " + fillEvents + "\n"
            + "traderx_order_events_total{event=\"accepted\"} " + acceptedOrders + "\n"
            + "traderx_order_events_total{event=\"canceled\"} " + canceledOrders + "\n"
            + "traderx_order_events_total{event=\"replaced\"} " + replacedOrders + "\n"
            + "traderx_market_trades_total{outcome=\"booked\"} " + marketTradesBooked + "\n"
            + "traderx_market_trades_total{outcome=\"rejected\"} " + marketTradesRejected + "\n"
            + "traderx_gateway_batch_fences_total{state=\"offered\"} " + batchFenceOffers + "\n"
            + "traderx_gateway_batch_high_water_total{outcome=\"completed\"} "
                + batchHighWaterCompletions + "\n"
            + "traderx_gateway_batch_high_water_total{outcome=\"timeout\"} "
                + batchHighWaterTimeouts + "\n"
            // Pipelined ingress: how full the in-flight window ran. If this pins at capacity the
            // window is the bottleneck (raise GATEWAY_MAX_INFLIGHT); if it sits low the binding
            // constraint is downstream (consensus commit rate / driver), not the gateway.
            + "traderx_gateway_inflight_orders " + inflight.depth() + "\n"
            + "traderx_gateway_inflight_capacity " + MAX_INFLIGHT + "\n";
        try {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final Exception ignore) {
            // scrape client went away
        }
    }

    // ----- symbol resolution (owner thread only) ---------------------------------------------

    /** ticker -> securityId via the sequenced registration path (matrix F2); cached forever.
     *  A "#<n>" pseudo-ticker is a pre-resolved securityId passthrough (REST securityId path). */
    private int resolveSecurityId(final String ticker) {
        if (ticker.startsWith("#")) {
            return Integer.parseInt(ticker.substring(1));
        }
        final Integer cached = idByTicker.get(ticker);
        if (cached != null) {
            return cached;
        }
        final long requestId = nextSymbolRequestId++;
        codec.encodeSymbolRegister(symbolBuffer, 0, requestId, ticker);
        lastSymbolAck = null;
        if (!offerAndAwait(symbolBuffer, AeronReplicationCodec.SYMBOL_BYTES,
            () -> lastSymbolAck != null && lastSymbolAck[2] == requestId)) {
            return -1;
        }
        final int id = (int) lastSymbolAck[1];
        if (id >= 0) {
            idByTicker.put(ticker, id);
        }
        return id;
    }

    private boolean offerAndAwait(final UnsafeBuffer buffer, final int length,
                                  final java.util.function.BooleanSupplier ackArrived) {
        final long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
        boolean offered = false;
        while (System.currentTimeMillis() < deadline) {
            client.pollEgress();
            if (!offered && client.offer(buffer, 0, length) > 0) {
                offered = true;
            }
            if (offered && ackArrived.getAsBoolean()) {
                return true;
            }
            Thread.yield();
        }
        return false;
    }

    private static void respond(final HttpExchange exchange, final int code, final String body) {
        try {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final Exception ignore) {
            // client went away
        }
    }

    private static String env(final String name, final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** One in-flight order-lifecycle command awaiting its committed ack (pipelined ingress).
     *  Package-private for {@code InflightCorrelationTest}. */
    static final class PendingOrder {
        final byte type;         // TYPE_ORDER_NEW / TYPE_ORDER_CANCEL / TYPE_ORDER_REPLACE
        final int accountId;     // NEW only
        final String ticker;     // NEW only (resolved on the owner thread); null for cancel/replace
        final char side;         // NEW only
        final int qty;           // NEW / REPLACE
        final long limitPx;      // NEW / REPLACE
        final long clientKey;    // idempotency key (0 = none); NEW / REPLACE
        final int orderRef;      // CANCEL / REPLACE target; 0 for NEW (engine assigns the ref)
        final CompletableFuture<ExecResult> future = new CompletableFuture<>();

        PendingOrder(final byte type, final int accountId, final String ticker, final char side,
                     final int qty, final long limitPx, final long clientKey, final int orderRef) {
            this.type = type;
            this.accountId = accountId;
            this.ticker = ticker;
            this.side = side;
            this.qty = qty;
            this.limitPx = limitPx;
            this.clientKey = clientKey;
            this.orderRef = orderRef;
        }
    }

    /**
     * The pipelined in-flight window. The FIFO is touched ONLY by the owner thread (register on
     * offer, onDirectAck in onEgress, drain on reconnect/batch), so it needs no synchronization. The
     * permit semaphore is the sole cross-thread piece: submitters {@link #acquire} before enqueuing
     * an offer and the owner {@link #release}s when the ack completes the order (or it is drained),
     * bounding in-flight orders and backpressuring submitters when the window is full.
     *
     * <p>Package-private so {@code InflightCorrelationTest} can drive the correlation core (FIFO +
     * inputSeq boundary + permit accounting) with no cluster.
     */
    static final class Inflight {
        private final ArrayDeque<PendingOrder> fifo = new ArrayDeque<>();
        private final Semaphore permits;
        private final int max;
        // inputSeq (member applied-sequence) whose entry ack last popped the head. -1 forces the
        // first ack to pop. Owner-thread-only, alongside the FIFO.
        private long lastInputSeq = -1;

        Inflight(final int max) {
            this.max = max;
            this.permits = new Semaphore(max);
        }

        /** Submitter thread: reserve a slot, or false if none frees within {@code timeoutMs}. */
        boolean acquire(final long timeoutMs) throws InterruptedException {
            return permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        }

        /** Owner thread: order was offered — register it in offer order. */
        void register(final PendingOrder p) {
            fifo.addLast(p);
        }

        /**
         * Owner thread: a direct (non-resting) order-lifecycle ack arrived carrying {@code inputSeq}.
         * If it OPENS a new input (its first direct ack), pop and return the FIFO head — the order to
         * complete. If it CONTINUES the current input (a later fill under the same applied-sequence),
         * return null: that fill belongs to the order already answered, and popping again would shift
         * every later order onto the wrong request. Returns null too if the window is empty.
         */
        PendingOrder onDirectAck(final long inputSeq) {
            if (inputSeq == lastInputSeq) {
                return null; // continuation fill of the already-answered order
            }
            lastInputSeq = inputSeq;
            return fifo.pollFirst();
        }

        /** Owner thread: an order completed — return its slot to the window. */
        void release() {
            permits.release();
        }

        /** Owner thread: complete every outstanding order as ambiguous, free its slot, and reset the
         *  inputSeq boundary (a fresh session may restart appliedSeq). */
        void drain() {
            for (PendingOrder p; (p = fifo.pollFirst()) != null; ) {
                p.future.complete(null);
                permits.release();
            }
            lastInputSeq = -1;
        }

        /** Thread-safe (semaphore-based, never touches the FIFO) in-flight depth — for /metrics. */
        int depth() {
            return max - permits.availablePermits();
        }
    }
}
