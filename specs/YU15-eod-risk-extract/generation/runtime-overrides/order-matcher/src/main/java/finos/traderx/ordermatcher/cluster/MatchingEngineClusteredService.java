package finos.traderx.ordermatcher.cluster;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.HotPathMetrics;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.OccSymbol;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.OutputPublisher;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.concurrent.TimeUnit;

/**
 * Hosts the deterministic {@link MatchingEngine} plus the authoritative {@link BlpRiskState}
 * inside an Aeron Cluster service (ADR-044).
 *
 * Every input is a committed consensus-log message (ADR-045): the inherited SBE
 * {@code InputEventMessage} decoded straight into the reusable {@link InputEvent} and applied on
 * the single service thread. Event time is the cluster timestamp, never a local clock. Risk
 * control state (accounts, securities, policy, restrictions) is seeded and mutated exclusively
 * by sequenced {@code *_CONTROL} ingress — there is no side-channel bootstrap feed.
 *
 * YU13: the hosted engine is a genuine crossing limit-order book (price-time priority; the
 * consensus-log order IS the time priority). Egress acks carry a resting-update class byte so
 * gateway ack correlation distinguishes an input's direct lifecycle response from counterparty
 * resting-order updates emitted by the same apply (FR-LOB07).
 *
 * YU15: a sequenced risk-extract marker (ADR-055) names a consensus sequence N at which every
 * member renders the identical position cut. It mutates no state — the cut is a read of state the
 * log has already agreed on — so it is safe to interleave with ordinary flow, and the sequence it
 * lands at is the immutable name the downstream EOD fixture is stamped with.
 *
 * Snapshot completeness (ADR-046, `system/snapshot-completeness-matrix.md`): {@code
 * onTakeSnapshot} persists every future-output generator and admission dependency — the
 * generators, the book (band geometry + per-security band anchors, then open rows in ascending
 * orderRef order — arrival order, so restore re-appends them into their price levels in the
 * exact original FIFO — then retained terminal rows in eviction-FIFO order so bounded-retention
 * eviction stays replica-identical), positions, engine prices, risk policy, account
 * control/executed exposure, security control/price freshness, and idempotency entries in
 * retention order. Per-order reservations ride on the order rows; the account/exposure
 * aggregates are rebuilt from them on load so the two can never disagree. Recovery fails closed
 * when a restored identifier reaches the restored generator.
 */
public final class MatchingEngineClusteredService implements ClusteredService {
    // ponytail: spike-fixed engine/risk sizing; the production path re-reads these from
    // properties — they must be identical on every member (config identity, see the matrix)
    static final int MAX_SECURITIES = 64;
    static final int POOL_SIZE = 1024;
    // The service thread is BOTH producer (engine emits during apply) and consumer (drainOutputs
    // after apply), so a single event whose output burst exceeds free ring space self-deadlocks in
    // RingBuffer.next() — hit on GKE 2026-07-19 when a price tick mass-executed a ~20k-order book
    // (3 slots per fill) against the old 4096 ring. Size must exceed 3x the worst resting-book
    // cascade. Default 1<<16 covers a ~21k-fill cascade (~10MB of pre-allocated slots — multi-
    // member in-process tests OOM'd at 1<<18); deployments with bigger books set
    // CLUSTER_OUTPUT_RING_SIZE (the GKE manifest pins 1<<18). YU13's crossing engine emits 6
    // slots per match step (both sides), bounded by the aggressor's quantity — the drain-and-
    // retry OutputPublisher override remains the structural guard either way.
    static final int OUTPUT_RING_SIZE = 1 << 16;

    static int outputRingSize() {
        final String env = System.getenv("CLUSTER_OUTPUT_RING_SIZE");
        return env == null || env.isEmpty() ? OUTPUT_RING_SIZE : Integer.parseInt(env);
    }
    static final int MAX_ACCOUNTS = 64;
    /**
     * Duplicate-suppression window, GLOBAL across every session (FR-IMRG14).
     *
     * <p>1024 was not a small window, it was a decorative one. A count-based bound only becomes a
     * protection WINDOW via the arrival rate, and this table is shared by every session: at kind's
     * measured ~240 FIX orders/s it bought ~4.3s; on the batch path at 438k/s it bought ~2.3ms; and
     * at 500 sessions it was ~2 orders of protection each — MORE sessions made the window SHORTER.
     * A FIX client reconnect takes seconds to tens of seconds, so a resend almost always landed
     * past the eviction frontier and double-booked. Verified by eviction, not computed: a key
     * resent after 1100 intervening distinct keys returned a NEW orderRef.
     *
     * <p>256Ki sizes the window for where resends actually happen — the session path at a projected
     * ~3k/s — giving ~90s of cover. Entries are 28 bytes in the snapshot (4-byte type + 3 longs),
     * so a full table adds ~7MB to a snapshot and ~8.5MB of presized heap; both are measured in
     * this state's bench rather than assumed, because snapshot duration is an apply-thread freeze.
     * Presized at construction, so the 250x table never rehashes on the hot path (NGC-01).
     */
    static final int IDEMPOTENCY_CAPACITY = 256 * 1024;
    static final long CREDIT_LIMIT_TICKS = Long.MAX_VALUE / 4;
    static final int MAX_ORDER_QUANTITY = 1_000_000;
    static final long MAX_ORDER_NOTIONAL_TICKS = Long.MAX_VALUE / 4;
    static final long PRICE_MAX_AGE_MILLIS = Long.MAX_VALUE / 4;

