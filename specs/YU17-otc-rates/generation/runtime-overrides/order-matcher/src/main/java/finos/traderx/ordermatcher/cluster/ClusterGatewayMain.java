package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.SwapConventions;
import finos.traderx.ordermatcher.risk.RiskReason;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.codecs.EventCode;
import io.aeron.logbuffer.Header;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 * Split readiness (ADR-045), corrected: {@code /ready} reports the ability to COMMIT, not the state
 * of a socket, and {@code /live} is the same signal at a much higher bar — the point at which a
 * restart (the only known cure for the wedge) is the right answer. Both are served from a separate
 * single-thread HTTP server on {@code GATEWAY_PROBE_PORT} so the order path cannot starve them.
 */
public final class ClusterGatewayMain implements OrderSubmitter, OrderStatusSource {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long ACK_TIMEOUT_MS = 10_000;
    private static final long BATCH_FENCE_RETRY_MS = 5;
    // Max order-lifecycle commands in flight per gateway before a submitter is backpressured. Set
    // well above any tested session count so the binding constraint is the consensus commit rate,
    // not the window; also caps how hard the ingress term / egress ring is filled.
    private static final int MAX_INFLIGHT = Integer.parseInt(env("GATEWAY_MAX_INFLIGHT", "4096"));

    /**
     * Key prefixes whose quantity is a USD FACE amount and therefore carries the FR-CDM16
     * minimum/increment rule. Allocation-free to test, because this runs on the order-entry path.
     * Adding an asset class is one edit here rather than a hunt through string literals — which
     * is exactly what the corporate rollout needed and did not have.
     */
    private static final String[] BOND_KEY_PREFIXES = { "UST-", "CORP-" };

