package finos.traderx.ordermatcher.cluster;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leader-side trade → NATS bridge (cluster-egress → NATS, YU12).
 *
 * <p>The deterministic service (apply) thread offers each booked trade (KIND_TRADE_BOOKED) to its
 * partition's lock-free SPSC queue; that partition's lane (YU18 {@link ReceiptLanes}: one thread and
 * connection per partition, 429 receipts counted) drains it and publishes a {@code NatsEnvelope<TradeOrder>}
 * JSON onto the {@code /trades} subject that {@code trade-processor} already consumes. From there
 * trade-processor persists the Trade + updated Position to the SQL database and republishes
 * {@code /accounts/{id}/trades} and {@code /accounts/{id}/positions}, which the Angular UI is
 * subscribed to over its NATS websocket — so the blotter and positions update live.
 *
 * <p>Why leader-side and not the gateway egress: Aeron Cluster egress is best-effort (drops under
 * load, by design) and only reaches the submitting session, so it would miss the resting side and
 * lose trades. The apply stream is the complete, ordered, deterministic source of every booked
 * trade; only the leader publishes (role check at the call site) so followers never duplicate.
 *
 * <p>Delivery is best-effort: queue overflow or an unavailable consumer can lose publication.
 * Stable descriptor-derived IDs make identical replay idempotent in trade-processor, but do not
 * provide durable NATS delivery. Frozen archive verification refuses missing projections.
 * The service thread never blocks on NATS; a full queue drops and counts.
 */
final class TradeNatsPublisher {

    /** ponytail: one small holder per booked trade; pool if the bridge must sustain the 134k flood. */
    private static final class Rec {
        long tradeSeq;
        long consensusSequence;
        int accountId;
        String security;
        byte side;
        int qty;
        long tradePx;
        int orderRef;
    }

    private final String url;
    private final String subject;
    // YU18: per-account partition subjects (BridgePartitions); one entry = the bare subject.
    private final String[] subjects;
    // Epoch, for the same reason OrderNatsPublisher carries one: an orderRef is unique only WITHIN
    // an epoch, so a bare ref would collide across incarnations and silently join a trade to some
    // previous epoch's order. `<epoch>-<orderRef>` is exactly the open-order read-model key, which
    // is what makes the trade row joinable to the order row without a lookup table.
    private final String epoch;
    private final RunDescriptor descriptor;
    // YU18 (§7.9): one loss-counting publish lane per partition; see ReceiptLanes.
    private final ReceiptLanes<Rec> lanes;
    private final AtomicLong dropped = new AtomicLong();

    /**
     * Pre-epoch form, kept so the ANCESTOR STATES still compile.
     *
     * This class lives in the YU12 layer and is therefore shared by every state from YU12 up, but
     * only YU17's MatchingEngineClusteredService was updated when the epoch parameter was added
     * (cf0f6f9a). The YU12-YU16 overrides on this branch still call the three-argument form, so
     * generating any of those states from this worktree failed to compile — the tip could no longer
     * render its own ancestors, which is the thing carrying every ancestor pack is FOR.
     *
     * A compatibility overload rather than updating five callers, deliberately: changing them would
     * give YU12-YU16 epoch-qualified trade ids, i.e. silently alter what those states demonstrate.
     * "0" is what CLUSTER_EPOCH-unset already produced, so this preserves their behaviour exactly.
     */
    TradeNatsPublisher(final String url, final String subject, final int capacity) {
        this(url, subject, "0", capacity);
    }

    TradeNatsPublisher(final String url, final String subject, final String epoch, final int capacity) {
        this(url,subject,epoch,null,capacity);
    }
    TradeNatsPublisher(final String url, final String subject, final String epoch,
                       final RunDescriptor descriptor, final int capacity) {
        this(url, subject, epoch, descriptor, capacity, BridgePartitions.count());
    }

    TradeNatsPublisher(final String url, final String subject, final String epoch,
                       final RunDescriptor descriptor, final int capacity, final int partitions) {
        this.descriptor=descriptor;
        this.url = url;
        this.subject = subject;
        this.subjects = BridgePartitions.subjects(subject, partitions);
        this.epoch = descriptor!=null ? descriptor.epoch() : epoch == null || epoch.isBlank() ? "0" : epoch;
        this.lanes = new ReceiptLanes<>("trade", url, subjects, capacity, this::encode, r -> r.accountId, this::idOf);
    }

    void start() {
        lanes.start();
    }

    /** Service (apply) thread — non-blocking, never throws, allocates one small Rec. */
    /** Pre-orderRef form, for the ancestor callers described on the constructor above. 0 is the
     *  same value writeTradeBooked emitted before it learned the originating order. */
    void offer(final long tradeSeq, final int accountId, final String security,
               final byte side, final int qty, final long tradePx) {
        offer(tradeSeq, accountId, security, side, qty, tradePx, 0);
    }

