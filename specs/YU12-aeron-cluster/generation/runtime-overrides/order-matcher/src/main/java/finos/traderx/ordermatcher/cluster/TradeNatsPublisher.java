package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.Nats;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leader-side trade → NATS bridge (cluster-egress → NATS, YU12).
 *
 * <p>The deterministic service (apply) thread offers each booked trade (KIND_TRADE_BOOKED) to a
 * lock-free SPSC queue; a daemon thread drains it and publishes a {@code NatsEnvelope<TradeOrder>}
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
 * <p>Delivery is at-least-once: {@code id = tradeSeq + side}, and trade-processor keys Trade by id,
 * so a replay dedups. The service thread NEVER blocks on NATS — {@link #offer} is non-blocking and
 * a full queue drops+counts (only reachable under the synthetic 134k flood, never real trade rates).
 */
final class TradeNatsPublisher {

    /** ponytail: one small holder per booked trade; pool if the bridge must sustain the 134k flood. */
    private static final class Rec {
        long tradeSeq;
        int accountId;
        String security;
        byte side;
        int qty;
        long tradePx;
        int orderRef;
    }

    private final String url;
    private final String subject;
    // Epoch, for the same reason OrderNatsPublisher carries one: an orderRef is unique only WITHIN
    // an epoch, so a bare ref would collide across incarnations and silently join a trade to some
    // previous epoch's order. `<epoch>-<orderRef>` is exactly the open-order read-model key, which
    // is what makes the trade row joinable to the order row without a lookup table.
    private final String epoch;
    private final OneToOneConcurrentArrayQueue<Rec> queue;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;
    private Thread thread;
    private volatile Connection nats;

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
        this.url = url;
        this.subject = subject;
        this.epoch = epoch == null || epoch.isBlank() ? "0" : epoch;
        this.queue = new OneToOneConcurrentArrayQueue<>(capacity);
    }

    void start() {
        thread = new Thread(this::run, "trade-nats-bridge");
        thread.setDaemon(true);
        thread.start();
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
        if (security == null) {
            return; // unresolved ticker — nothing the downstream can key on
        }
        final Rec r = new Rec();
        r.tradeSeq = tradeSeq;
        r.orderRef = orderRef;
        r.accountId = accountId;
        r.security = security;
        r.side = side;
        r.qty = qty;
        r.tradePx = tradePx;
        if (!queue.offer(r)) {
            dropped.incrementAndGet();
        }
    }

    private void run() {
        while (running && nats == null) {
            try {
                nats = Nats.connect(url); // reconnect/buffering is handled by the client thereafter
            } catch (final Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException ie) {
                    return;
                }
            }
        }
        final StringBuilder sb = new StringBuilder(256);
        while (running) {
            final Rec r = queue.poll();
            if (r == null) {
                try {
                    Thread.sleep(1);
                } catch (final InterruptedException e) {
                    return;
                }
                continue;
            }
            try {
                nats.publish(subject, encode(sb, r));
                published.incrementAndGet();
            } catch (final Exception e) {
                // best-effort; the NATS client reconnects on its own. A hard loss here is bounded
                // and idempotent-dedupable downstream by trade id.
            }
        }
    }

    /** {@code NatsEnvelope<TradeOrder>} JSON — trade-processor ignores any envelope whose type is
     *  not exactly "TradeOrder" (payload class simple name). Price ticks: 1 unit == 1_000_000. */
    private byte[] encode(final StringBuilder sb, final Rec r) {
        final boolean buy = r.side == 0; // finos.traderx.ordermatcher.lmax.InputEvent.SIDE_BUY
        final long px = r.tradePx < 0 ? 0 : r.tradePx;
        final long whole = px / 1_000_000L;
        final long frac = px % 1_000_000L;
        sb.setLength(0);
        sb.append("{\"topic\":\"").append(subject)
          .append("\",\"from\":\"cluster-bridge\",\"type\":\"TradeOrder\",\"date\":")
          .append(System.currentTimeMillis())
          .append(",\"payload\":{\"id\":\"").append(r.tradeSeq).append(buy ? "-B" : "-S")
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
          .append("\"}}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    long published() {
        return published.get();
    }

    long dropped() {
        return dropped.get();
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        final Connection c = nats;
        if (c != null) {
            try {
                c.close();
            } catch (final Exception ignore) {
                // shutting down
            }
        }
    }
}
