package finos.traderx.ordermatcher.cluster;

import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leader-side capture tap for the KDB-X analytical store (brief 06). The third sibling of
 * {@link TradeNatsPublisher} and {@link OrderNatsPublisher}: same leader-only, off-consensus,
 * best-effort tap on the output ring, same non-blocking offer, same visible drop signal. The only
 * difference is the sink — a plain append-only CSV capture log on the member's own data volume,
 * which q loads directly (see {@code tick-store/kdb/txstore.q}).
 *
 * <p><b>This is the analytical path, not the authoritative one.</b> The Aeron Archive consensus
 * journal remains the deterministic replay source of truth for recovery; nothing written here is
 * read back by the state machine, and losing all of it costs analytics, not correctness. The
 * capture log is the kdb tickerplant log, not a journal.
 *
 * <p><b>Never in the apply path.</b> {@link #offerOrder}/{@link #offerTrade} allocate one small
 * record and do a non-blocking SPSC offer; a daemon thread does every file system call. There is
 * no flush, no fsync and no open() on the service thread, so a stalled disk cannot wedge apply —
 * it fills the queue and the tap drops. Unset {@code KDB_TAP_DIR} and the whole thing is one null
 * check per output event, exactly like the two NATS bridges.
 *
 * <p><b>Drop is the sampling policy</b> (brief: "sampled under flood"), and it is never silent —
 * the first drop and every 10,000th WARN, and {@link #stop()} prints the totals. The silent-drop
 * bug class has bitten this project four times; an analytical store that quietly thins out under
 * load is exactly how a wrong VWAP gets presented as fact.
 *
 * <p>Rows are epoch-qualified for the same reason the order bridge's ids are ({@code orderRef}
 * restarts at 1 on a fresh cluster incarnation) and carry the member id, so the captures from all
 * three members can be dropped into one directory and loaded together without collision.
 */
final class KdbTapWriter {

    static final byte KIND_ORDER = 0;
    static final byte KIND_TRADE = 1;

    /** Column order must match {@code .tx.ORDER_TYPES} / {@code .tx.TRADE_TYPES} in txstore.q. */
    static final String ORDER_HEADER =
        "seq,epoch,ref,account,sym,side,qty,remaining,limitPx,status,lastExecPx,lastFillQty,createdMs,updatedMs";
    static final String TRADE_HEADER = "seq,epoch,tradeSeq,account,sym,side,qty,px,tsMs";

    /** ponytail: one small holder per captured event, mirroring the NATS bridges' Rec; pool it if
     *  the tap ever has to sustain a flood instead of sampling one. */
    static final class Rec {
        byte kind;
        long seq;
        long orderRef;
        long tradeSeq;
        int accountId;
        String security;      // null when the id was never registered — see encode()
        int securityId;
        byte side;
        int quantity;
        int remainingQty;
        long limitPx;
        byte status;
        long lastExecPx;
        int lastFillQty;
        long createdAtMillis;
        long updatedAtMillis;
    }

    private final File orderFile;
    private final File tradeFile;
    private final String epoch;
    private final OneToOneConcurrentArrayQueue<Rec> queue;
    private final AtomicLong captured = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile boolean running = true;
    private Thread thread;

    KdbTapWriter(final File dir, final String epoch, final String member, final int capacity) {
        this.orderFile = new File(dir, "txorder-" + epoch + "-" + member + ".csv");
        this.tradeFile = new File(dir, "txtrade-" + epoch + "-" + member + ".csv");
        this.epoch = epoch;
        this.queue = new OneToOneConcurrentArrayQueue<>(capacity);
    }

    void start() {
        thread = new Thread(this::run, "kdb-capture-tap");
        thread.setDaemon(true);
        thread.start();
    }

    /** Service (apply) thread — non-blocking, never throws, no file system call. */
    void offerOrder(final long seq, final long orderRef, final int accountId, final String security,
                    final int securityId, final byte side, final int quantity, final int remainingQty,
                    final long limitPx, final byte status, final long lastExecPx, final int lastFillQty,
                    final long createdAtMillis, final long updatedAtMillis) {
        final Rec r = new Rec();
        r.securityId = securityId;
        r.kind = KIND_ORDER;
        r.seq = seq;
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
        submit(r);
    }

    /** Service (apply) thread — non-blocking, never throws, no file system call. */
    void offerTrade(final long seq, final long tradeSeq, final int accountId, final String security,
                    final int securityId, final byte side, final int quantity, final long tradePx,
                    final long tsMillis) {
        final Rec r = new Rec();
        r.securityId = securityId;
        r.kind = KIND_TRADE;
        r.seq = seq;
        r.tradeSeq = tradeSeq;
        r.accountId = accountId;
        r.security = security;
        r.side = side;
        r.quantity = quantity;
        r.limitPx = tradePx;
        r.updatedAtMillis = tsMillis;
        submit(r);
    }

    private void submit(final Rec r) {
        if (!queue.offer(r)) {
            final long n = dropped.incrementAndGet();
            // Rejection signal: this path is not allowed to drop silently. Throttled so a flood
            // WARNs at a bounded rate, but the very first drop is always visible.
            if (n == 1 || n % 10_000 == 0) {
                System.out.println("WARN kdb-capture-tap queue full: dropped=" + n
                    + " (the analytical store is sampling, not capturing every event)");
            }
        }
    }

    private void run() {
        try (BufferedWriter orders = open(orderFile, ORDER_HEADER);
             BufferedWriter trades = open(tradeFile, TRADE_HEADER)) {
            final StringBuilder sb = new StringBuilder(192);
            boolean dirty = false;
            while (running) {
                final Rec r = queue.poll();
                if (r == null) {
                    if (dirty) {
                        orders.flush();
                        trades.flush();
                        dirty = false;
                    }
                    try {
                        Thread.sleep(1);
                    } catch (final InterruptedException e) {
                        break;
                    }
                    continue;
                }
                try {
                    (r.kind == KIND_ORDER ? orders : trades).write(encode(sb, r));
                    captured.incrementAndGet();
                    dirty = true;
                } catch (final Exception e) {
                    final long n = errors.incrementAndGet();
                    if (n == 1 || n % 10_000 == 0) {
                        System.out.println("WARN kdb-capture-tap write failed: errors=" + n
                            + " reason=" + e);
                    }
                }
            }
            orders.flush();
            trades.flush();
        } catch (final Exception e) {
            // Best-effort by construction: a capture log we cannot open is an analytics gap, never
            // a trading outage. Say so once, loudly, and leave the state machine alone.
            System.out.println("WARN kdb-capture-tap disabled, cannot open capture log in "
                + orderFile.getParent() + ": " + e);
        }
    }

    /** Append mode: a restarted leader adds to its own capture rather than truncating it. The
     *  header is written only when the file is created, so appends stay loadable. */
    private static BufferedWriter open(final File f, final String header) throws Exception {
        f.getParentFile().mkdirs();
        final boolean fresh = !f.exists() || f.length() == 0;
        final BufferedWriter w = new BufferedWriter(new FileWriter(f, true), 1 << 16);
        if (fresh) {
            w.write(header);
            w.write('\n');
            w.flush();
        }
        return w;
    }

    /** One CSV line. Package-private: the exact column order is the contract txstore.q parses. */
    String encode(final StringBuilder sb, final Rec r) {
        sb.setLength(0);
        sb.append(r.seq).append(',').append(epoch).append(',');
        if (r.kind == KIND_ORDER) {
            sb.append(r.orderRef);
        } else {
            sb.append(r.tradeSeq);
        }
        sb.append(',').append(r.accountId).append(',').append(symbolOf(r))
          .append(',').append(r.side == 0 ? 'B' : 'S')
          .append(',').append(r.quantity).append(',');
        if (r.kind == KIND_ORDER) {
            sb.append(r.remainingQty).append(',');
            OrderNatsPublisher.appendPx(sb, r.limitPx);
            sb.append(',').append(statusName(r.status)).append(',');
            OrderNatsPublisher.appendPx(sb, r.lastExecPx);
            sb.append(',').append(r.lastFillQty)
              .append(',').append(r.createdAtMillis)
              .append(',').append(r.updatedAtMillis);
        } else {
            OrderNatsPublisher.appendPx(sb, r.limitPx);
            sb.append(',').append(r.updatedAtMillis);
        }
        sb.append('\n');
        return sb.toString();
    }

    /** A security whose ticker was never registered is captured as {@code #<id>}, never dropped:
     *  omitting rows for it would thin the analytical store silently, which is the failure this
     *  tap exists to avoid. Resolved off the apply thread, so the fallback costs it nothing. */
    private static String symbolOf(final Rec r) {
        return r.security != null ? r.security : "#" + r.securityId;
    }

    /** Same ordinal → name mapping the order bridge publishes, so the two feeds agree. */
    private static String statusName(final byte status) {
        return switch (status) {
            case 1 -> "PARTIALLY_FILLED";
            case 2 -> "FILLED";
            case 3 -> "CANCELED";
            case 4 -> "REJECTED";
            default -> "NEW";
        };
    }

    long captured() {
        return captured.get();
    }

    long dropped() {
        return dropped.get();
    }

    long errors() {
        return errors.get();
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000); // let the last records reach disk before the process exits
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("kdb-capture-tap stopped: captured=" + captured.get()
            + " dropped=" + dropped.get() + " errors=" + errors.get());
    }
}