    /** Format 3 (YU14): the security record carries the contract multiplier (6 columns); format
     *  2 (YU13) added book geometry/band anchors, which carry forward unchanged. */
    static final int SNAPSHOT_FORMAT = 3;
    static final int T_HEADER = 1;
    static final int T_ORDER = 2;
    static final int T_POSITION = 3;
    static final int T_PRICE = 4;
    static final int T_END = 5;
    static final int T_POLICY = 6;
    static final int T_ACCOUNT = 7;
    static final int T_SECURITY = 8;
    static final int T_IDEMPOTENCY = 9;
    static final int T_SYMBOL = 10;
    static final int T_BOOK = 11;

    /** Egress ack kind for symbol registration (outside OutputEvent's 1..8 range). */
    public static final byte KIND_SYMBOL_REGISTERED = 100;

    /** Egress ack kind for the YU15 risk-extract marker; the ack carries the sequence N it landed
     *  at, which is the name the extract is stamped with. */
    public static final byte KIND_RISK_EXTRACT_MARKED = 101;

    // long appliedSeq, int orderRef, byte kind, long tradeSeq at 13..20, then three class bytes:
    //  21 restingClass — 1 = counterparty resting-order update, 0 = direct response (FR-LOB07);
    //  22 riskReason   — RiskReason ordinal, so a synchronous /trades reject can answer WHY
    //                    (YU12 3394186 put this at 21; moved here because 21 is taken above);
    //  23 marketTrade  — 1 = this output came from a TYPE_TRADE_NEW apply. YU13's crossing book
    //                    emits KIND_TRADE_BOOKED twice per MATCH for ordinary order fills, so
    //                    kind alone no longer identifies the /trades path; without this byte the
    //                    gateway would answer a market trade from a foreign fill's ack.
    static final int EGRESS_ACK_LENGTH = 24;

    /** Snapshot transport seam: production offers to the cluster snapshot publication; tests
     *  capture buffers directly. One call per record. */
    interface SnapshotWriter {
        void write(DirectBuffer buffer, int offset, int length);
    }

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final InputEvent event = new InputEvent();
    private final UnsafeBuffer ackBuffer = new UnsafeBuffer(new byte[EGRESS_ACK_LENGTH]);
    private final ExpandableArrayBuffer snapshotBuffer = new ExpandableArrayBuffer();

    private Cluster cluster;
    private IdleStrategy idle;
    private BlpRiskState risk;
    private MatchingEngine engine;
    private RingBuffer<OutputEvent> outputRing;
    private Sequence outputConsumed;
    private ClientSession activeSession; // apply-scoped: egress target for mid-apply backpressure drains
    // Apply-scoped: true while a TYPE_TRADE_NEW is being applied, so every output drained for it
    // (including mid-apply backpressure drains) is stamped as a market-trade decision. YU13's
    // crossing book emits KIND_TRADE_BOOKED for both sides of every ORDER match, so the ack kind
    // alone can no longer tell the /trades path from ordinary fills.
    private boolean applyingMarketTrade;
    private boolean stampFirstApplyAsLeader; // Phase-0 SLO clock: armed on LEADER, fires once

    private long nextOrderRef = 1;
    private long highestIssuedRef;
    private long appliedSeq;
    private boolean snapshotHeaderSeen;
    // Symbol identity as replicated state (matrix F2): ids assigned in committed-log order,
    // never evicted, so the generator derives from the mapping itself on restore.
    private final String[] tickerById = new String[MAX_SECURITIES];
    private int nextSymbolId;

