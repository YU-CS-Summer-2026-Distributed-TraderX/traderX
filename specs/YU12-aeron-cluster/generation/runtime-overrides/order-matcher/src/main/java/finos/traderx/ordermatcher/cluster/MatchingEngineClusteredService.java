package finos.traderx.ordermatcher.cluster;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.HotPathMetrics;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
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

import java.util.List;

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
 * Snapshot completeness (ADR-046, `system/snapshot-completeness-matrix.md`): {@code
 * onTakeSnapshot} persists every future-output generator and admission dependency — the
 * generators, the book (open rows first, retained terminal rows in exact eviction-FIFO order so
 * bounded-retention eviction stays replica-identical), positions, engine prices, risk policy,
 * account control/executed exposure, security control/price freshness, and idempotency entries
 * in retention order. Per-order reservations ride on the order rows; the account/exposure
 * aggregates are rebuilt from them on load so the two can never disagree. Recovery fails closed
 * when a restored identifier reaches the restored generator.
 */
public final class MatchingEngineClusteredService implements ClusteredService {
    // ponytail: spike-fixed engine/risk sizing; the production path re-reads these from
    // properties — they must be identical on every member (config identity, see the matrix)
    static final int MAX_SECURITIES = 64;
    static final int FILL_FULL_THRESHOLD = 100;
    static final int POOL_SIZE = 1024;
    static final int OUTPUT_RING_SIZE = 4096;
    static final int MAX_ACCOUNTS = 64;
    static final int IDEMPOTENCY_CAPACITY = 1024;
    static final long CREDIT_LIMIT_TICKS = Long.MAX_VALUE / 4;
    static final int MAX_ORDER_QUANTITY = 1_000_000;
    static final long MAX_ORDER_NOTIONAL_TICKS = Long.MAX_VALUE / 4;
    static final long PRICE_MAX_AGE_MILLIS = Long.MAX_VALUE / 4;

    static final int SNAPSHOT_FORMAT = 1;
    static final int T_HEADER = 1;
    static final int T_ORDER = 2;
    static final int T_POSITION = 3;
    static final int T_PRICE = 4;
    static final int T_END = 5;
    static final int T_POLICY = 6;
    static final int T_ACCOUNT = 7;
    static final int T_SECURITY = 8;
    static final int T_IDEMPOTENCY = 9;

    static final int EGRESS_ACK_LENGTH = 24; // long appliedSeq, int orderRef, byte kind, long tradeSeq at 13..20

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

    private long nextOrderRef = 1;
    private long highestIssuedRef;
    private long appliedSeq;
    private boolean snapshotHeaderSeen;

    private volatile int snapshotsTaken;
    private volatile long lastLoadedNextOrderRef = -1;

    @Override
    public void onStart(final Cluster cluster, final Image snapshotImage) {
        this.cluster = cluster;
        this.idle = cluster.idleStrategy();
        initEngine();
        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }
    }

    /** Fresh deterministic core; package-private so unit tests can drive the record codec. */
    void initEngine() {
        this.outputRing = RingBuffer.createSingleProducer(OutputEvent::new, OUTPUT_RING_SIZE);
        this.outputConsumed = new Sequence(-1);
        this.outputRing.addGatingSequences(outputConsumed);
        this.risk = new BlpRiskState(MAX_ACCOUNTS, MAX_SECURITIES, POOL_SIZE, IDEMPOTENCY_CAPACITY,
            CREDIT_LIMIT_TICKS, MAX_ORDER_QUANTITY, MAX_ORDER_NOTIONAL_TICKS, PRICE_MAX_AGE_MILLIS,
            new RiskMetrics());
        this.engine = new MatchingEngine(new OutputPublisher(outputRing), new HotPathMetrics(),
            MAX_SECURITIES, FILL_FULL_THRESHOLD, POOL_SIZE, POOL_SIZE, POOL_SIZE, risk);
        this.nextOrderRef = 1;
        this.highestIssuedRef = 0;
        this.appliedSeq = 0;
        this.snapshotHeaderSeen = false;
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
        event.eventTimeMillis = timestamp; // cluster time, identical on every member and replay (FR-AC06)
        event.ingressNanos = System.nanoTime(); // telemetry only, never state
        engine.onEvent(event, appliedSeq, true);
        drainOutputs(session);
    }

    @Override
    public void onTimerEvent(final long correlationId, final long timestamp) {
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
    }

    @Override
    public void onTerminate(final Cluster cluster) {
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
        writer.write(snapshotBuffer, 0, 40);

        writeTuple(writer, T_POLICY, risk.policyTuple());
        for (final long[] account : risk.accountTuples()) {
            writeTuple(writer, T_ACCOUNT, account);
        }
        for (final long[] security : risk.securityTuples()) {
            writeTuple(writer, T_SECURITY, security);
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
        // Open rows first (ring-neutral), then retained terminal rows in exact eviction-FIFO
        // order — restore re-marks terminals in write order, keeping eviction replica-identical.
        final int[] terminalFifo = engine.terminalOrderRefsFifo();
        final IntHashSet terminalSet = new IntHashSet(terminalFifo.length * 2);
        final List<long[]> allOrders = engine.allOrderTuples();
        for (final int ref : terminalFifo) {
            terminalSet.add(ref);
        }
        for (final long[] order : allOrders) {
            if (!terminalSet.contains((int) order[0])) {
                writeTuple(writer, T_ORDER, order);
            }
        }
        for (final int ref : terminalFifo) {
            for (final long[] order : allOrders) {
                if ((int) order[0] == ref) {
                    writeTuple(writer, T_ORDER, order);
                    break;
                }
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
                snapshotHeaderSeen = true;
            }
            case T_POLICY -> risk.bootstrapPolicy(new long[] {
                buffer.getLong(offset + 4), buffer.getLong(offset + 12),
                buffer.getLong(offset + 20), buffer.getLong(offset + 28) });
            case T_ACCOUNT -> risk.bootstrapAccount(
                (int) buffer.getLong(offset + 4),
                buffer.getLong(offset + 12) != 0,
                buffer.getLong(offset + 20));
            case T_SECURITY -> risk.bootstrapSecurity(
                (int) buffer.getLong(offset + 4),
                buffer.getLong(offset + 12) != 0,
                buffer.getLong(offset + 20) != 0,
                buffer.getLong(offset + 28),
                buffer.getLong(offset + 36));
            case T_IDEMPOTENCY -> risk.bootstrapIdempotency(
                buffer.getLong(offset + 4),
                (int) buffer.getLong(offset + 12),
                (byte) buffer.getLong(offset + 20));
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

    /** Drain engine outputs emitted by the just-applied event and echo them to the session. */
    private void drainOutputs(final ClientSession session) {
        final long cursor = outputRing.getCursor();
        for (long seq = outputConsumed.get() + 1; seq <= cursor; seq++) {
            final OutputEvent out = outputRing.get(seq);
            ackBuffer.putLong(0, out.inputSeq);
            ackBuffer.putInt(8, out.orderRef);
            ackBuffer.putByte(12, out.kind);
            ackBuffer.putLong(13, out.tradeSeq);
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
        for (int i = 0; i < 1000; i++) {
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

    public MatchingEngine engine() {
        return engine;
    }

    public BlpRiskState risk() {
        return risk;
    }
}
