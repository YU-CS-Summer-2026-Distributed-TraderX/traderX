package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.Nats;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leader-side order-lifecycle → NATS bridge (cluster-egress → NATS, YU13). The order-state sibling
 * of {@link TradeNatsPublisher}: same leader-only, best-effort, off-consensus tap on the output
 * ring, publishing what the deterministic engine already produces (ADR-048). It forwards the
 * KIND_ORDER_* lifecycle events the crossing book already emits per match/cancel/replace — no new
 * engine emission — onto {@code /orders}, where the read model persists the {@code orderbook}
 * projection so a client can finally enumerate its open orders (the gap that stranded the 107k book).
 *
 * <p><b>Epoch-qualified id (the trap the trade bridge fell into — brief 05 item 0).</b> {@code
 * orderRef} restarts at 1 on a fresh cluster incarnation, so a table keyed on the bare ref collides
 * across epochs — partially, silently. The read-model key is therefore {@code epoch + "-" + orderRef}.
 * The epoch comes from {@code CLUSTER_EPOCH} (identical on every member via the manifest): stable
 * across failover (orderRef does not reset on failover, and the restarted pod reads the same env),
 * and bumped together with wiping the DB on a fresh incarnation — "they are one artifact". This is
 * off-consensus but its CONTENT is a pure function of engine state + a shared constant, so every
 * member would emit the same id if promoted.
 *
 * <p>The service (apply) thread NEVER blocks on NATS: {@link #offer} is non-blocking and a full
 * queue drops+counts+WARNs (the rejection signal — this path is not allowed to drop silently, brief
 * 05 item 3). Delivery is at-least-once and the read model upserts by id, so a replay is idempotent.
 */
final class OrderNatsPublisher {

    /** ponytail: one small holder per lifecycle event; pool if the bridge must sustain a flood.
     *  Package-private so the encode() seam is unit-testable without a NATS server. */
    static final class Rec {
        long orderRef;
        int accountId;
        String security;
        byte side;
        int quantity;
        int remainingQty;
        long limitPx;
        byte status;
        long lastExecPx;
        int lastFillQty;
        long createdAtMillis;
        long updatedAtMillis;
        // OrderTrace correlation key, or 0 for "this update names no trace". The KEY, not the
        // rendered id: traceIdHex allocates a String and offer() runs on the deterministic apply
        // thread, so the hex is rendered in encode() on this bridge's own thread instead.
        long traceKey;
    }

    private final String url;
    private final String subject;
    private final String epoch;
    private final OneToOneConcurrentArrayQueue<Rec> queue;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;
    private Thread thread;
    private volatile Connection nats;

    OrderNatsPublisher(final String url, final String subject, final String epoch, final int capacity) {
        this.url = url;
        this.subject = subject;
        this.epoch = epoch;
        this.queue = new OneToOneConcurrentArrayQueue<>(capacity);
    }

    void start() {
        thread = new Thread(this::run, "order-nats-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    /** Service (apply) thread — non-blocking, never throws, allocates one small Rec. */
    /** Pre-traceKey form, for the ancestor states whose overrides predate the read-model trace id
     *  (bfc3ace8). 0 means "no trace", which is exactly what those states emit. */
    void offer(final long orderRef, final int accountId, final String security, final byte side,
               final int quantity, final int remainingQty, final long limitPx, final byte status,
               final long lastExecPx, final int lastFillQty,
               final long createdAtMillis, final long updatedAtMillis) {
        offer(orderRef, accountId, security, side, quantity, remainingQty, limitPx, status,
              lastExecPx, lastFillQty, createdAtMillis, updatedAtMillis, 0L);
    }

    void offer(final long orderRef, final int accountId, final String security, final byte side,
               final int quantity, final int remainingQty, final long limitPx, final byte status,
               final long lastExecPx, final int lastFillQty,
               final long createdAtMillis, final long updatedAtMillis, final long traceKey) {
        if (security == null) {
            return; // unresolved ticker — nothing the downstream can key on
        }
        final Rec r = new Rec();
        r.orderRef = orderRef;
        r.accountId = accountId;
        r.security = security;
        r.side = side;
        r.quantity = quantity;
        r.remainingQty = remainingQty;
        r.limitPx = limitPx;
        r.status = status;
        r.lastExecPx = lastExecPx;
        r.lastFillQty = lastFillQty;
        r.createdAtMillis = createdAtMillis;
        r.updatedAtMillis = updatedAtMillis;
        r.traceKey = traceKey;
        if (!queue.offer(r)) {
            final long n = dropped.incrementAndGet();
            // Rejection signal (brief 05 item 3): never a silent drop. Throttled so a flood WARNs
            // at bounded rate, but the very first drop is always visible.
            if (n == 1 || n % 10_000 == 0) {
                System.out.println("WARN order-nats-bridge queue full: dropped=" + n
                    + " (read model will miss order updates until it drains)");
            }
        }
    }

    private void run() {
        while (running && nats == null) {
            try {
                nats = Nats.connect(url);
            } catch (final Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException ie) {
                    return;
                }
            }
        }
        final StringBuilder sb = new StringBuilder(320);
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
                // and idempotent-dedupable downstream by the epoch-qualified order id.
            }
        }
    }

    /** {@code NatsEnvelope<OrderUpdate>} JSON — the read model ignores any envelope whose type is
     *  not exactly "OrderUpdate". Price ticks: 1 unit == 1_000_000. */
    byte[] encode(final StringBuilder sb, final Rec r) {
        final boolean buy = r.side == 0; // finos.traderx.ordermatcher.lmax.InputEvent.SIDE_BUY
        sb.setLength(0);
        sb.append("{\"topic\":\"").append(subject)
          .append("\",\"from\":\"cluster-bridge\",\"type\":\"OrderUpdate\",\"date\":")
          .append(r.updatedAtMillis)
          .append(",\"payload\":{\"id\":\"").append(epoch).append('-').append(r.orderRef)
          .append("\",\"accountId\":").append(r.accountId)
          .append(",\"security\":\"").append(r.security)
          .append("\",\"side\":\"").append(buy ? "Buy" : "Sell")
          .append("\",\"quantity\":").append(r.quantity)
          .append(",\"remainingQuantity\":").append(r.remainingQty)
          .append(",\"limitPrice\":");
        appendPx(sb, r.limitPx);
        sb.append(",\"status\":\"").append(statusName(r.status))
          .append("\",\"lastExecutionPrice\":");
        appendPx(sb, r.lastExecPx);
        sb.append(",\"lastFillQuantity\":").append(r.lastFillQty)
          .append(",\"createdAt\":").append(r.createdAtMillis)
          .append(",\"updatedAt\":").append(r.updatedAtMillis);
        // OTEL-01 (brief 07): the order's own 32-hex W3C trace id, so the read model can join a
        // trace to an order NOBODY here submitted — over FIX, from the algo engine, from another
        // browser. The id is DERIVED from the client idempotency key, which stops at the gateway,
        // so persisting it once at NEW is the only way a later reader can name it.
        //
        // OMITTED, never empty or fabricated, when the key is 0: an order with no client key has
        // no derivable id, and an unsampled one emitted no spans. A fabricated id is worse than
        // none — it resolves to nothing and reads as a broken join. OrderUpdate ignores unknown
        // fields, so an absent traceId is a null on the consumer, not a parse failure.
        if (r.traceKey != 0L) {
            sb.append(",\"traceId\":\"").append(OrderTrace.traceIdHex(r.traceKey)).append('"');
        }
        sb.append("}}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Six-digit fractional ticks, zero-padded, no allocation. Negative (Px.NONE) → 0.
     *  Package-private so {@link KdbTapWriter} formats prices identically — two implementations of
     *  tick formatting is one rounding difference away from the analytical store disagreeing with
     *  the read model about the same fill. */
    static void appendPx(final StringBuilder sb, final long ticks) {
        final long px = ticks < 0 ? 0 : ticks;
        sb.append(px / 1_000_000L).append('.');
        final long frac = px % 1_000_000L;
        for (long div = 100_000L; div >= 1; div /= 10) {
            sb.append((char) ('0' + (frac / div) % 10));
        }
    }

    /** RestingOrder.STATUS_* ordinal → orderbook.status CHECK-constraint string. */
    private static String statusName(final byte status) {
        return switch (status) {
            case 0 -> "NEW";
            case 1 -> "PARTIALLY_FILLED";
            case 2 -> "FILLED";
            case 3 -> "CANCELED";
            case 4 -> "REJECTED";
            // YU17 (ADR-069): PRE_OPEN-queued — accepted, holding its ref, in no book. Without
            // its own name it would fall to the default and read as a live resting order, which
            // is exactly what decision (g) put STATUS_QUEUED in scope to prevent.
            case 5 -> "QUEUED";
            default -> "NEW";
        };
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
