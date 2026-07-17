package finos.traderx.ordermatcher.cluster;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.HotPathMetrics;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.OutputPublisher;
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
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Hosts the deterministic {@link MatchingEngine} inside an Aeron Cluster service (ADR-044).
 *
 * Every input is a committed consensus-log message (ADR-045): the inherited SBE
 * {@code InputEventMessage} decoded straight into the reusable {@link InputEvent} and applied on
 * the single service thread. Event time is the cluster timestamp, never a local clock.
 *
 * The order-reference generator is plain replicated state advanced by the same committed
 * messages on every member (ADR-046): {@code ORDER_NEW} ingress carries no reference; the
 * service assigns {@code nextOrderRef++} while applying, so journal-replay/injection seams that
 * produced the parent state's ID reuse cannot exist. {@code onTakeSnapshot} persists the
 * complete deterministic state — book, generators, positions, prices — and recovery fails closed
 * if the restored generator does not exceed every restored identifier.
 */
public final class MatchingEngineClusteredService implements ClusteredService {
    // ponytail: spike-fixed engine sizing; the production path re-reads these from properties
    static final int MAX_SECURITIES = 64;
    static final int FILL_FULL_THRESHOLD = 100;
    static final int POOL_SIZE = 1024;
    static final int OUTPUT_RING_SIZE = 4096;

    static final int SNAPSHOT_FORMAT = 1;
    static final int T_HEADER = 1;
    static final int T_ORDER = 2;
    static final int T_POSITION = 3;
    static final int T_PRICE = 4;
    static final int T_END = 5;

    static final int EGRESS_ACK_LENGTH = 24; // long appliedSeq, int orderRef, byte kind, long tradeSeq at 13..20

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final InputEvent event = new InputEvent();
    private final UnsafeBuffer ackBuffer = new UnsafeBuffer(new byte[EGRESS_ACK_LENGTH]);
    private final ExpandableArrayBuffer snapshotBuffer = new ExpandableArrayBuffer();

    private Cluster cluster;
    private IdleStrategy idle;
    private MatchingEngine engine;
    private RingBuffer<OutputEvent> outputRing;
    private Sequence outputConsumed;

    private long nextOrderRef = 1;
    private long highestIssuedRef;
    private long appliedSeq;

    private volatile int snapshotsTaken;
    private volatile long lastLoadedNextOrderRef = -1;

    @Override
    public void onStart(final Cluster cluster, final Image snapshotImage) {
        this.cluster = cluster;
        this.idle = cluster.idleStrategy();
        this.outputRing = RingBuffer.createSingleProducer(OutputEvent::new, OUTPUT_RING_SIZE);
        this.outputConsumed = new Sequence(-1);
        this.outputRing.addGatingSequences(outputConsumed);
        this.engine = new MatchingEngine(new OutputPublisher(outputRing), new HotPathMetrics(),
            MAX_SECURITIES, FILL_FULL_THRESHOLD, POOL_SIZE, POOL_SIZE, POOL_SIZE, null);
        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }
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
        snapshotBuffer.putInt(0, T_HEADER);
        snapshotBuffer.putInt(4, SNAPSHOT_FORMAT);
        snapshotBuffer.putLong(8, nextOrderRef);
        snapshotBuffer.putLong(16, highestIssuedRef);
        snapshotBuffer.putLong(24, appliedSeq);
        snapshotBuffer.putLong(32, engine.tradeCounter());
        offerSnapshot(snapshotPublication, 40);

        for (final long[] order : engine.allOrderTuples()) {
            writeTuple(T_ORDER, order);
            offerSnapshot(snapshotPublication, 4 + 8 * order.length);
        }
        for (final long[] position : engine.positionTuples()) {
            writeTuple(T_POSITION, position);
            offerSnapshot(snapshotPublication, 4 + 8 * position.length);
        }
        for (final long[] price : engine.priceTuples()) {
            writeTuple(T_PRICE, price);
            offerSnapshot(snapshotPublication, 4 + 8 * price.length);
        }
        snapshotBuffer.putInt(0, T_END);
        offerSnapshot(snapshotPublication, 4);
        snapshotsTaken++;
    }

    @Override
    public void onRoleChange(final Cluster.Role newRole) {
    }

    @Override
    public void onTerminate(final Cluster cluster) {
    }

    // ----- snapshot load ---------------------------------------------------------------------

    private void loadSnapshot(final Image snapshotImage) {
        final boolean[] done = { false };
        final FragmentHandler handler = (buffer, offset, length, header) -> {
            switch (buffer.getInt(offset)) {
                case T_HEADER -> {
                    final int format = buffer.getInt(offset + 4);
                    if (format != SNAPSHOT_FORMAT) {
                        throw new IllegalStateException("unknown snapshot format: " + format);
                    }
                    nextOrderRef = buffer.getLong(offset + 8);
                    highestIssuedRef = buffer.getLong(offset + 16);
                    appliedSeq = buffer.getLong(offset + 24);
                    engine.bootstrapTradeCounter(buffer.getLong(offset + 32));
                }
                case T_ORDER -> {
                    final long ref = buffer.getLong(offset + 4);
                    if (ref >= nextOrderRef) {
                        // Fail closed (FR-AC09): an ID generator at or below a restored
                        // identifier would reissue references after recovery.
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
                case T_END -> done[0] = true;
                default -> throw new IllegalStateException(
                    "unknown snapshot record type: " + buffer.getInt(offset));
            }
        };
        while (!done[0]) {
            final int fragments = snapshotImage.poll(handler, 16);
            if (fragments == 0) {
                if (snapshotImage.isEndOfStream()) {
                    break;
                }
                idle.idle();
            } else {
                idle.reset();
            }
        }
        if (nextOrderRef <= highestIssuedRef) {
            throw new IllegalStateException("snapshot incomplete: nextOrderRef " + nextOrderRef
                + " <= highestIssuedRef " + highestIssuedRef);
        }
        lastLoadedNextOrderRef = nextOrderRef;
    }

    private void writeTuple(final int type, final long[] tuple) {
        snapshotBuffer.putInt(0, type);
        for (int i = 0; i < tuple.length; i++) {
            snapshotBuffer.putLong(4 + 8 * i, tuple[i]);
        }
    }

    private void offerSnapshot(final ExclusivePublication publication, final int length) {
        idle.reset();
        while (publication.offer(snapshotBuffer, 0, length) < 0) {
            idle.idle();
        }
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
}