    static boolean isBondKey(final String ticker) {
        if (ticker == null) {
            return false;
        }
        for (final String prefix : BOND_KEY_PREFIXES) {
            if (ticker.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }



    // Off-hot-path client for FIX order-status (H/AF) reads against the trade-processor read model.
    // Status queries are low-volume and never touch the order-entry path, so a blocking JDK client is
    // fine; created once, not per request.
    private final HttpClient readModelClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final InputEvent event = new InputEvent();
    // Same default as the Spring controller's risk.control.token, so a proof written against one
    // tier authenticates against the other without being told which it is talking to.
    private final String riskControlToken = env("RISK_CONTROL_TOKEN", "dev-risk-control");
    // Stamped once per process: a restarted gateway is a new epoch, which is exactly what a
    // consumer comparing epochs needs in order to know its watermark is no longer comparable.
    private final long controlEpoch = System.currentTimeMillis();
    private volatile long controlWatermark;
    // Must match reference-data's jetstream-control-feed-publisher.ts (STREAM TRADERX_CONTROL_SECURITY).
    private static final String CONTROL_FEED_SUBJECT =
        env("CONTROL_FEED_SUBJECT", "traderx.control.security.deltas");
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

    /**
     * Consecutive submits that produced NO committed ack, across every ingress path. Reset by any
     * submit that gets one.
     *
     * <p>This exists because {@code connected} is not a readiness signal. After a leader kill the
     * gateway can hold a socket it believes is good while its session is useless: measured on both
     * kind and GKE (2026-08-12/13), {@code /ready} answered {@code connected:true} with
     * {@code restarts=0} and no log line while every order came back 504. Kubernetes therefore
     * never took the pod out of the Service and the LoadBalancer kept routing a single public IP
     * into it — and the orders were not refused, they were committed and booked while the client
     * was told they failed. See issues/open/HANDOFF-issue-gateway-wedges-after-leader-kill.md.
     *
     * <p>A readiness signal for an ingress process has to mean "I can commit", not "my socket is
     * open".
     */
    private final AtomicInteger noAckStreak = new AtomicInteger();

    /**
     * Consecutive no-ack submits before {@code /ready} starts failing.
     *
     * <p>Why a STREAK and not a rate or a window: any single submit that gets a committed ack
     * resets it, and the counter is global across submitter threads. Under legitimate saturation
     * some orders always complete — the whole point of the in-flight permit window is that it
     * drains — so the streak sits near zero however deep the backlog gets. It only runs away when
     * NOTHING is completing anywhere in the gateway, which is the wedge and essentially nothing
     * else. That distinction matters: this rig benches at ~190k/s on four gateways, and a
     * readiness signal that trips under peak load would take the ingress down to protect it.
     *
     * <p>20 rather than 3 for margin — a wedge produces an unbounded streak in seconds, so the
     * threshold costs nothing against the real signal and buys room against a burst of ambiguous
     * timeouts during an election, which IS recoverable and must not unready anything.
     */
    private static final int READY_NO_ACK_STREAK = Integer.parseInt(env("READY_NO_ACK_STREAK", "20"));

    /**
     * Consecutive no-ack submits before {@code /live} starts failing — i.e. before Kubernetes is
     * asked to RESTART this gateway rather than merely stop routing to it.
     *
     * <p>Readiness was only half the fix. It removes the pod from the Service, which is the
     * substantive win on a multi-replica tier; but the correctness rig runs {@code replicas: 1}
     * (hostname anti-affinity, one untainted node), so there is nowhere else to route and the
     * outage persists until a human runs {@code kubectl rollout restart}. That restart is the
     * known, reliable cure on both kind and GKE, and the gateway is stateless — it costs the
     * in-flight orders and nothing else. A probe can ask for it.
     *
     * <p>The reason it is deliberately NOT the readiness signal with a bigger number:
     *
     * <ul>
     *   <li><b>It ignores {@code connected}.</b> A closed session is not a reason to restart —
     *       an election or a member roll closes it and the owner thread reconnects on its own.
     *       Restarting on that would restart every gateway on every failover.</li>
     *   <li><b>It cannot fire on an idle gateway.</b> The streak only advances when a submit
     *       returns no committed ack, so a cluster-wide outage with no traffic offered restarts
     *       nothing. The condition is precisely "clients are being told their orders failed",
     *       which is the only state a restart is known to fix.</li>
     *   <li><b>It is reached by volume, not by time.</b> 5x the readiness limit at the default,
     *       and the manifest's {@code failureThreshold} then demands ~60s of continuous failure
     *       on top. A burst of ambiguous timeouts during an election clears long before that.</li>
     * </ul>
     *
     * <p>The residual risk is honest: a genuine cluster-wide outage under live load WILL restart
     * gateways, because from the gateway's side that is indistinguishable from its own wedge. The
     * cost is bounded — during such an outage nothing is committing anyway, and the kubelet's
     * restart backoff caps the flap rate — whereas the cost of not restarting is a single public
     * IP routing every order into a gateway that books what it denies.
     */
    private static final int LIVE_NO_ACK_STREAK = Integer.parseInt(
        env("LIVE_NO_ACK_STREAK", Integer.toString(READY_NO_ACK_STREAK * 5)));

    // Owner-thread-only ack scratch (set by the egress listener between poll calls). Order-lifecycle
    // acks no longer use a single slot — they complete the head of the pipelined FIFO (see onEgress).
    private long[] lastTradeAck;   // {kind, riskReason} — market-trade (/trades) committed decision
    private long[] lastSymbolAck;  // {appliedSeq, symbolId, requestId}
    private long[] lastSwapAck;    // YU17 {contractId, booked, riskReason, clientOrderKey}
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
    // Binary per-order funnel diagnostics. The acceptor counters have many connection-thread
    // writers; these pipeline counters have one owner-thread writer and a racy /metrics reader.
    private BinaryGatewayAcceptor binaryAcceptor;
    private volatile long pipelineOfferAttempts;
    private volatile long pipelineOffersSucceeded;
    private volatile long pipelineOfferBackpressure;
    private volatile long pipelineAcksCompleted;
    // Market-trade (/trades, the UI create-order path) outcome counters — the market-trade path
    // emits KIND_TRADE_BOOKED/REJECTED, neither of which the order-lifecycle metrics above count,
    // so without these a stage mis-seed books nothing with no visible signal. Owner-thread writes.
    // Gated on the ack's market-trade byte: YU13 crossing fills also emit KIND_TRADE_BOOKED, and
    // counting those here would drown the market-trade signal in ordinary order flow.
    private volatile long marketTradesBooked;
    private volatile long marketTradesRejected;
    // OTEL-01: side-channel distributed tracing; null unless OTEL_TRACES=1, in which case spans are
    // written to a bounded ring and shipped by a separate thread (see SpanSink — the trade path never
    // waits for the collector). Head-sampled per order, and the sampling verdict + trace identity are
    // DERIVED from the client idempotency key the log already carries, so nothing about tracing is
    // added to the sequenced message (see OrderTrace).
    private final SpanSink traces = SpanSink.fromEnvOrNull("traderx-cluster-gateway");
    private final int traceMask = SpanSink.sampleMaskFromEnv();

    // OTEL-01 follow-up: the ONE per-order log line this tier emits, and it is the reject — see
    // logReject. Capped per second because stdout is the only sink in this design with NO overflow
    // valve: a span drops at a full ring and the exporter's duty cycle is capped, but a log line
    // goes straight to the node's disk and on to promtail/Loki with nothing in between. A reject
    // storm is not hypothetical here (a 30s bench once had the engine reject 296,000 orders on
    // CREDIT_LIMIT while every request got a green 2xx), and 10k println/s on the submit threads
    // would make the telemetry the outage.
    private static final int REJECT_LOG_PER_SEC = Integer.parseInt(env("REJECT_LOG_PER_SEC", "20"));
    private final AtomicLong rejectLogWindow = new AtomicLong();
    private final AtomicInteger rejectLogCount = new AtomicInteger();
    private final AtomicLong rejectLogsSuppressed = new AtomicLong();

    // LATENCY-01 Phase A: side-channel per-hop latency decomposition; null unless LATENCY_DECOMP=1.
    // Owner thread records the queue/cluster segments; the binary acceptor records decode/reply/total.
    private final GatewayLatencyDecomposition latency = GatewayLatencyDecomposition.fromEnvOrNull();

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
        server.createContext("/swaps", this::handleSwapBook);
        server.createContext("/swaptions", this::handleSwaptionBook);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/latency", this::handleLatency);
        server.createContext("/seed", this::handleSeed);
        server.createContext("/resolve", this::handleResolve);
        // Prefix context: /risk/control/{policy,restriction,security,account} all land here.
        server.createContext("/risk/control", this::handleRiskControl);
        // YU05 recon + regulatory audit (FR-PTC04/05/10/20/21). The gateway is stateless-forward and
        // holds no history: egress acks carry a kind byte and a sequence, not trade detail, so it
        // cannot answer these from anything it has seen. The data is the committed log, which lives
        // on the MEMBERS — so this is a pass-through to a member's own surface, not a local answer.
        // Deliberately a plain HTTP forward that references nothing new: this file is YU13's layer,
        // and a reference here to a YU15-layer class would not compile on the YU13/YU14 branches.
        server.createContext("/recon", this::handleMemberProxy);
        server.createContext("/regulatory", this::handleMemberProxy);
        // READY MEANS "I CAN COMMIT", not "my socket is open". `connected` alone reported healthy
        // through a wedge in which every order was answered 504 while being committed and booked
        // (issues/open/HANDOFF-issue-gateway-wedges-after-leader-kill.md), so Kubernetes never pulled
        // the pod and the LoadBalancer kept feeding it. The streak is reported in the body either
        // way: the first thing anyone does with a failing probe is curl it by hand, and a bare
        // `connected:false` would send them to the network when the session is the problem.
        final HttpHandler readyHandler = exchange -> {
            final int streak = noAckStreak.get();
            final boolean ready = connected && streak < READY_NO_ACK_STREAK;
            respond(exchange, ready ? 200 : 503, "{\"connected\":" + connected
                + ",\"noAckStreak\":" + streak + ",\"noAckLimit\":" + READY_NO_ACK_STREAK + "}");
        };
        // /health stays 200 whenever the process is serving — it answers "am I up", and nothing
        // probes it. It carries the streak so a human looking here first is not misled.
        final HttpHandler healthHandler = exchange ->
            respond(exchange, 200, "{\"connected\":" + connected
                + ",\"noAckStreak\":" + noAckStreak.get() + "}");
        // LIVE MEANS "RESTART ME": the same streak, a much higher bar, and no `connected` term —
        // see LIVE_NO_ACK_STREAK for why each of those three is deliberate.
        final HttpHandler liveHandler = exchange -> {
            final int streak = noAckStreak.get();
            respond(exchange, streak < LIVE_NO_ACK_STREAK ? 200 : 503,
                "{\"noAckStreak\":" + streak + ",\"noAckLimit\":" + LIVE_NO_ACK_STREAK + "}");
        };
        // PROBES GET THEIR OWN SERVER AND THEIR OWN THREAD (§5 of the wedge issue). The order
        // path's 64-thread pool is exhaustible by construction — every in-flight order parks a
        // thread for the full ACK_TIMEOUT, and under a wedge none of them complete early. Measured
        // 2026-08-13: at ~20 orders/s a wedged gateway stopped answering ANY HTTP, /ready included,
        // and never drained — eight minutes with zero load offered, still nothing. A probe the
        // server cannot answer is not a signal: Kubernetes then acts on the TIMEOUT, which is
        // indiscriminate and says nothing about what is wrong. One thread that only ever reads two
        // volatiles cannot be starved by the order path no matter what the order path is doing.
        //
        // Registered on the main port as well, because every existing proof and bench script curls
        // :18110/ready and there is no reason to break them — but the manifest's probes read the
        // probe port, which is the whole point.
        final HttpServer probes = HttpServer.create(
            new InetSocketAddress(Integer.parseInt(env("GATEWAY_PROBE_PORT", "18111"))), 16);
        probes.setExecutor(Executors.newSingleThreadExecutor());
        for (final HttpServer s : new HttpServer[] {server, probes}) {
            s.createContext("/ready", readyHandler);
            s.createContext("/health", healthHandler);
            s.createContext("/live", liveHandler);
        }
        server.start();
        probes.start();
        startControlFeedSubscriber();

        final String fixPortEnv = env("FIX_ACCEPTOR_PORT", "");
        if (!fixPortEnv.isEmpty()) {
            final List<String> compIds = Arrays.asList(env("FIX_SESSION_COMPIDS", "CLIENT1").split(","));
            final FixGatewayAcceptor fix = new FixGatewayAcceptor(this, this, Integer.parseInt(fixPortEnv),
                env("FIX_TARGET_COMP_ID", "TRADERX"),
                Integer.parseInt(env("FIX_DEFAULT_ACCOUNT", "11")), compIds);
            fix.start();
            Runtime.getRuntime().addShutdownHook(new Thread(fix::stop));
        }

        // Binary order-entry fast path (lever 4), additive alongside FIX/REST. Off unless the port is
        // set, so existing deploys are unchanged. Same OrderSubmitter seam, same pipelined window.
        final String binPortEnv = env("BINARY_ACCEPTOR_PORT", "");
        if (!binPortEnv.isEmpty()) {
            binaryAcceptor = new BinaryGatewayAcceptor(this, Integer.parseInt(binPortEnv), latency);
            binaryAcceptor.start();
            Runtime.getRuntime().addShutdownHook(new Thread(binaryAcceptor::stop));
        }
        System.out.println("GATEWAY up: http=" + httpPort + " ingress=" + ingressEndpoints
            + (fixPortEnv.isEmpty() ? "" : " fix=" + fixPortEnv)
            + (binPortEnv.isEmpty() ? "" : " bin=" + binPortEnv));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            server.stop(0);
            probes.stop(0);
            CloseHelper.quietCloseAll(client, driver);
        }));
        Thread.currentThread().join();
    }

    // ----- owner thread: sole cluster-client user --------------------------------------------

    private void ownerLoop() {
        connectCycling();
        long lastReconnect = 0;
        long lastKeepAlive = 0;
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
                // Session keepalive. Ingress traffic keeps a session alive on its own, but an IDLE
                // gateway sends nothing and the consensus module expires its session — observed on
                // GKE 2026-07-26 as every quiet gateway flapping /ready 503 for ~50ms every ~5-10s,
                // forever (a one-clock observer caught gw1-gw5 cycling while the loaded gw0 never
                // blipped). Each flap is a full close+reconnect, and a failover that lands mid-flap
                // reads as seconds of client-visible outage that the 154ms election never caused.
                if (client != null && !client.isClosed()) {
                    final long now = System.currentTimeMillis();
                    if (now - lastKeepAlive > 1000) {
                        lastKeepAlive = now;
                        client.sendKeepAlive();
                    }
                }
                // sessionLost, not just isClosed(): a session the cluster closed or errored does not
                // always reach isClosed(), and that gap is the wedge -- ingress kept committing while
                // every ack was gone. See the listener above.
                if (client != null && (client.isClosed() || sessionLost)) {
                    final long now = System.currentTimeMillis();
                    // 100ms, not 1000: connectCycling() already blocks until a live endpoint accepts,
                    // so this gate only bounds retry churn — at 1s it was the largest avoidable term
                    // in post-election recovery for whichever gateway lost its session.
                    if (now - lastReconnect > 100) {
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
    /**
     * A FULL EgressListener, not a method reference.
     *
     * `.egressListener(this::onEgress)` satisfies the interface's single abstract method and leaves
     * onSessionEvent and onNewLeader on their DEFAULT NO-OP bodies (verified against
     * aeron-cluster 1.51.0). That is why a gateway that stops committing after a leader change says
     * nothing at any log level: the two events that would name the cause were being discarded by
     * the interface's own defaults. See
     * issues/open/HANDOFF-issue-gateway-wedges-after-leader-kill.md, whose §3 is exactly this ("no
     * exception, no reconnect attempt, no log line at any level") and whose §2 asks whether the
     * egress subscription survives a leader change.
     *
     * Both are logged rather than counted, because the failure they explain is diagnosed by a human
     * reading `kubectl logs` after the fact, and neither fires often enough to be noise: a session
     * event means the cluster changed its mind about this session, and a new-leader event means an
     * election happened.
     */
    private final EgressListener egressListener = new EgressListener() {
        @Override
        public void onMessage(final long clusterSessionId, final long timestamp,
                              final DirectBuffer buffer, final int offset, final int length,
                              final Header header) {
            onEgress(clusterSessionId, timestamp, buffer, offset, length, header);
        }

        /**
         * Anything other than OK means this session is no longer usable for acks. The owner loop's
         * only reconnect trigger was {@code client.isClosed()}, which a cluster-side close does not
         * always reach -- so the session could be dead for acks while the gateway kept offering
         * orders that committed and were never acknowledged, answering every client 504. Record it
         * and let the owner loop rebuild the session, which is the known cure (a `rollout restart`
         * clears the wedge instantly, and a fresh session is the only thing that changes).
         */
        @Override
        public void onSessionEvent(final long correlationId, final long clusterSessionId,
                                   final long leadershipTermId, final int leaderMemberId,
                                   final EventCode code, final String detail) {
            System.out.println("CLUSTER-SESSION-EVENT code=" + code + " session=" + clusterSessionId
                + " leader=" + leaderMemberId + " term=" + leadershipTermId
                + " detail=" + (detail == null ? "-" : detail));
            if (code != EventCode.OK) {
                sessionLost = true;
            }
        }

        /**
         * A leader change is the one moment the FIFO's "one direct ack per cleared offer, in offer
         * order" invariant provably breaks: the dying leader sequenced offers it never egressed to
         * this session, and the promotion destroys them (a follower applied the same log with its
         * egress suppressed, and does not re-apply once promoted). Left alone, the FIFO stays
         * permanently N ahead and every subsequent ack answers an abandoned request — measured
         * ratcheting 21 -> 36 -> 51 across three kills, and never recovering.
         *
         * <p>So resynchronise here, on the owner thread, DIRECTLY — never via connectCycling(),
         * which loops {@code while (running)} until it reconnects and would park the owner thread
         * for the duration of a quorum loss. That is the precise hazard the self-heal's
         * offer-cleared trigger was designed to avoid, and routing this through it would reintroduce
         * it by the back door.
         */
        @Override
        public void onNewLeader(final long clusterSessionId, final long leadershipTermId,
                                final int leaderMemberId, final String ingressEndpoints) {
            final int stranded = inflight.depth();
            inflight.onNewLeaderResync();
            // The at-risk set was unacked by definition; forgetting it makes the self-heal fire
            // LESS often, never more, which is the safe direction for a trigger whose hazard is
            // firing when a reconnect cannot help.
            offeredUnackedStreak.set(0);
            System.out.println("CLUSTER-NEW-LEADER leader=" + leaderMemberId
                + " term=" + leadershipTermId + " session=" + clusterSessionId
                + " resynced=" + stranded + " (in-flight answered ambiguous)");
        }
    };

    /**
     * Set by onSessionEvent above AND by the wedge detector in submitPipelined (submitter threads),
     * read and cleared by the owner loop. Volatile because those writers are not the owner thread.
     */
    private volatile boolean sessionLost = false;

    /**
     * THE WEDGE SIGNAL: consecutive orders whose offer CLEARED INTO THE LOG and whose committed ack
     * then never arrived.
     *
     * Deliberately not `noAckStreak`, which also counts orders that never cleared the ingress at
     * all. That distinction is the whole safety argument, and it is measured rather than assumed
     * (2026-08-14, gateway's own counters):
     *
     *   healthy cluster   offer_attempt +10   offer_success +10
     *   quorum loss       offer_attempt  +1   offer_success  +0
     *
     * During quorum loss the offer never clears, so this streak cannot advance and the self-heal
     * cannot fire during a recoverable outage — which matters because a fresh session is useless
     * there and `connectCycling()` would park the owner thread trying to get one. During the wedge
     * the offer DOES clear (the cluster consumes a ref for every order it then never acks —
     * measured 1:1), so the streak advances and a fresh session is exactly the known cure.
     */
    private final AtomicInteger offeredUnackedStreak = new AtomicInteger();
    private static final int WEDGE_RECONNECT_STREAK = Integer.parseInt(
        env("WEDGE_RECONNECT_STREAK", String.valueOf(READY_NO_ACK_STREAK)));

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
                    .egressListener(egressListener));
                connectRotation = (connectRotation + 1) % endpointEntries.length;
                sessionLost = false;   // a fresh session: whatever killed the last one is behind us
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
        // Every egress kind carries the member's appliedSeq at offset 0. Record it before dispatch,
        // for ALL kinds: the leader-change watermark is only as good as the highest sequence we can
        // prove we saw, and a resting update or symbol ack is evidence just as much as a direct ack.
        inflight.observeInputSeq(buffer.getLong(offset));
        if (kind == MatchingEngineClusteredService.KIND_SYMBOL_REGISTERED) {
            lastSymbolAck = new long[] {
                buffer.getLong(offset), buffer.getInt(offset + 8), buffer.getLong(offset + 13) };
        } else if (kind == MatchingEngineClusteredService.KIND_SWAP_BOOKED) {
            // YU17. Correlated by the clientOrderKey the ack echoes at 13, exactly as the symbol
            // and extract acks correlate by requestId — NOT by "first one after my offer", because
            // a swap booking is a cold-path request whose ack can interleave with another
            // gateway client's.
            lastSwapAck = new long[] { buffer.getLong(offset), buffer.getInt(offset + 8),
                buffer.getByte(offset + 22), buffer.getLong(offset + 13) };
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

    /**
     * OTEL-01 follow-up: THE log/trace join. One line, on the reject — the line a supporter is
     * actually looking for — carrying the trace id the spans for this same order were emitted
     * under.
     *
     * <h2>Where the trace id is stamped, and why there</h2>
     * <b>In the line.</b> Not as a Loki label: a label per trace id is one log STREAM per order,
     * which is the textbook Loki cardinality bomb (at the measured 190k orders/s ceiling it asks
     * for 190k streams a second, and Loki indexes by stream). Not as structured metadata either,
     * which would be the modern home for it — the deployed Loki 2.9.8 ships
     * {@code allow_structured_metadata: false}, so it would need a limits change plus a promtail
     * stage, and it buys nothing over a substring filter on a stream already narrowed to one
     * namespace. And not via the log pattern or an MDC, the two mechanisms the brief expected,
     * because <b>this tier has no logging framework</b>: it writes to {@code System.out}, and
     * adding slf4j/logback to reach an MDC would be a dependency and a hot-path allocation source
     * to solve what a string concat solves.
     *
     * <p>In the line also happens to be the only form that works in BOTH directions, which is what
     * a support workflow actually needs: Grafana's Loki {@code derivedFields} turns
     * {@code trace=<32 hex>} into a clickable Tempo link (log &rarr; trace), and Tempo's
     * {@code tracesToLogsV2} custom query line-filters on the same token (trace &rarr; log). The
     * ClOrdID is on the line for the third direction — the supporter who starts from the client's
     * own id and has no way to compute splitmix64 by hand.
     *
     * <p>Nothing is plumbed to get the id here: it is DERIVED from the idempotency key exactly as
     * both span emitters derive it. That is the whole point of derive-don't-carry paying off a
     * second time — and it is why this works even with {@code OTEL_TRACES=0}, where the line is
     * still emitted (so the cost A/B compares tracing and nothing else) and simply names a trace
     * that was never recorded.
     *
     * <p>Cold path by construction: a reject already builds a String for the REST response body,
     * and this runs on the submitting thread, never on the owner thread or a member's apply thread.
     */
    private void logReject(final PendingOrder p, final ExecResult result) {
        if (!allowRejectLog(System.currentTimeMillis(), REJECT_LOG_PER_SEC,
                rejectLogWindow, rejectLogCount)) {
            rejectLogsSuppressed.incrementAndGet();
            return;
        }
        final byte reason = result.riskReason();
        // A key of 0 means this order has no identity either tier could have agreed on, so it was
        // never traced and never could be. Print a dash rather than the id the derivation WOULD
        // have produced: a 32-hex token that resolves to nothing is a link into an empty trace, and
        // "wiring that looks right and isn't" is the exact failure this deliverable keeps finding.
        final long key = OrderTrace.keyOf(p.clientKey, p.orderRef);
        System.out.println("ORDER-REJECT trace=" + (key == 0L ? "-" : OrderTrace.traceIdHex(key))
            + " clordid=" + safeForLog(p.clOrdId)
            + " account=" + p.accountId
            + " ticker=" + safeForLog(p.ticker)
            + " orderRef=" + result.orderRef()
            + " kind=" + result.kind()
            + " reason=" + (reason >= 0 && reason < RiskReason.values().length
                ? RiskReason.values()[reason] : "NONE"));
    }

    /**
     * The ClOrdID and ticker on this line are CLIENT-SUPPLIED and are being written to a log a
     * supporter reads and Loki indexes. A value containing a newline would forge a second log line
     * — a real reject that never happened, or a fake trace id — so anything outside printable
     * non-space ASCII becomes a dot, and the field is length-bounded. Cold path; a reject already
     * allocates for its REST response body.
     */
    static String safeForLog(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return "-";
        }
        final int len = Math.min(raw.length(), 64);
        final StringBuilder out = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            final char c = raw.charAt(i);
            out.append(c > 0x20 && c < 0x7F ? c : '.');
        }
        return out.toString();
    }

    /**
     * True at most {@code perSecond} times in any one wall-clock second, process-wide.
     *
     * <p>Static and handed its own state so the cap can be asserted directly in a test rather than
     * inferred from wall-clock timing — the same reason {@code SpanSink.pauseMillis} is pure. The
     * window roll races benignly: two threads can both see a new second, one wins the CAS and
     * resets, the loser simply counts into the window the winner opened.
     */
    // ponytail: a whole-second bucket, not a sliding window — a burst can be twice the rate across
    // a second boundary. Swap in a token bucket if that ever matters; for a reject line it does not.
    static boolean allowRejectLog(final long nowMillis, final int perSecond,
                                  final AtomicLong window, final AtomicInteger count) {
        final long second = nowMillis / 1000L;
        final long open = window.get();
        if (second != open && window.compareAndSet(open, second)) {
            count.set(0);
        }
        return count.incrementAndGet() <= perSecond;
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
        final PendingOrder p = new PendingOrder(InputEvent.TYPE_ORDER_NEW, accountId, ticker, side,
            qty, limitPxTicks, clientOrderKey(clOrdId), 0);
        // Reject log only. Assigned here rather than added to the constructor so the binary and
        // cancel paths — which have no client order id — keep their signatures untouched.
        p.clOrdId = clOrdId;
        return submitPipelined(p);
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
        final PendingOrder p = new PendingOrder(InputEvent.TYPE_ORDER_REPLACE, 0, null, (char) 0,
            qty, limitPxTicks, clientOrderKey(clOrdId), orderRef);
        p.clOrdId = clOrdId;
        return submitPipelined(p);
    }

    /**
     * Binary fast-path NEW (lever 4). Identical to {@link #submitOrder(String, int, String, char,
     * int, long)} downstream — it builds the same {@link PendingOrder} and rides the same pipelined
     * window and consensus offer — but the caller supplies a pre-resolved numeric {@code securityId}
     * and a numeric {@code clientKey}, so no ticker String is built and no key is hashed on the hot
     * path. That is the whole allocation win of the lever: the decode-to-submit path is zero-alloc.
     */
    @Override
    public ExecResult submitOrder(final long clientKey, final int accountId, final int securityId,
                                  final char side, final int qty, final long limitPxTicks) {
        return submitPipelined(new PendingOrder(InputEvent.TYPE_ORDER_NEW, accountId, null, side,
            qty, limitPxTicks, clientKey, 0, securityId));
    }

    /** Binary fast-path atomic replace (lever 4): {@link #submitReplace(int, String, int, long)}
     *  with the client's numeric idempotency key used directly rather than hashed from a String. */
    @Override
    public ExecResult submitReplace(final int orderRef, final long clientKey, final int qty,
                                    final long limitPxTicks) {
        if (orderRef <= 0) {
            return new ExecResult(false, orderRef, OutputEvent.KIND_ORDER_NOT_FOUND); // reserved fence ref
        }
        return submitPipelined(new PendingOrder(InputEvent.TYPE_ORDER_REPLACE, 0, null, (char) 0,
            qty, limitPxTicks, clientKey, orderRef));
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
        final ExecResult r = submitPipelined0(p);
        // The wedge detector, in the ONE place every ingress path funnels through — REST new,
        // cancel, replace, the binary fast path and the FIX acceptor all reach consensus here, so
        // one update covers them and none can be added later that bypasses it. A null is exactly
        // "no committed decision", which is what /ready now has to know about.
        if (r == null && p.resyncAmbiguous) {
            // Answered by the leader-change resync, not by any inability of this gateway. Counting
            // it would invert the signal the streaks carry: a strand of 20-99 would fail readiness,
            // remove the only gateway from the Service, and then never clear — nothing resets
            // noAckStreak but a SUCCESSFUL order, and a pod out of the Service is sent none.
            // Liveness is 5x readiness, so that bracket has no rescue and needs a human. Neither
            // incremented nor reset: orders that fail for real still climb the streak below, so
            // yu16-ready-tracks-commit's quorum-loss assertions are untouched (those fail through
            // offerPipelined's deadline path and are never resync-marked).
            return r;
        }
        if (r == null) {
            noAckStreak.incrementAndGet();
            // THE SELF-HEAL. Only orders that actually cleared the ingress count: those are the ones
            // whose ack the cluster owes us, so their silence is OUR session's problem and a fresh
            // session is the known cure (a `rollout restart` clears the wedge instantly, and a new
            // session is the only thing it changes). Orders that never cleared say the cluster will
            // not take traffic, which no reconnect fixes.
            if (p.offered && offeredUnackedStreak.incrementAndGet() >= WEDGE_RECONNECT_STREAK) {
                offeredUnackedStreak.set(0);
                System.out.println("GATEWAY-WEDGE-SUSPECTED offeredUnacked>=" + WEDGE_RECONNECT_STREAK
                    + " noAckStreak=" + noAckStreak.get() + " — rebuilding the cluster session");
                sessionLost = true; // the owner loop reconnects; see ownerLoop
            }
        } else {
            noAckStreak.set(0);
            offeredUnackedStreak.set(0);
        }
        return r;
    }

    private ExecResult submitPipelined0(final PendingOrder p) {
        try {
            if (!inflight.acquire(ACK_TIMEOUT_MS)) {
                return null; // window saturated: treat as ambiguous backpressure, never a false reject
            }
            // LATENCY-01 Phase A: t_decoded — owner-queue wait starts as this order is enqueued.
            if (latency != null && latency.sample()) {
                p.tSubmitNanos = System.nanoTime();
            }
            // OTEL-01: head-sample HERE, at ingress, before the order is offered — the verdict is a
            // pure function of the idempotency key, so the members will independently reach the same
            // one without us sending them anything.
            //
            // OTEL-01 follow-up: the head verdict is now a FLAG, not the gate. traceKey is set for
            // every keyed order so the span TIMESTAMPS exist if the order turns out to be rejected
            // and has to be traced after the fact (OrderTrace.escalate) — a decision that can only
            // be taken once the committed ack is in hand, by which time an unrecorded start time is
            // unrecoverable. That costs one nanoTime per order, and only while OTEL_TRACES=1: with
            // tracing off `traces` is null and this whole block is the single null check it always
            // was. Orders with no idempotency key still leave traceKey 0 and are never traced,
            // rejected or not, because there is nothing for the member to agree with us about.
            if (traces != null) {
                p.traceKey = OrderTrace.keyOf(p.clientKey, p.orderRef);
                p.traceSampled = OrderTrace.sampled(p.traceKey, traceMask);
                p.traceStartNanos = System.nanoTime();
            }
            // Fire-and-forget on the owner thread: offer + register, no per-order wait.
            tasks.add(new FutureTask<>(() -> offerPipelined(p), null));
            final ExecResult result = p.future.get(ACK_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS);
            // OTEL-01: the root span closes on THIS thread, not the owner's — it covers the residence
            // the client actually experiences, and keeps one of the three span writes off the owner.
            if (p.traceKey != 0L && (p.traceSampled || (result != null && OrderTrace.escalate(result.kind())))) {
                final long hi = OrderTrace.traceIdHi(p.traceKey);
                final long lo = OrderTrace.traceIdLo(p.traceKey);
                traces.span(hi, lo, OrderTrace.spanId(p.traceKey, 0), 0L,
                    OrderTrace.epochNanos(p.traceStartNanos), OrderTrace.epochNanos(System.nanoTime()),
                    SpanSink.NAME_ORDER, result == null ? 0L : result.orderRef());
            }
            if (result != null && OrderTrace.escalate(result.kind())) {
                logReject(p, result);
            }
            return result;
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
                // Binary NEW carries a pre-resolved numeric securityId (ticker == null) so the owner
                // thread never builds a String; FIX/REST NEW resolves its ticker exactly as before.
                final int securityId = p.ticker != null ? resolveSecurityId(p.ticker) : p.securityId;
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
            pipelineOfferAttempts++;
            while (client.offer(orderBuffer, 0, AeronReplicationCodec.INPUT_BYTES) < 0) {
                pipelineOfferBackpressure++;
                client.pollEgress(); // drains earlier acks (frees the ingress window) while backpressured
                if (System.currentTimeMillis() > deadline) {
                    p.future.complete(null); // never cleared the ingress: ambiguous, do not register
                    inflight.release();
                    return;
                }
            }
            pipelineOffersSucceeded++;
            p.offered = true; // cleared the ingress: from here, silence means a missing ACK, not a
                              // cluster that would not take the order. See offeredUnackedStreak.
            // LATENCY-01 Phase A: t_offer — offer cleared into the log. queue = owner-thread wait, and
            // the cluster black box starts now. Single owner thread, single clock: valid subtraction.
            if (p.tSubmitNanos != 0) {
                p.tOfferNanos = System.nanoTime();
                latency.recordQueue(p.tOfferNanos - p.tSubmitNanos);
            }
            // OTEL-01: same instant, independent of LATENCY_DECOMP — the offer is where the gateway
            // hands off to consensus, so it both ends the queue span and starts the black box.
            if (p.traceKey != 0L) {
                p.traceOfferNanos = System.nanoTime();
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
        // LATENCY-01 Phase A: t_egress — committed ack in hand. cluster black box = t_offer -> now
        // (ingress-out + sequence + consensus commit + apply + egress-back), all one owner clock.
        // Record BEFORE completing the future so the submit thread can't race the reset() between them.
        if (p.tOfferNanos != 0) {
            latency.recordCluster(System.nanoTime() - p.tOfferNanos);
        }
        // OTEL-01: the two owner-thread spans. cluster.consensus is the span the MEMBERS will parent
        // their commit/apply spans to — they compute its id from the same idempotency key, which is
        // how the trace crosses the consensus boundary with nothing added to the log. Both writes are
        // a 64-byte copy into a bounded ring; a full ring drops and counts, it never blocks the owner.
        //
        // OTEL-01 follow-up: `kind` is the committed ack byte and it is in hand HERE, before any span
        // is written — which is what makes escalating a reject at the head possible at all. The
        // member reads the identical byte off the output it just produced and escalates with us, so
        // the trace stays whole across the boundary with, still, nothing carried.
        if (p.traceKey != 0L && p.traceOfferNanos != 0L
                && (p.traceSampled || OrderTrace.escalate(kind))) {
            final long hi = OrderTrace.traceIdHi(p.traceKey);
            final long lo = OrderTrace.traceIdLo(p.traceKey);
            final long root = OrderTrace.spanId(p.traceKey, 0);
            final long offerEpoch = OrderTrace.epochNanos(p.traceOfferNanos);
            traces.span(hi, lo, OrderTrace.spanId(p.traceKey, 1), root,
                OrderTrace.epochNanos(p.traceStartNanos), offerEpoch, SpanSink.NAME_QUEUE, ref);
            traces.span(hi, lo, OrderTrace.clusterSpanId(p.traceKey), root,
                offerEpoch, OrderTrace.epochNanos(System.nanoTime()), SpanSink.NAME_CLUSTER, ref);
        }
        p.future.complete(new ExecResult(accepted, ref, kind, riskReason));
        pipelineAcksCompleted++;
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

    /**
     * The instrument field, under either of the two names clients in this system actually send.
     *
     * <p>The cluster gateway has always read {@code ticker}; the single-BLP Spring matcher has
     * always read {@code security}, and so every client written against it sends that —
     * {@code OrderMatcherClient} in execution-algo-engine and the yu03 proof among them. Against
     * this gateway that silently produced an empty instrument, which resolves to no security and
     * surfaces as a bare rejection with nothing naming the cause. Accepting both here is what lets
     * one rig serve both sets of clients, and it removes a trap that has cost real debugging time.
     *
     * <p>{@code ticker} wins when both are present, so nothing that already worked changes.
     */
    private static String instrumentOf(final JsonNode body) {
        final String ticker = body.path("ticker").asText("");
        return ticker.isEmpty() ? body.path("security").asText("") : ticker;
    }

    private void handleOrder(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            final String ticker = body.hasNonNull("securityId")
                ? "#" + body.get("securityId").asInt() : instrumentOf(body);
            final char side = "Sell".equalsIgnoreCase(body.path("side").asText("Buy")) ? 'S' : 'B';
            final int qty = body.path("quantity").asInt();
            // YU16 (FR-CDM16): a Treasury quantity is USD face - at least 100, a multiple of 100
            // - validated here, before the engine ever sees the order. The engine stays uniform
            // across asset classes (NFR-CDM01); the boundary owns instrument semantics. limitPrice
            // for a bond is the FRACTION of par (ADR-057), which the 1e6 conversion below carries
            // at full six-decimal precision.
            if (isBondKey(ticker)) {
                if (qty < 100) {
                    respond(exchange, 422, "{\"error\":\"Bond quantity must be at least 100.\"}");
                    return;
                }
                if (qty % 100 != 0) {
                    respond(exchange, 422, "{\"error\":\"Bond quantity must be a multiple of 100.\"}");
                    return;
                }
            }
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
                    ? "#" + body.get("securityId").asInt() : instrumentOf(body);
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
                ? "#" + body.get("securityId").asInt() : instrumentOf(body);
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

    /**
     * OTC swap booking ingress (YU17, ADR-062).
     *
     * <p>POST /swaps {"clientOrderId","accountId","payReceive":"Pay"|"Receive","notional",
     * "fixedRate","effectiveDate","maturityDate","conventions"} →
     * 200 {"contractId":"SW-N","sequence":N,"booked":true}, 422 with a RiskReason when the gate
     * refuses, 400 when a term cannot be represented, 504 when no decision committed.
     *
     * <p>There is no matching, no book and no price grid for a swap (D2): the command creates a
     * contract and never crosses. It is nonetheless SEQUENCED THROUGH CONSENSUS like every other
     * command, which is the load-bearing decision of this state — the EOD extract's own header
     * claims every row is the replicated state machine's state at a consensus sequence "not a
     * read-model query", and booking swaps into the database directly would quietly make that
     * sentence false.
     */
    private void handleSwapBook(final HttpExchange exchange) {
        handleOtcBooking(exchange, false);
    }

    /**
     * OTC swaption booking ingress (YU17 phase 2, ADR-065).
     *
     * <p>POST /swaptions — the swap body plus {@code "expiryDate"} and {@code "exerciseStyle"}
     * ("European" | "Bermudan" | "American"). Every other field describes the UNDERLYING swap, so
     * {@code fixedRate} is the strike and {@code payReceive} is the direction of the underlying's
     * fixed leg: {@code "Pay"} is a payer swaption. Returns
     * 200 {"contractId":"SWPT-N","sequence":N,"booked":true}.
     *
     * <p>Its own route rather than a {@code product} field on /swaps, for the same reason the
     * engine gets its own command type: a swaption is not a swap, and a client that posts a swap
     * body should never be able to receive an option by accident of which fields it happened to
     * include.
     */
    private void handleSwaptionBook(final HttpExchange exchange) {
        handleOtcBooking(exchange, true);
    }

    /**
     * The shared body of both OTC routes. A swaption's underlying IS a swap, so every term except
     * the option wrapper is validated by exactly the same code — which is the point: the two routes
     * cannot drift into disagreeing about what a notional or a date is.
     *
     * <p><b>Every term the record cannot represent is refused HERE, before anything is
     * sequenced</b>, following the boundary-owns-instrument-semantics rule the Treasury face
     * validation established (FR-CDM16). A notional past {@code int} range would wrap into a small
     * one and book silently; a date past 2149 would wrap into a past date; an unknown conventions
     * or exercise-style name would resolve to index 0 and publish a contract under the wrong day
     * count or the wrong style. Each of those is a plausible-looking wrong number in a risk file,
     * which is the failure mode this whole state exists to prevent.
     */
    private void handleOtcBooking(final HttpExchange exchange, final boolean swaption) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            final List<String> required = new ArrayList<>(List.of(
                "accountId", "payReceive", "notional", "fixedRate", "effectiveDate",
                "maturityDate", "conventions"));
            if (swaption) {
                required.add("expiryDate");
                required.add("exerciseStyle");
            }
            for (final String field : required) {
                if (!body.hasNonNull(field)) {
                    respond(exchange, 400, "{\"error\":\"" + field + " required\"}");
                    return;
                }
            }
            final String direction = body.get("payReceive").asText("");
            final byte side;
            if ("Pay".equalsIgnoreCase(direction)) {
                side = InputEvent.SWAP_PAY_FIXED;
            } else if ("Receive".equalsIgnoreCase(direction)) {
                side = InputEvent.SWAP_RECEIVE_FIXED;
            } else {
                respond(exchange, 400, "{\"error\":\"payReceive must be Pay or Receive\"}");
                return;
            }
            // asLong, not asInt: asInt SATURATES at Integer.MAX_VALUE, so a 5bn notional would be
            // silently admitted as 2.147bn — the exact class of quiet wrong number this refuses.
            final long notional = body.get("notional").asLong(0L);
            if (notional <= 0 || notional > Integer.MAX_VALUE) {
                respond(exchange, 400, "{\"error\":\"notional must be 1.." + Integer.MAX_VALUE + "\"}");
                return;
            }
            final long rateTicks = Math.round(body.get("fixedRate").asDouble() * 1_000_000d);
            if (rateTicks == 0L) {
                // A zero rate is either a missing field or a rate below a millionth. Both are
                // wrong, and a zero-rate swap in a risk file looks like a real one.
                respond(exchange, 400, "{\"error\":\"fixedRate must be a non-zero decimal fraction (0.042 = 4.2%)\"}");
                return;
            }
            final int conventionIndex = SwapConventions.indexOf(body.get("conventions").asText(""));
            if (conventionIndex < 0) {
                respond(exchange, 400, "{\"error\":\"unknown conventions '"
                    + body.get("conventions").asText("") + "'\"}");
                return;
            }
            final long effective;
            final long maturity;
            final long expiry;
            try {
                effective = java.time.LocalDate.parse(body.get("effectiveDate").asText()).toEpochDay();
                maturity = java.time.LocalDate.parse(body.get("maturityDate").asText()).toEpochDay();
                expiry = swaption
                    ? java.time.LocalDate.parse(body.get("expiryDate").asText()).toEpochDay() : 0L;
            } catch (final java.time.format.DateTimeParseException e) {
                respond(exchange, 400, "{\"error\":\"dates must be ISO yyyy-MM-dd\"}");
                return;
            }
            if (effective < 0 || maturity < 0 || expiry < 0
                || effective > InputEvent.MAX_SWAP_EPOCH_DAY || maturity > InputEvent.MAX_SWAP_EPOCH_DAY
                || expiry > InputEvent.MAX_SWAP_EPOCH_DAY) {
                respond(exchange, 400, "{\"error\":\"dates must fall between 1970-01-01 and 2149-06-06\"}");
                return;
            }
            if (maturity <= effective) {
                respond(exchange, 400, "{\"error\":\"maturityDate must be after effectiveDate\"}");
                return;
            }
            int exerciseStyle = 0;
            if (swaption) {
                exerciseStyle = SwapConventions.exerciseStyleIndexOf(body.get("exerciseStyle").asText(""));
                if (exerciseStyle < 0) {
                    respond(exchange, 400, "{\"error\":\"unknown exerciseStyle '"
                        + body.get("exerciseStyle").asText("") + "'\"}");
                    return;
                }
                // An option that expires after the swap it is an option ON has nothing to be
                // exercised into. Refused here rather than published as a term nobody can act on.
                if (expiry > effective) {
                    respond(exchange, 400,
                        "{\"error\":\"expiryDate must be on or before the underlying effectiveDate\"}");
                    return;
                }
            }

            final String clOrdId = body.path("clientOrderId").asText("");
            final long clientKey = clientOrderKey(clOrdId);
            final int accountId = body.get("accountId").asInt();
            final int styleIndex = exerciseStyle;
            final long[] ack = onOwner(() -> {
                event.type = swaption ? InputEvent.TYPE_SWAPTION_BOOK : InputEvent.TYPE_SWAP_BOOK;
                event.side = side;
                event.accountId = accountId;
                event.qty = (int) notional;
                event.limitPx = rateTicks;
                if (swaption) {
                    event.setSwaptionTerms(conventionIndex, styleIndex, (int) expiry);
                } else {
                    event.securityId = conventionIndex;
                }
                event.setClientOrderKey(clientKey);
                event.setSwapDates((int) effective, (int) maturity);
                event.eventTimeMillis = 0;
                codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
                lastSwapAck = null;
                if (!offerAndAwait(orderBuffer, AeronReplicationCodec.INPUT_BYTES,
                        () -> lastSwapAck != null && lastSwapAck[3] == clientKey)) {
                    return null; // no committed decision within timeout: ambiguous, NOT a reject
                }
                return lastSwapAck;
            });
            if (ack == null) {
                respond(exchange, 504, "{\"error\":\"no committed decision\"}");
                return;
            }
            if (ack[1] != 0) {
                respond(exchange, 200, "{\"contractId\":\"" + (swaption ? "SWPT-" : "SW-") + ack[0]
                    + "\",\"sequence\":" + ack[0] + ",\"booked\":true}");
            } else {
                respond(exchange, 422, "{\"booked\":false,\"reason\":\""
                    + RiskReason.values()[(int) ack[2]] + "\"}");
            }
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /**
     * Risk control plane on the cluster tier (FR-IMRG30/31), the counterpart to the single-BLP
     * {@code RiskControlController}.
     *
     * <p><b>Nothing new happens to the replicated state machine here.</b> Every one of these
     * mutations is an {@code InputEvent} control command that the engine has always applied —
     * {@code TYPE_ACCOUNT_CONTROL}, {@code TYPE_SECURITY_CONTROL}, {@code TYPE_POLICY_CONTROL},
     * {@code TYPE_RESTRICTION_CONTROL} are dispatched by {@code MatchingEngine.onEvent} to
     * {@code risk.putAccount/putSecurity/putPolicy/putLimits/putRestriction}, and the resulting
     * state is already carried in the snapshot as {@code T_POLICY}/{@code T_ACCOUNT}/{@code
     * T_SECURITY}. {@code /seed} has been sequencing two of these four through consensus since
     * YU12. All that was missing on this tier was an operator-facing way to send them, so this
     * adds routes and nothing else: no schema change, no new template, no snapshot format bump.
     *
     * <p>Because they are ordinary sequenced commands, a control change lands at a definite
     * consensus position and every member applies it in the same order relative to the orders
     * around it — which is the property the single-BLP version got from journaling and this tier
     * gets from the log. Replay and a rebuilt member reach the identical risk state.
     *
     * <p>Auth mirrors the Spring controller exactly: a shared token plus a non-blank operator
     * header, both required, with the operator logged for attribution.
     */
    /**
     * Durable control feed consumer for the cluster tier (ADR-021, FR-IMRG32/33) — the piece YU04
     * specified and this tier never had.
     *
     * <p>reference-data writes security existence/identity into its outbox in the same transaction
     * as the {@code stocks} row and publishes each row, in strict version order, onto the
     * JetStream subject below. On the single-BLP tier {@code ControlFeedSubscriber} consumed that;
     * here nothing did, so a security only existed once someone POSTed {@code /seed} by hand. That
     * is why scripts/yu15/seed-proof-fixtures.sh had to exist.
     *
     * <p>Each delta becomes an ordinary SEQUENCED control command, offered through the same owner
     * thread and the same path {@code handleRiskControl} uses — so every member applies it at a
     * definite consensus position and a rebuilt member replays to the identical risk state. No new
     * replicated state, no schema change, no snapshot format bump: {@code TYPE_SECURITY_CONTROL} is
     * already dispatched by MatchingEngine and already carried in the snapshot as {@code
     * T_SECURITY}.
     *
     * <p>Runs on its own daemon thread and NEVER touches the cluster session directly — the session
     * and the {@code event} flyweight are owner-thread-only, so it hands work over via onOwner
     * exactly as an HTTP handler does. Failure is non-fatal and retried: a broker that is not up
     * yet must not stop the gateway from serving orders.
     */
    private void startControlFeedSubscriber() {
        // Opt-in per manifest rather than on in code: the replay-on-connect that makes offline
        // catch-up work (DeliverPolicy.All) re-sequences the whole security universe through
        // consensus at every gateway start — correct and idempotent, but ~510 commands of startup
        // noise a latency bench would rather not pay. The kind cluster manifest sets
        // CONTROL_FEED_SUBSCRIBER=1; benches simply leave it unset.
        //
        // (Historical: this was first forced off because MAX_SECURITIES was 64 and the 510-security
        // universe wedged the consumer at ticker 65. The engine is sized 1024 now — see the sizing
        // block in MatchingEngineClusteredService — so that reason is gone.)
        if (!"1".equals(env("CONTROL_FEED_SUBSCRIBER", "0"))) {
            return;
        }
        final String natsUrl = env("NATS_ADDRESS",
            "nats://" + env("NATS_BROKER_HOST", "nats") + ":4222");
        final Thread t = new Thread(() -> runControlFeed(natsUrl), "control-feed-subscriber");
        t.setDaemon(true);
        t.start();
    }

    private void runControlFeed(final String natsUrl) {
        while (true) {
            // Do not subscribe until the cluster session can actually take an offer. Consuming
            // earlier makes the whole replay burst fail unacked while the session dials, and an
            // ephemeral push consumer then redelivers those messages only after the ~30s AckWait —
            // observed live as an offline-injected delta arriving well after the gateway reported
            // ready, which reads as the durable feed failing to catch up when it is only waiting
            // out an ack timer nobody needed to start.
            while (!connected) {
                try {
                    Thread.sleep(200);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try (io.nats.client.Connection nats = io.nats.client.Nats.connect(natsUrl)) {
                final io.nats.client.JetStream js = nats.jetStream();
                // Ephemeral push subscription from the START of the stream: replaying the whole
                // durable log on connect IS the offline catch-up path (FR-IMRG33). A security the
                // feed published while this gateway was down is applied when it comes back, which
                // is the property yu04-offline-catchup asserts and the reason the stream is
                // file-backed rather than a fire-and-forget subject.
                final io.nats.client.PushSubscribeOptions opts =
                    io.nats.client.PushSubscribeOptions.builder()
                        .configuration(io.nats.client.api.ConsumerConfiguration.builder()
                            .deliverPolicy(io.nats.client.api.DeliverPolicy.All)
                            .ackPolicy(io.nats.client.api.AckPolicy.Explicit)
                            .build())
                        .build();
                final io.nats.client.JetStreamSubscription sub =
                    js.subscribe(CONTROL_FEED_SUBJECT, opts);
                System.out.println("CONTROL-FEED subscribed subject=" + CONTROL_FEED_SUBJECT);
                while (true) {
                    final io.nats.client.Message msg =
                        sub.nextMessage(java.time.Duration.ofSeconds(5));
                    if (msg == null) {
                        continue;
                    }
                    try {
                        applyControlDelta(new String(msg.getData(), StandardCharsets.UTF_8));
                        msg.ack();
                    } catch (final Exception apply) {
                        // Do NOT ack: leave it redeliverable rather than silently dropping a
                        // control change. Strict version order is the whole point of the outbox.
                        System.out.println("CONTROL-FEED apply failed, will redeliver: " + apply);
                        Thread.sleep(1000);
                    }
                }
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (final Exception e) {
                System.out.println("CONTROL-FEED connect/subscribe failed, retrying: " + e);
                try {
                    Thread.sleep(2000);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** One outbox delta -> one sequenced SECURITY_CONTROL, plus the operator-visible replica. */
    private void applyControlDelta(final String payload) throws Exception {
        final JsonNode body = JSON.readTree(payload);
        final String ticker = body.path("ticker").asText("").trim();
        if (ticker.isEmpty()) {
            return; // nothing to register; ack it rather than redelivering forever
        }
        final long version = body.path("version").asLong(System.currentTimeMillis());
        final Boolean ok = onOwner(() -> {
            final int id = resolveSecurityId(ticker);
            if (id < 0) {
                return false;
            }
            event.qty = 0;
            event.limitPx = 0L;
            event.type = InputEvent.TYPE_SECURITY_CONTROL;
            event.accountId = 0;
            event.securityId = id;
            event.setControlEnabled(true);
            event.setControlVersion(version);
            offerBlocking();
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("could not register " + ticker + " from the control feed");
        }
        controlReplica.compute(ticker, (k, prev) -> new long[] {
            1, prev == null ? 0 : prev[1], version,
        });
        controlWatermark = Math.max(controlWatermark, version);
        System.out.println("CONTROL-FEED applied ticker=" + ticker + " version=" + version);
    }

    private void handleRiskControl(final HttpExchange exchange) {
        try {
            final String requestPath = exchange.getRequestURI().getPath();
            if (requestPath.endsWith("/snapshot")) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, 405, "{\"error\":\"GET only\"}");
                    return;
                }
                respond(exchange, 200, controlReplicaJson());
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final String token = exchange.getRequestHeaders().getFirst("X-Risk-Control-Token");
            final String operator = exchange.getRequestHeaders().getFirst("X-Risk-Operator");
            if (!riskControlToken.equals(token) || operator == null || operator.isBlank()) {
                respond(exchange, 401, "{\"error\":\"invalid risk-control credentials\"}");
                return;
            }
            final String path = exchange.getRequestURI().getPath();
            final String action = path.substring(path.lastIndexOf('/') + 1);
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            // Wall-clock version, matching GatewayReplicaStore: monotonic across restarts, and only
            // ever compared for ordering. It is read on the gateway and then carried IN the command,
            // so every member applies the same number -- never read per-member, which would diverge.
            final long version = System.currentTimeMillis();
            final String instrument = instrumentOf(body).trim();

            // YU17 FX-rate fix: POST /risk/control/fxrate {"currency":"EUR","rate":1.0842} — USD
            // per one unit. Validated HERE, before sequencing (boundary-owns-semantics, FR-CDM16):
            // an unknown currency or non-positive rate must never become a committed log entry the
            // members can only ignore. USD is refused because its rate is identity by construction
            // — sequencing a USD rate could only ever mean someone believes it is settable.
            final int fxCurrency;
            final long fxRateTicks;
            if ("fxrate".equals(action)) {
                final String currency = body.path("currency").asText("");
                fxCurrency = SwapConventions.currencyIndexOf(currency);
                fxRateTicks = Math.round(body.path("rate").asDouble() * 1_000_000d);
                if (fxCurrency < 0) {
                    respond(exchange, 400, "{\"error\":\"unknown currency '" + currency + "'\"}");
                    return;
                }
                if (fxCurrency == 0) {
                    respond(exchange, 400,
                        "{\"error\":\"USD is the limit currency; its rate is identity and not settable\"}");
                    return;
                }
                if (fxRateTicks <= 0L) {
                    respond(exchange, 400,
                        "{\"error\":\"rate must be a positive decimal (USD per unit, e.g. 1.0842)\"}");
                    return;
                }
            } else {
                fxCurrency = 0;
                fxRateTicks = 0L;
            }

            final Boolean ok = onOwner(() -> {
                // `event` is the shared owner-thread flyweight, so whatever the last order left in
                // the limit/qty slots would otherwise be encoded into the log. The control handlers
                // ignore those fields, so it is deterministic either way -- but a sequenced command
                // carrying a stale price is a genuinely confusing thing to find in a journal dump.
                event.qty = 0;
                event.limitPx = 0L;
                switch (action) {
                    case "policy" -> {
                        event.type = InputEvent.TYPE_POLICY_CONTROL;
                        event.accountId = 0;
                        event.securityId = 0;
                        event.setControlEnabled(body.path("killSwitch").asBoolean(false));
                        event.setControlVersion(body.path("policyVersion").asLong(version));
                        // 0 means "leave unchanged" to onPolicyControl, which is exactly what a
                        // null in the JSON should mean -- the proof sends nulls for both.
                        event.qty = body.path("maxPositionQuantity").asInt(0);
                        event.limitPx = body.path("maxConcentrationNotionalTicks").asLong(0L);
                    }
                    case "fxrate" -> {
                        event.type = InputEvent.TYPE_FX_RATE;
                        event.accountId = 0;
                        event.securityId = fxCurrency;
                        event.limitPx = fxRateTicks;
                        event.side = 0;
                        event.orderRef = 0;
                        event.setClientOrderKey(0L);
                    }
                    case "restriction", "security", "account" -> {
                        final boolean enabled = switch (action) {
                            // A restriction is expressed to the engine as the restricted flag, and
                            // a security control as tradable = enabled && !halted -- the same two
                            // mappings RiskControlController makes.
                            case "restriction" -> body.path("restricted").asBoolean(false);
                            case "security" -> body.path("enabled").asBoolean(true)
                                && !body.path("halted").asBoolean(false);
                            default -> body.path("enabled").asBoolean(true);
                        };
                        if ("account".equals(action)) {
                            event.type = InputEvent.TYPE_ACCOUNT_CONTROL;
                            event.accountId = body.path("accountId").asInt();
                            event.securityId = 0;
                        } else {
                            final int id = resolveSecurityId(instrument);
                            if (id < 0) {
                                return false;
                            }
                            event.type = "restriction".equals(action)
                                ? InputEvent.TYPE_RESTRICTION_CONTROL
                                : InputEvent.TYPE_SECURITY_CONTROL;
                            event.accountId = 0;
                            event.securityId = id;
                        }
                        event.setControlEnabled(enabled);
                        event.setControlVersion(version);
                    }
                    default -> {
                        return null;
                    }
                }
                offerBlocking();
                return true;
            });

            if (ok == null) {
                respond(exchange, 404, "{\"error\":\"unknown control: " + action + "\"}");
                return;
            }
            if (!ok) {
                respond(exchange, 422, "{\"error\":\"unknown instrument\"}");
                return;
            }
            // Replica update happens only AFTER the offer returned, so the operator-visible view can
            // never claim a control that consensus did not accept. The ordering is the point: the
            // log is the record, this map is a reflection of it.
            if (!instrument.isEmpty() && !"policy".equals(action) && !"account".equals(action)) {
                controlReplica.compute(instrument, (k, prev) -> new long[] {
                    "security".equals(action)
                        ? (body.path("enabled").asBoolean(true)
                            && !body.path("halted").asBoolean(false) ? 1 : 0)
                        : (prev == null ? 1 : prev[0]),
                    "restriction".equals(action)
                        ? (body.path("restricted").asBoolean(false) ? 1 : 0)
                        : (prev == null ? 0 : prev[1]),
                    version,
                });
                controlWatermark = version;
            }
            System.out.println("risk_control operator=" + operator + " type=" + action
                + " version=" + version);
            // 200-with-body rather than the Spring side's 204: respond() sends an explicit
            // content length, and a 204 must carry none -- passing 0 there means chunked, not
            // empty. Every other route on this gateway answers JSON, so this matches them.
            respond(exchange, 200, "{\"applied\":true,\"version\":" + version + "}");
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /**
     * The gateway-side replica of control state, and the reason it exists rather than the snapshot
     * being answered from a member: this is the same split the single-BLP tier has, where
     * {@code GatewayReplicaStore} holds the operator-visible view and the engine holds the
     * authoritative one. Every mutation below is also sequenced through consensus, so the two
     * cannot drift on anything this gateway sent.
     *
     * <p>It is deliberately NOT presented as the authoritative risk state -- a member rebuilt from
     * a snapshot has the real thing, and this map only knows what passed through this process.
     * Naming it "replica" in the response keeps that honest.
     */
    private final java.util.Map<String, long[]> controlReplica =
        new java.util.concurrent.ConcurrentHashMap<>();

    private String controlReplicaJson() {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("{\"sourceEpoch\":").append(controlEpoch)
          .append(",\"watermark\":").append(controlWatermark)
          .append(",\"count\":").append(controlReplica.size())
          .append(",\"securities\":[");
        boolean first = true;
        for (final java.util.Map.Entry<String, long[]> e : controlReplica.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            final long[] v = e.getValue();
            sb.append("{\"ticker\":\"").append(e.getKey())
              .append("\",\"enabled\":").append(v[0] != 0)
              .append(",\"restricted\":").append(v[1] != 0)
              .append(",\"version\":").append(v[2]).append('}');
        }
        return sb.append("]}").toString();
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

    /**
     * Cold-path ticker → securityId lookup (lever 4). Clients that speak the numeric binary protocol
     * need the id the sequenced registration assigned, and nothing else exposed it — real venues offer
     * exactly this as a security-definition lookup. Registers on first ask, identically to how an
     * order's ticker would, so it is consistent with {@code /seed} (same {@code resolveSecurityId}).
     * POST {"ticker":"JPM"} → {"securityId":N}; 404 if the registration was rejected. ONE call per
     * client at startup — never on the order hot path, so its allocation is irrelevant to the lever.
     */
    private void handleResolve(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            final String ticker = instrumentOf(body).trim();
            if (ticker.isEmpty()) {
                respond(exchange, 400, "{\"error\":\"ticker required\"}");
                return;
            }
            final Integer id = onOwner(() -> resolveSecurityId(ticker));
            if (id == null || id < 0) {
                respond(exchange, 404, "{\"error\":\"unresolvable ticker\"}");
                return;
            }
            respond(exchange, 200, "{\"ticker\":\"" + ticker + "\",\"securityId\":" + id + "}");
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
            + "traderx_gateway_inflight_capacity " + MAX_INFLIGHT + "\n"
            + "traderx_binary_frames_total{stage=\"decoded\"} "
                + (binaryAcceptor == null ? 0 : binaryAcceptor.decodedFrames()) + "\n"
            + "traderx_binary_frames_total{stage=\"acknowledged\"} "
                + (binaryAcceptor == null ? 0 : binaryAcceptor.acknowledgedFrames()) + "\n"
            + "traderx_gateway_pipeline_total{stage=\"offer_attempt\"} " + pipelineOfferAttempts + "\n"
            + "traderx_gateway_pipeline_total{stage=\"offer_success\"} " + pipelineOffersSucceeded + "\n"
            + "traderx_gateway_pipeline_total{stage=\"offer_backpressure\"} "
                + pipelineOfferBackpressure + "\n"
            + "traderx_gateway_pipeline_total{stage=\"ack_completed\"} " + pipelineAcksCompleted + "\n"
            // OTEL-01 follow-up: reject lines the per-second cap refused to print. Exported rather
            // than silent for the same reason the span drop count is: a correlation gap an operator
            // cannot see is worse than one they can. Non-zero here means read the reject COUNTERS,
            // not the log — the log is a sample during a storm.
            + "traderx_gateway_reject_logs_suppressed_total " + rejectLogsSuppressed.get() + "\n"
            // OTEL-01: the sink's own health. A rising drop count is the honest signal that telemetry
            // is shedding load — which is the designed behaviour, and far better than the alternative
            // of it showing up as latency on the trade path.
            + (traces == null ? "" : traces.metrics());
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

    /** LATENCY-01 Phase A side channel: per-hop decomposition (µs) of the gateway's residence time.
     *  {@code GET /latency} dumps p50/p99/p99.9/max per segment; {@code GET /latency?reset=1} zeros the
     *  histograms (call after warmup so the reported window is warm-JIT only). 503 when LATENCY_DECOMP
     *  is off. */
    private void handleLatency(final HttpExchange exchange) {
        if (latency == null) {
            respond(exchange, 503, "latency decomposition disabled (set LATENCY_DECOMP=1)\n");
            return;
        }
        final String query = exchange.getRequestURI().getQuery();
        if (query != null && query.contains("reset=1")) {
            latency.reset();
            respond(exchange, 200, "reset\n");
            return;
        }
        try {
            final byte[] bytes = latency.dump().getBytes(StandardCharsets.UTF_8);
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

    /**
     * Forward a {@code /recon/*} or {@code /regulatory/*} request to a member's health-port surface
     * and return its answer verbatim, Authorization header included — the member enforces the admin
     * claim, because that is where the trade detail is.
     *
     * <p>Members are tried in id order and the FIRST ONE REACHED answers. Any member can: all three
     * hold the same committed log, and the full-history index is a pure function of it. The one
     * asymmetry is that the index is cached per member, so a member that dies between a client's
     * reindex and its paging leaves the next member answering 503 "reindex first" — an honest
     * error the caller already handles, and better than pretending the pages are empty.
     *
     * <p>A connection failure to every member is 503, never an empty 200: "no member answered" and
     * "the log holds nothing" must not look alike to a reconciliation caller.
     *
     * <p>This file is the YU13 layer, so YU13 and YU14 carry it too — and their members predate
     * {@code ClusterRecon} and serve no such routes. There the member answers 404 and this forwards
     * it, which is byte-for-byte the answer those states already gave before this route existed:
     * the capability is YU15's, and the forward is inert until a member serves it.
     */
    private void handleMemberProxy(final HttpExchange exchange) {
        final int healthPort = Integer.parseInt(env("GATEWAY_MEMBER_HEALTH_PORT", "8080"));
        final String query = exchange.getRequestURI().getRawQuery();
        final String suffix = exchange.getRequestURI().getRawPath() + (query == null ? "" : "?" + query);
        final String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        final StringBuilder tried = new StringBuilder();
        for (final String entry : endpointEntries) {
            final int eq = entry.indexOf('=');
            final String hostPort = eq < 0 ? entry : entry.substring(eq + 1);
            final int colon = hostPort.lastIndexOf(':');
            final String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
            try {
                final HttpRequest.Builder builder = HttpRequest
                    .newBuilder(URI.create("http://" + host + ":" + healthPort + suffix))
                    // A full-log reindex is expensive BY DESIGN (it is the Spring tier's own
                    // contract), so the forward must outlast it; the caller's own timeout is the
                    // real bound.
                    .timeout(Duration.ofMinutes(10));
                if (authorization != null) {
                    builder.header("Authorization", authorization);
                }
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    builder.POST(HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.GET();
                }
                final HttpResponse<String> response =
                    readModelClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                respond(exchange, response.statusCode(), response.body());
                return;
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (final Exception ex) {
                tried.append(tried.length() == 0 ? "" : ", ").append(host).append(": ").append(ex);
            }
        }
        respond(exchange, 503, "{\"error\":\"no cluster member answered " + suffix + "\",\"tried\":\""
            + tried.toString().replace('"', '\'') + "\"}");
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

    /**
     * FIX order-status (H/AF) source: reads the SAME trade-processor {@code orderbook} read model the
     * REST blotter serves ({@code GET /accounts/{id}/orders}), so a FIX status answer can never
     * disagree with a REST one. Returns {@code null} when {@code ORDER_READMODEL_URL} is unset (the
     * bench/no-read-model case) or the query fails — the acceptor then answers "status unavailable"
     * rather than fabricating "no orders". Gated on its own env, so ingress-only deploys are untouched.
     */
    @Override
    public List<OrderStatusSource.OrderView> orders(final int accountId, final boolean includeTerminal) {
        final String base = env("ORDER_READMODEL_URL", "");
        if (base.isEmpty()) {
            return null;
        }
        final String url = base + "/accounts/" + accountId + "/orders"
            + (includeTerminal ? "?status=all" : "");
        try {
            final HttpResponse<String> resp = readModelClient.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            final JsonNode arr = JSON.readTree(resp.body());
            final List<OrderStatusSource.OrderView> out = new ArrayList<>(arr.size());
            for (final JsonNode n : arr) {
                out.add(new OrderStatusSource.OrderView(
                    refOf(n.path("id").asText("")),
                    n.path("side").asText(""),
                    n.path("quantity").asInt(),
                    n.path("remainingQuantity").asInt(),
                    n.path("status").asText(""),
                    n.path("security").asText("")));
            }
            return out;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final Exception e) {
            System.err.println("read-model status query failed for account " + accountId + ": " + e);
            return null;
        }
    }

    /** orderRef out of the read model's epoch-qualified id {@code <epoch>-<orderRef>}; -1 if malformed. */
    private static int refOf(final String id) {
        final int dash = id.lastIndexOf('-');
        if (dash < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(id.substring(dash + 1));
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    /** One in-flight order-lifecycle command awaiting its committed ack (pipelined ingress).
     *  Package-private for {@code InflightCorrelationTest}. */
    static final class PendingOrder {
        final byte type;         // TYPE_ORDER_NEW / TYPE_ORDER_CANCEL / TYPE_ORDER_REPLACE
        final int accountId;     // NEW only
        final String ticker;     // NEW only, resolved on the owner thread; null for cancel/replace
                                 // AND for a binary NEW, which instead carries a pre-resolved securityId
        final char side;         // NEW only
        final int qty;           // NEW / REPLACE
        final long limitPx;      // NEW / REPLACE
        final long clientKey;    // idempotency key (0 = none); NEW / REPLACE
        final int orderRef;      // CANCEL / REPLACE target; 0 for NEW (engine assigns the ref)
        final int securityId;    // binary NEW only: pre-resolved id (ticker == null); -1 = resolve via ticker
        final CompletableFuture<ExecResult> future = new CompletableFuture<>();
        // Set by the owner thread once client.offer() clears; read by the submitting thread when
        // its wait expires. Volatile: those are different threads and the future never completed.
        volatile boolean offered;
        // Answered ambiguous by the LEADER-CHANGE RESYNC rather than by any failure of this gateway.
        // Such an order must not advance the no-ack streaks: it was completed by our own deliberate
        // repair, and after that repair the gateway is MORE able to commit, not less. Counting it as
        // ill-health inverts the signal — see submitPipelined. Volatile for the same reason as above.
        volatile boolean resyncAmbiguous;
        // LATENCY-01 Phase A single-clock timestamps (gateway nanoTime), 0 = order not sampled. Written
        // by the submit thread (tSubmit) then the owner thread (tOffer); the concurrent task queue and
        // the owner's single-threaded run give the happens-before, so no volatile needed.
        long tSubmitNanos; // t_decoded: submit thread enqueued this order (owner-queue wait starts)
        long tOfferNanos;  // t_offer:   owner thread cleared the offer into the log (cluster black box starts)
        // OTEL-01 trace state, same threading contract as the two fields above. traceKey 0 = this
        // order is not sampled (the common case) and every trace call site short-circuits. The key
        // is derived, never generated, so it needs no carriage to the members — and because it lives
        // HERE, on the gateway's heap, the trace context crosses consensus in the FIFO that already
        // correlates the committed ack back to this request. No trace id ever enters the log.
        long traceKey;
        long traceStartNanos; // ingress: root span + queue span start
        long traceOfferNanos; // offer cleared: queue span ends, cluster.consensus span starts
        // OTEL-01 follow-up: the HEAD verdict, now separate from traceKey. traceKey says "this order
        // has an identity both tiers can derive"; traceSampled says "the head chose it". A rejected
        // order is traced with traceSampled false — see OrderTrace.escalate.
        boolean traceSampled;
        // The client's own order id, for the reject log line only (null on the binary fast path and
        // on cancel, neither of which has one). A reference to a String the REST/FIX layer already
        // built — no allocation, and never touched by the owner thread.
        String clOrdId;

        PendingOrder(final byte type, final int accountId, final String ticker, final char side,
                     final int qty, final long limitPx, final long clientKey, final int orderRef) {
            this(type, accountId, ticker, side, qty, limitPx, clientKey, orderRef, -1);
        }

        PendingOrder(final byte type, final int accountId, final String ticker, final char side,
                     final int qty, final long limitPx, final long clientKey, final int orderRef,
                     final int securityId) {
            this.type = type;
            this.accountId = accountId;
            this.ticker = ticker;
            this.side = side;
            this.qty = qty;
            this.limitPx = limitPx;
            this.clientKey = clientKey;
            this.orderRef = orderRef;
            this.securityId = securityId;
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
        // ---- THE appliedSeq SEQUENCE SPACE. All three fields below are derived from the member's
        // applied-sequence and belong to ONE numbering. They must therefore be reset TOGETHER, and
        // resetSequenceSpace() is the only place that does it. A fresh session may be a fresh epoch
        // in which appliedSeq restarts at 0 (MatchingEngineClusteredService sets appliedSeq = 0 on
        // start), so a survivor from the previous epoch's numbering is not merely stale, it is
        // catastrophic: a leftover watermark swallows every ack in the new epoch and the gateway
        // 504s forever while looking connected. If a fourth appliedSeq-derived field is ever added,
        // it goes here and into that method. Do NOT collapse these into one comparison — they answer
        // different questions (see onDirectAck).
        //
        // inputSeq whose entry ack last popped the head. -1 forces the first ack to pop.
        private long lastInputSeq = -1;
        // Highest appliedSeq this session has EVIDENCE of, from any egress message. Feeds the
        // watermark at a leader change.
        private long highestInputSeqSeen = -1;
        // Acks at or below this are stale — sequenced before a leader change we have already
        // resynchronised past — and must not pop a head that belongs to a later order.
        private long ignoreAcksAtOrBelow = -1;

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
            // STALE-ACK GATE, and it is a DIFFERENT question from the continuation test below.
            // "<= ignoreAcksAtOrBelow" means "sequenced before a leader change we have already
            // resynchronised past, so it belongs to an order that is gone"; "== lastInputSeq" means
            // "a later fill of the input we just answered". Collapsing them loses one meaning.
            if (inputSeq <= ignoreAcksAtOrBelow) {
                return null; // stale: its order was completed by the leader-change resync
            }
            if (inputSeq == lastInputSeq) {
                return null; // continuation fill of the already-answered order
            }
            lastInputSeq = inputSeq;
            return fifo.pollFirst();
        }

        /** Owner thread: record evidence of the member's applied-sequence from ANY egress message.
         *  Tracking every kind, not just direct acks, keeps the leader-change watermark as high as
         *  the evidence allows — a lower watermark would let a stale ack through. */
        void observeInputSeq(final long inputSeq) {
            if (inputSeq > highestInputSeqSeen) {
                highestInputSeqSeen = inputSeq;
            }
        }

        /** Owner thread: an order completed — return its slot to the window. */
        void release() {
            permits.release();
        }

        /**
         * Owner thread: a NEW LEADER was elected on the SAME session. The dying leader sequenced
         * offers it never egressed to us, and the promotion destroys them — a follower applied the
         * same log with its egress suppressed and does not re-apply after promotion, so those acks
         * are never coming. Without this the FIFO stays permanently N ahead and every later ack pops
         * a head belonging to an abandoned request (the correlation offset).
         *
         * <p>Answer the at-risk set honestly and resynchronise. The sequence space is NOT reset:
         * a new leader continues the same {@code appliedSeq}, so the watermark it sets is meaningful.
         * That is exactly what distinguishes this from {@link #drain()}.
         */
        void onNewLeaderResync() {
            completeAllAmbiguous(true);
            ignoreAcksAtOrBelow = highestInputSeqSeen;
            lastInputSeq = -1;
        }

        /**
         * Owner thread: complete every outstanding order as ambiguous and free its slot.
         *
         * @param resync true when this is the leader-change repair, which marks each order so the
         *               submitter does not count it against the no-ack streaks. Not a cosmetic
         *               distinction: at a strand of 20-99 an uncounted streak would fail readiness,
         *               remove the only gateway from the Service, and then never clear — the streak
         *               resets ONLY on a successful order, and a pod out of the Service receives
         *               none. Liveness sits at 5x readiness, so nothing rescues that bracket.
         */
        private void completeAllAmbiguous(final boolean resync) {
            for (PendingOrder p; (p = fifo.pollFirst()) != null; ) {
                p.resyncAmbiguous = resync;
                p.future.complete(null);
                permits.release();
            }
        }

        /** Owner thread: a NEW SESSION. Complete every outstanding order as ambiguous, free its
         *  slot, and reset the whole appliedSeq sequence space — the new session may be a new epoch
         *  in which appliedSeq restarts at 0. */
        void drain() {
            // NOT resync-marked: a session-boundary drain answers orders the gateway genuinely
            // could not complete, and those are honest evidence for the streak. This is what keeps
            // yu16-ready-tracks-commit's step 3 intact — it asserts /ready STAYS 503 across a
            // restored quorum, and a blanket reset here (or in the resync) would flip it to 200 and
            // launder that verdict rather than fail it.
            completeAllAmbiguous(false);
            resetSequenceSpace();
        }

        /** The ONLY place the appliedSeq-derived fields are reset, so they cannot drift apart.
         *  Carrying any of them across an epoch boundary is silent and total: a stale watermark
         *  makes every ack in the new epoch look stale, nothing ever pops, and every order 504s. */
        private void resetSequenceSpace() {
            lastInputSeq = -1;
            highestInputSeqSeen = -1;
            ignoreAcksAtOrBelow = -1;
        }

        /** Thread-safe (semaphore-based, never touches the FIFO) in-flight depth — for /metrics. */
        int depth() {
            return max - permits.availablePermits();
        }
    }
}