    void offer(final long tradeSeq, final int accountId, final String security,
               final byte side, final int qty, final long tradePx, final int orderRef) {
        offer(tradeSeq,accountId,security,side,qty,tradePx,orderRef,0L);
    }
    void offer(final long tradeSeq, final int accountId, final String security,
               final byte side, final int qty, final long tradePx, final int orderRef,
               final long consensusSequence) {
        if (security == null) {
            return; // unresolved ticker — nothing the downstream can key on
        }
        final Rec r = new Rec();
        r.tradeSeq = tradeSeq;
        r.consensusSequence=consensusSequence;
        r.orderRef = orderRef;
        r.accountId = accountId;
        r.security = security;
        r.side = side;
        r.qty = qty;
        r.tradePx = tradePx;
        if (!lanes.offer(r)) {
            final long n = dropped.incrementAndGet();
            // YU18: was a bare counter nobody read - same throttled WARN as the order bridge.
            if (n == 1 || n % 10_000 == 0) {
                System.out.println("WARN trade-nats-bridge queue full: dropped=" + n
                    + " (trade-processor will miss these trade sides; it counts them as tradeSeq gaps)");
            }
        }
    }

    /** Cold replay verifier uses the identical packing and serializer as live publication. */
    static byte[] projectionEvent(finos.traderx.ordermatcher.lmax.OutputEvent out,String ticker,RunDescriptor descriptor) {
        if(ticker==null || descriptor==null) {throw new IllegalArgumentException("RUN_REPLAY_IDENTITY_REQUIRED");}
        var publisher=new TradeNatsPublisher("", "/trades",descriptor.epoch(),descriptor,2,1);
        publisher.offer(out.tradeSeq,out.accountId,ticker,out.side,out.tradeQty,out.tradePx,out.orderRef,out.inputSeq);
        return publisher.encode(new StringBuilder(512),publisher.lanes.poll());
    }

    /** The trade side's id exactly as encode() publishes it (and as the projection's recon reports it). */
    String idOf(final Rec r) {
        return descriptor==null ? r.tradeSeq+(r.side == 0 ? "-B" : "-S") : descriptor.tradeId(r.tradeSeq,r.side);
    }

    /** {@code NatsEnvelope<TradeOrder>} JSON — trade-processor ignores any envelope whose type is
     *  not exactly "TradeOrder" (payload class simple name). Price ticks: 1 unit == 1_000_000. */
    byte[] encode(final StringBuilder sb, final Rec r) {
        final boolean buy = r.side == 0; // finos.traderx.ordermatcher.lmax.InputEvent.SIDE_BUY
        final long px = r.tradePx < 0 ? 0 : r.tradePx;
        final long whole = px / 1_000_000L;
        final long frac = px % 1_000_000L;
        sb.setLength(0);
        sb.append("{\"topic\":\"").append(subject)
          .append("\",\"from\":\"cluster-bridge\",\"type\":\"TradeOrder\",\"date\":")
          .append(System.currentTimeMillis())
          .append(",\"payload\":{\"id\":\"").append(idOf(r))
          .append("\",\"state\":\"New\",\"security\":\"").append(r.security)
          .append("\",\"quantity\":").append(r.qty)
          .append(",\"price\":").append(whole).append('.');
        // six-digit fractional ticks, zero-padded, no allocation
        for (long div = 100_000L; div >= 1; div /= 10) {
            sb.append((char) ('0' + (frac / div) % 10));
        }
        sb.append(",\"accountId\":").append(r.accountId)
          .append(",\"side\":\"").append(buy ? "Buy" : "Sell")
          // sourceOrderId is already DECLARED on TradeOrder and already copied to Trade by
          // TradeService, so this is a known property rather than an additive one — no
          // FAIL_ON_UNKNOWN_PROPERTIES hazard, and no consumer change needed.
          .append("\",\"sourceOrderId\":\"").append(epoch).append('-').append(r.orderRef)
          .append('"');
        if (descriptor!=null) {sb.append(descriptor.eventFields(r.consensusSequence));}
        sb.append("}}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    long published() {
        return lanes.published();
    }

    /** Lost before NATS: the lane's queue was full, or the publish call failed. */
    long dropped() {
        return dropped.get() + lanes.failed();
    }

    /** Lost AT NATS: published, then dropped at stream ingest (429 receipts). */
    long rejected() {
        return lanes.rejected();
    }

    /** Stored by the stream (PubAck receipts). */
    long acked() {
        return lanes.acked();
    }

    long rejected(final int partition) {
        return lanes.rejected(partition);
    }

    String json() {
        return lanes.json().replaceFirst("\\{", "{\"queueDropped\":" + dropped.get() + ",");
    }

    void stop() {
        lanes.stop();
    }
}