    private volatile int snapshotsTaken;
    private volatile long lastLoadedNextOrderRef = -1;
    private volatile long lastExtractCutSeq = -1;
    private volatile String lastExtractCutSha;
    private volatile Cluster.Role role = Cluster.Role.FOLLOWER;
    // LATENCY-01 Phase B: leader-clock commit/apply split; null unless LATENCY_DECOMP=1.
    private final LeaderApplyLatency latency = LeaderApplyLatency.fromEnvOrNull();
    // Leader-side cluster-egress → NATS /trades bridge (YU12): only started when TRADE_BRIDGE_NATS_URL
    // is set, so default behaviour is unchanged. Null on every member until then.
    private TradeNatsPublisher tradeBridge;
    // Leader-side order-lifecycle → NATS /orders bridge (YU13): the order-state sibling of the trade
    // bridge, gated on the same env so default behaviour is unchanged. Null on every member until then.
    private OrderNatsPublisher orderBridge;
    // Leader-side position-cut → NATS bridge (YU15). Gated by RISK_EXTRACT_NATS_URL; null until set.
    private RiskExtractCutPublisher extractBridge;

    @Override
    public void onStart(final Cluster cluster, final Image snapshotImage) {
        this.cluster = cluster;
        this.idle = cluster.idleStrategy();
        this.role = cluster.role();
        initEngine();
        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }
        final String bridgeUrl = System.getenv("TRADE_BRIDGE_NATS_URL");
        if (bridgeUrl != null && !bridgeUrl.isBlank()) {
            tradeBridge = new TradeNatsPublisher(bridgeUrl, "/trades", 1 << 16);
            tradeBridge.start();
            // Epoch-qualified order ids so the read-model key never collides across incarnations
            // (brief 05 item 0). Same value on every member via the manifest; bumped with a DB wipe.
            final String epoch = System.getenv("CLUSTER_EPOCH");
            orderBridge = new OrderNatsPublisher(bridgeUrl, "/orders",
                epoch == null || epoch.isBlank() ? "1" : epoch, 1 << 16);
            orderBridge.start();
        }
        final String extractUrl = System.getenv("RISK_EXTRACT_NATS_URL");
        if (extractUrl != null && !extractUrl.isBlank()) {
            extractBridge = new RiskExtractCutPublisher(extractUrl,
                System.getenv().getOrDefault("RISK_EXTRACT_CUT_SUBJECT", "risk.extract.cut"));
            extractBridge.start();
        }
    }

    /** Fresh deterministic core; package-private so unit tests can drive the record codec. */
    void initEngine() {
        initEngine(POOL_SIZE, POOL_SIZE);
    }

    /**
     * Test-only sizing seam for snapshot fixtures whose retained state is intentionally much
     * larger than the production spike defaults. Production always enters through
     * {@link #initEngine()} and therefore keeps the exact same capacities.
     */
    void initEngine(final int initialPoolSize, final int terminalRetain) {
        this.outputRing = RingBuffer.createSingleProducer(OutputEvent::new, outputRingSize());
        this.outputConsumed = new Sequence(-1);
        this.outputRing.addGatingSequences(outputConsumed);
        this.risk = new BlpRiskState(MAX_ACCOUNTS, MAX_SECURITIES, POOL_SIZE, IDEMPOTENCY_CAPACITY,
            CREDIT_LIMIT_TICKS, MAX_ORDER_QUANTITY, MAX_ORDER_NOTIONAL_TICKS, PRICE_MAX_AGE_MILLIS,
            new RiskMetrics());
        // fillFullThreshold 0: the crossing book has no threshold-driven half-fill policy (YU13).
        this.engine = new MatchingEngine(new OutputPublisher(outputRing, this::drainOnBackpressure), new HotPathMetrics(),
            MAX_SECURITIES, 0, initialPoolSize, POOL_SIZE, terminalRetain, risk);
        this.nextOrderRef = 1;
        this.highestIssuedRef = 0;
        this.appliedSeq = 0;
        this.snapshotHeaderSeen = false;
        java.util.Arrays.fill(tickerById, null);
        this.nextSymbolId = 0;
    }

    @Override
    public void onSessionOpen(final ClientSession session, final long timestamp) {
    }

    @Override
    public void onSessionClose(final ClientSession session, final long timestamp, final CloseReason closeReason) {
    }

    @Override
    public void onSessionMessage(final ClientSession session, final long timestamp,
                                 final DirectBuffer buffer, final int offset, final int length,
                                 final Header header) {
        final int templateId = codec.templateIdOf(buffer, offset, length);
        if (templateId == 7) { // SymbolRegisterMessage
            onSymbolRegister(session, buffer, offset, length);
            return;
        }
        if (templateId == 8) { // RiskExtractMessage (YU15)
            onRiskExtract(session, buffer, offset, length);
            return;
        }
        if (codec.tryDecodeInput(buffer, offset, length, event) != AeronReplicationCodec.OK) {
            return; // fail closed: unknown schema/template/version never reaches the engine (FR-AC04)
        }
        if (event.type == InputEvent.TYPE_ORDER_NEW) {
            // The generator is replicated state advanced by the committed message itself
            // (ADR-046). A duplicate retry also consumes a value — deterministic on every
            // member and replay, and never reused; the engine then answers from idempotency.
            event.orderRef = (int) nextOrderRef++;
            highestIssuedRef = Math.max(highestIssuedRef, event.orderRef);
        }
        event.seq = ++appliedSeq;
        // The unit must be read HERE, not cached in onStart: the container is told the cluster's time
        // unit when it joins the log, which is after onStart runs, so onStart still sees the default.
        // Caching it there silently left this on the millisecond branch under CLUSTER_CLOCK=nanos and
        // every commit sample went negative and was dropped. It is a field read behind the interface.
        // Null when a harness drives apply directly without a Cluster (the allocation gates do).
        final boolean nanosClusterClock = cluster != null && cluster.timeUnit() == TimeUnit.NANOSECONDS;
        // cluster time, identical on every member and replay (FR-AC06). Under CLUSTER_CLOCK=nanos the
        // cluster clock hands us epoch-NANOS; the divide is deterministic, so state is unchanged.
        event.eventTimeMillis = nanosClusterClock ? timestamp / 1_000_000L : timestamp;
        event.ingressNanos = System.nanoTime(); // telemetry only, never state
        // LATENCY-01 Phase B (leader only, side-channel — never touches replicated state): the
        // consensus commit round-trip = now - the sequencing timestamp, both read on the leader from
        // the SAME clock source as the cluster clock; the apply span is timed around onEvent+
        // drainOutputs below. Only the LEADER's commit-to-apply equals the gateway's black box, so
        // record there alone. LATENCY-02: on the ms clock this is a 1ms quantum per sample — prefer
        // CLUSTER_CLOCK=nanos, which resolves the real distribution.
        final boolean timeThis = latency != null && role == Cluster.Role.LEADER;
        final long applyStartNanos = timeThis ? System.nanoTime() : 0L;
        if (timeThis) {
            if (nanosClusterClock) {
                latency.recordCommitNanos(NanosClusterClock.epochNanos() - timestamp);
            } else {
                latency.recordCommitMillis(System.currentTimeMillis(), timestamp);
            }
        }
        activeSession = session; // backpressure drain target while the engine emits (same thread)
        applyingMarketTrade = event.type == InputEvent.TYPE_TRADE_NEW;
        engine.onEvent(event, appliedSeq, true);
        activeSession = null;
        drainOutputs(session);
        applyingMarketTrade = false;
        if (timeThis) {
            latency.recordApplyNanos(System.nanoTime() - applyStartNanos);
        }
        if (stampFirstApplyAsLeader) {
            stampFirstApplyAsLeader = false;
            System.out.println("FIRST-APPLY atMs=" + System.currentTimeMillis() + " seq=" + appliedSeq);
        }
    }

    @Override
    public void onTimerEvent(final long correlationId, final long timestamp) {
    }

    /** Symbol registration is idempotent by ticker and sequenced like any other input; the
     *  assigned id is deterministic on every member and replay. Cold path — registration is
     *  first-sighting-rare, so the String allocation here never touches the apply hot loop. */
    private void onSymbolRegister(final ClientSession session, final DirectBuffer buffer,
                                  final int offset, final int length) {
        if (codec.tryDecodeSymbolRegister(buffer, offset, length) != AeronReplicationCodec.OK) {
            return; // fail closed
        }
        appliedSeq++;
        final String ticker = codec.symbolTicker();
        int id = symbolIdFor(ticker);
        if (id < 0) {
            if (nextSymbolId >= MAX_SECURITIES) {
                id = -1; // capacity refused; deterministic everywhere
            } else {
                id = nextSymbolId++;
                tickerById[id] = ticker;
                // YU14 (ADR-052): the multiplier is a pure function of the committed ticker,
                // derived identically on every member and replay. Cold path.
                risk.putContractMultiplier(id, OccSymbol.multiplierFor(ticker));
            }
        }
        ackBuffer.putLong(0, appliedSeq);
        ackBuffer.putInt(8, id);
        ackBuffer.putByte(12, KIND_SYMBOL_REGISTERED);
        ackBuffer.putLong(13, codec.symbolRequestId());
        ackBuffer.putByte(21, (byte) 0);
        ackBuffer.putByte(22, (byte) 0);
        ackBuffer.putByte(23, (byte) 0);
        offerEgress(session);
    }

    /**
     * YU15 EOD risk-extract marker (ADR-055). A sequenced command that mutates nothing: its only
     * effect is to name a consensus sequence N. Because the marker is a committed log entry, N is
     * agreed by consensus rather than sampled by a reader, so the state every member renders at N
     * is a consistent cut by construction — no quiesce, no locks, no read-model lag.
     *
     * <p>Every member renders and hashes the cut; the identical hash across members is the
     * determinism proof (and the same rendering happens again on any replay to N). Only the leader
     * hands it to the NATS bridge, so followers never duplicate.
     *
     * <p>Cold path — once per EOD batch. It allocates freely (the render is inherently O(positions))
     * and is deliberately kept off the order-flow branch above so the hot path is untouched.
     */
    private void onRiskExtract(final ClientSession session, final DirectBuffer buffer,
                               final int offset, final int length) {
        if (codec.tryDecodeRiskExtract(buffer, offset, length) != AeronReplicationCodec.OK) {
            return; // fail closed: a malformed marker never names a sequence
        }
        appliedSeq++;
        final java.util.List<long[]> positions = engine.positionTuples();
        final String cut = RiskExtractCut.render(appliedSeq, codec.extractSessionDateEpochDay(),
            codec.extractPriceVersion(), positions, engine.priceTuples(),
            tickerById, risk::contractMultiplier);
        lastExtractCutSeq = appliedSeq;
        lastExtractCutSha = RiskExtractCut.sha256(cut);
        // Stamped on every member so a cross-member diff needs nothing but the pod logs.
        System.out.println("RISK-EXTRACT-CUT seq=" + appliedSeq + " rows=" + positions.size()
            + " sha256=" + lastExtractCutSha + " role=" + role);
        if (role == Cluster.Role.LEADER && extractBridge != null) {
            extractBridge.offer(cut);
        }
        ackBuffer.putLong(0, appliedSeq);
        ackBuffer.putInt(8, positions.size());
        ackBuffer.putByte(12, KIND_RISK_EXTRACT_MARKED);
        ackBuffer.putLong(13, codec.extractRequestId());
        ackBuffer.putByte(21, (byte) 0);
        ackBuffer.putByte(22, (byte) 0);
        ackBuffer.putByte(23, (byte) 0);
        offerEgress(session);
    }

    public int symbolIdFor(final String ticker) {
        for (int i = 0; i < nextSymbolId; i++) {
            if (ticker.equals(tickerById[i])) {
                return i;
            }
        }
        return -1;
    }

    public int symbolCount() {
        return nextSymbolId;
    }

    @Override
    public void onTakeSnapshot(final ExclusivePublication snapshotPublication) {
        writeSnapshot((buffer, offset, length) -> {
            idle.reset();
            while (snapshotPublication.offer(buffer, offset, length) < 0) {
                idle.idle();
            }
        });
        snapshotsTaken++;
    }

    @Override
    public void onRoleChange(final Cluster.Role newRole) {
        this.role = newRole;
        // Joint-plan Phase 0: the system-facing SLO clock stops at the first committed apply
        // AS LEADER (a fast role change during a snapshot barrier is not "serving"), so arm the
        // FIRST-APPLY stamp here. Cold branch: one println per election, never in the
        // allocation-gate window (no role changes there).
        if (newRole == Cluster.Role.LEADER) {
            stampFirstApplyAsLeader = true;
        }
        System.out.println("ROLE-CHANGE role=" + newRole + " atMs=" + System.currentTimeMillis());
    }

    @Override
    public void onTerminate(final Cluster cluster) {
        if (tradeBridge != null) {
            tradeBridge.stop();
        }
        if (orderBridge != null) {
            orderBridge.stop();
        }
        if (extractBridge != null) {
            extractBridge.stop();
        }
    }

    // ----- snapshot record codec (package-private: unit-tested without a cluster) --------------

    /** Serialize the complete deterministic state as self-describing records. */
    void writeSnapshot(final SnapshotWriter writer) {
        snapshotBuffer.putInt(0, T_HEADER);
        snapshotBuffer.putInt(4, SNAPSHOT_FORMAT);
        snapshotBuffer.putLong(8, nextOrderRef);
        snapshotBuffer.putLong(16, highestIssuedRef);
        snapshotBuffer.putLong(24, appliedSeq);
        snapshotBuffer.putLong(32, engine.tradeCounter());
        snapshotBuffer.putInt(40, engine.bookLevels());
        snapshotBuffer.putLong(44, engine.bookTickPx());
        writer.write(snapshotBuffer, 0, 52);

        writeTuple(writer, T_POLICY, risk.policyTuple());
        for (final long[] account : risk.accountTuples()) {
            writeTuple(writer, T_ACCOUNT, account);
        }
        for (final long[] security : risk.securityTuples()) {
            // Format 3: append the contract multiplier as the 6th column. Write the effective
            // value (never 0) so restore can fail closed on anything below 1.
            final long multiplier = risk.contractMultiplier((int) security[0]);
            writeTuple(writer, T_SECURITY, new long[] { security[0], security[1], security[2],
                security[3], security[4], multiplier == 0L ? 1L : multiplier });
        }
        for (int id = 0; id < nextSymbolId; id++) {
            final byte[] ascii = tickerById[id].getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            snapshotBuffer.putInt(0, T_SYMBOL);
            snapshotBuffer.putInt(4, id);
            snapshotBuffer.putInt(8, ascii.length);
            snapshotBuffer.putBytes(12, ascii);
            writer.write(snapshotBuffer, 0, 12 + ascii.length);
        }
        // Retention (insertion) order: restore preserves the eviction frontier (FR-IMRG14).
        for (final long[] entry : risk.idempotencyTuples()) {
            writeTuple(writer, T_IDEMPOTENCY, entry);
        }
        for (final long[] position : engine.positionTuples()) {
            writeTuple(writer, T_POSITION, position);
        }
        for (final long[] price : engine.priceTuples()) {
            writeTuple(writer, T_PRICE, price);
        }
        // Band anchors BEFORE order rows: restore must rebuild each book's geometry first so
        // open rows re-enter their exact original price levels (replica-identical bands).
        for (final long[] bookBase : engine.bookBaseTuples()) {
            writeTuple(writer, T_BOOK, bookBase);
        }
        // Open rows first in ascending ref order (arrival order — restore re-appends them into
        // their levels in the exact original FIFO), then retained terminal rows in exact
        // eviction-FIFO order — restore re-marks terminals in write order, keeping eviction
        // replica-identical.
        final int[] terminalFifo = engine.terminalOrderRefsFifo();
        final IntHashSet terminalSet = new IntHashSet(terminalFifo.length * 2);
        final long[] orderTuple = new long[MatchingEngine.SNAPSHOT_ORDER_TUPLE_LENGTH];
        for (final int ref : terminalFifo) {
            terminalSet.add(ref);
        }
        for (final int ref : engine.snapshotOrderRefsAscending()) {
            if (!terminalSet.contains(ref) && engine.copySnapshotOrderTuple(ref, orderTuple)) {
                writeTuple(writer, T_ORDER, orderTuple);
            }
        }
        for (final int ref : terminalFifo) {
            if (engine.copySnapshotOrderTuple(ref, orderTuple)) {
                writeTuple(writer, T_ORDER, orderTuple);
            }
        }
        snapshotBuffer.putInt(0, T_END);
        writer.write(snapshotBuffer, 0, 4);
    }

    /** Apply one snapshot record; returns true on the END record. Fails closed on unknown or
     *  out-of-order records and on any identifier at or beyond the restored generator. */
    boolean onSnapshotRecord(final DirectBuffer buffer, final int offset) {
        final int type = buffer.getInt(offset);
        if (!snapshotHeaderSeen && type != T_HEADER) {
            throw new IllegalStateException("snapshot corrupt: first record type " + type + ", want header");
        }
        switch (type) {
            case T_HEADER -> {
                final int format = buffer.getInt(offset + 4);
                if (format != SNAPSHOT_FORMAT) {
                    throw new IllegalStateException("unknown snapshot format: " + format);
                }
                nextOrderRef = buffer.getLong(offset + 8);
                highestIssuedRef = buffer.getLong(offset + 16);
                appliedSeq = buffer.getLong(offset + 24);
                engine.bootstrapTradeCounter(buffer.getLong(offset + 32));
                engine.adoptBookGeometry(buffer.getInt(offset + 40), buffer.getLong(offset + 44));
                snapshotHeaderSeen = true;
            }
            case T_POLICY -> risk.bootstrapPolicy(new long[] {
                buffer.getLong(offset + 4), buffer.getLong(offset + 12),
                buffer.getLong(offset + 20), buffer.getLong(offset + 28) });
            case T_ACCOUNT -> risk.bootstrapAccount(
                (int) buffer.getLong(offset + 4),
                buffer.getLong(offset + 12) != 0,
                buffer.getLong(offset + 20));
            case T_SECURITY -> {
                final int securityId = (int) buffer.getLong(offset + 4);
                final long multiplier = buffer.getLong(offset + 44);
                if (multiplier < 1L) {
                    // Fail closed (FR-LEO04): a member must never enforce un-multiplied caps.
                    throw new IllegalStateException(
                        "snapshot corrupt: security " + securityId + " multiplier " + multiplier);
                }
                risk.bootstrapSecurity(securityId,
                    buffer.getLong(offset + 12) != 0,
                    buffer.getLong(offset + 20) != 0,
                    buffer.getLong(offset + 28),
                    buffer.getLong(offset + 36));
                risk.putContractMultiplier(securityId, multiplier);
            }
            case T_IDEMPOTENCY -> risk.bootstrapIdempotency(
                buffer.getLong(offset + 4),
                (int) buffer.getLong(offset + 12),
                (byte) buffer.getLong(offset + 20));
            case T_BOOK -> engine.bootstrapBook(
                (int) buffer.getLong(offset + 4),
                buffer.getLong(offset + 12));
            case T_ORDER -> {
                final long ref = buffer.getLong(offset + 4);
                if (ref >= nextOrderRef) {
                    // Fail closed (FR-AC09): a generator at or below a restored identifier
                    // would reissue references after recovery.
                    throw new IllegalStateException(
                        "snapshot incomplete: order ref " + ref + " >= nextOrderRef " + nextOrderRef);
                }
                engine.bootstrapOrder((int) ref,
                    (int) buffer.getLong(offset + 12),   // accountId
                    (int) buffer.getLong(offset + 20),   // securityId
                    (byte) buffer.getLong(offset + 28),  // side
                    (int) buffer.getLong(offset + 36),   // quantity
                    (int) buffer.getLong(offset + 44),   // remaining
                    buffer.getLong(offset + 52),         // limitPx
                    (byte) buffer.getLong(offset + 60),  // status
                    (byte) buffer.getLong(offset + 100), // riskReason
                    buffer.getLong(offset + 68),         // lastExecPx
                    (int) buffer.getLong(offset + 76),   // lastFillQty
                    buffer.getLong(offset + 84),         // createdAtMillis
                    buffer.getLong(offset + 92),         // updatedAtMillis
                    buffer.getLong(offset + 108),        // reservedNotional
                    (int) buffer.getLong(offset + 116)); // reservedQty
            }
            case T_POSITION -> engine.bootstrapPosition(
                (int) buffer.getLong(offset + 4),
                (int) buffer.getLong(offset + 12),
                (int) buffer.getLong(offset + 20),
                buffer.getLong(offset + 28));
            case T_PRICE -> engine.bootstrapPrice(
                (int) buffer.getLong(offset + 4),
                buffer.getLong(offset + 12));
            case T_SYMBOL -> {
                final int id = buffer.getInt(offset + 4);
                final int tickerLength = buffer.getInt(offset + 8);
                final byte[] ascii = new byte[tickerLength];
                buffer.getBytes(offset + 12, ascii);
                if (id < 0 || id >= MAX_SECURITIES || tickerById[id] != null) {
                    throw new IllegalStateException("snapshot corrupt: symbol id " + id);
                }
                tickerById[id] = new String(ascii, java.nio.charset.StandardCharsets.US_ASCII);
                nextSymbolId = Math.max(nextSymbolId, id + 1);
            }
            case T_END -> {
                finishLoad();
                return true;
            }
            default -> throw new IllegalStateException("unknown snapshot record type: " + type);
        }
        return false;
    }

    /** Final load invariant (FR-AC09): the generator strictly exceeds every ID ever issued. */
    void finishLoad() {
        if (!snapshotHeaderSeen) {
            throw new IllegalStateException("snapshot corrupt: no header record");
        }
        if (nextOrderRef <= highestIssuedRef) {
            throw new IllegalStateException("snapshot incomplete: nextOrderRef " + nextOrderRef
                + " <= highestIssuedRef " + highestIssuedRef);
        }
        lastLoadedNextOrderRef = nextOrderRef;
    }

    private void loadSnapshot(final Image snapshotImage) {
        final boolean[] done = { false };
        final FragmentHandler handler = (buffer, offset, length, header) ->
            done[0] = onSnapshotRecord(buffer, offset);
        while (!done[0]) {
            final int fragments = snapshotImage.poll(handler, 16);
            if (fragments == 0) {
                if (snapshotImage.isEndOfStream()) {
                    throw new IllegalStateException("snapshot truncated: end of stream before END record");
                }
                idle.idle();
            } else {
                idle.reset();
            }
        }
    }

    private void writeTuple(final SnapshotWriter writer, final int type, final long[] tuple) {
        snapshotBuffer.putInt(0, type);
        for (int i = 0; i < tuple.length; i++) {
            snapshotBuffer.putLong(4 + 8 * i, tuple[i]);
        }
        writer.write(snapshotBuffer, 0, 4 + 8 * tuple.length);
    }

    // ----- egress ----------------------------------------------------------------------------

    /** Backpressure hook: the engine (on THIS thread) hit a full output ring mid-apply; drain the
     *  published tail to egress so the claim can retry. Without this a single event's fill cascade
     *  larger than the ring self-deadlocks the state machine — including on replay (poison log). */
    private void drainOnBackpressure() {
        drainOutputs(activeSession);
    }

    /** Drain engine outputs emitted by the just-applied event and echo them to the session. */
    private void drainOutputs(final ClientSession session) {
        final long cursor = outputRing.getCursor();
        for (long seq = outputConsumed.get() + 1; seq <= cursor; seq++) {
            final OutputEvent out = outputRing.get(seq);
            // Leader-side trade bridge: every booked trade → NATS /trades → trade-processor → DB +
            // positions + UI. Leader-only so followers never duplicate; offer is non-blocking so the
            // deterministic apply thread is never held up by NATS.
            if (out.kind == OutputEvent.KIND_TRADE_BOOKED && role == Cluster.Role.LEADER
                && tradeBridge != null) {
                tradeBridge.offer(out.tradeSeq, out.accountId, tickerById[out.securityId],
                    out.side, out.tradeQty, out.tradePx);
            }
            // Leader-side order bridge: every order-state transition → NATS /orders → read model →
            // orderbook projection → REST enumeration. Same leader-only, non-blocking discipline as
            // the trade bridge. Covers both the input's own order and counterparty resting orders
            // hit by an aggressor (FLAG_RESTING_UPDATE) — so an STP/replace cancel of a resting
            // order is observable to its owner via this feed (brief 07).
            if (OutputEvent.isOrderLifecycleKind(out.kind) && role == Cluster.Role.LEADER
                && orderBridge != null) {
                orderBridge.offer(out.orderRef, out.accountId, tickerById[out.securityId],
                    out.side, out.quantity, out.remainingQty, out.limitPx, out.status,
                    out.lastExecPx, out.lastFillQty, out.createdAtMillis, out.updatedAtMillis);
            }
            ackBuffer.putLong(0, out.inputSeq);
            ackBuffer.putInt(8, out.orderRef);
            ackBuffer.putByte(12, out.kind);
            ackBuffer.putLong(13, out.tradeSeq);
            // Correlation class (FR-LOB07): counterparty resting-order updates never complete an
            // offer's direct ack — the gateway skips them in offer/ack accounting.
            ackBuffer.putByte(21, (out.flags & OutputEvent.FLAG_RESTING_UPDATE) != 0 ? (byte) 1 : (byte) 0);
            ackBuffer.putByte(22, out.riskReason);
            ackBuffer.putByte(23, applyingMarketTrade ? (byte) 1 : (byte) 0);
            offerEgress(session);
        }
        outputConsumed.set(cursor);
    }

    /** Best-effort bounded offer: during replay or after disconnect the session is not
     *  deliverable and the committed log, not egress, is the source of truth. */
    private void offerEgress(final ClientSession session) {
        if (session == null || session.isClosing()) {
            return;
        }
        idle.reset();
        // 20 attempts is sub-millisecond worst case. The old bound (1000 x backoff idle, ~1s
        // per undeliverable ack) let one non-draining client session throttle the whole state
        // machine under load: apply collapsed to ~1 ack/s and the cluster appeared frozen
        // (GKE bench, 2026-07-19). Egress is best-effort BY DESIGN — a slow client gets drops,
        // never the state machine's time; the committed log remains the source of truth.
        for (int i = 0; i < 20; i++) {
            if (session.offer(ackBuffer, 0, EGRESS_ACK_LENGTH) > 0) {
                return;
            }
            idle.idle();
        }
    }

    // ----- test observability (read off-thread; volatile) ------------------------------------

    public int snapshotsTaken() {
        return snapshotsTaken;
    }

    public long lastLoadedNextOrderRef() {
        return lastLoadedNextOrderRef;
    }

    /**
     * Position on the committed consensus log — restored from the snapshot header and advanced by
     * every sequenced input. This, not the engine's {@code blpSeq}, is what "how far has this
     * member caught up" means: a member restored from a snapshot holds state as of this sequence
     * even though its engine has applied no events since (YU15, T-RXT07).
     */
    public long appliedSeq() {
        return appliedSeq;
    }

    /** Consensus sequence the most recent risk-extract marker landed at (YU15), or -1. */
    public long lastExtractCutSeq() {
        return lastExtractCutSeq;
    }

    /** SHA-256 of the most recent rendered cut — equal on every member by construction. */
    public String lastExtractCutSha() {
        return lastExtractCutSha;
    }

    public Cluster.Role role() {
        return role;
    }

    /** LATENCY-01 Phase B side channel (leader commit/apply split); null unless LATENCY_DECOMP=1. */
    public LeaderApplyLatency leaderLatency() {
        return latency;
    }

    /** Plain read for quiesced cross-member equality checks; ordered by the engine's per-event
     *  release-store (read {@code engine().blpSeq()} first). */
    public long nextOrderRef() {
        return nextOrderRef;
    }

    public MatchingEngine engine() {
        return engine;
    }

    public BlpRiskState risk() {
        return risk;
    }
}
