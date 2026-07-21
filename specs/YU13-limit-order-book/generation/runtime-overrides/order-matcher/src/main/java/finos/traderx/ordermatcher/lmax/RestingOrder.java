package finos.traderx.ordermatcher.lmax;

/**
 * BLP-private resting-order entry. The pool (free list) is filled once at startup
 * ({@code blp.book.pool-size}) and entries are taken as orders arrive (NGC-01, technique
 * table "object pooling for domain state"). Entries are retained while addressable —
 * terminal orders stay in the dense index up to the bounded terminal-retention cap so
 * cancel/force-fill of a completed order reproduces 009's "return it unchanged"
 * semantics (FR-09B13) — so the steady state allocates nothing while the pre-allocated
 * pool lasts; beyond it, growth follows the same amortized-doubling rule as the indexes.
 *
 * YU13 crossing book: an OPEN order is a node in its security's {@link LimitBook} —
 * {@code bookNext}/{@code bookPrev} are the intrusive FIFO links of its price level and
 * {@code bookLevel} is the level's array slot ({@code LimitBook.NO_LEVEL} when the order
 * is not resting: terminal, rejected, or an aggressor mid-cross). Intrusive links keep
 * insert/cancel/match O(1) with zero per-node allocation; they are rebuilt on restore,
 * never serialized.
 */
public final class RestingOrder implements finos.traderx.ordermatcher.risk.ReservationHolder {
    public static final byte STATUS_NEW = 0;
    public static final byte STATUS_PARTIALLY_FILLED = 1;
    public static final byte STATUS_FILLED = 2;
    public static final byte STATUS_CANCELED = 3;
    public static final byte STATUS_REJECTED = 4;

    public int orderRef;
    public int accountId;
    public int securityId;
    public byte side;
    public int quantity;
    public int remaining;
    public long limitPx;
    public byte status;
    public byte riskReason;       // RiskReason ordinal; ACCEPTED (0) unless BLP-rejected (FR-IMRG15)
    public long lastExecPx = Px.NONE;
    public int lastFillQty;
    public long createdAtMillis;
    public long updatedAtMillis;
    // Live exposure reservation (in-memory-risk-gateway): rides the pooled entry so its lifetime
    // exactly matches the order's addressability; snapshotted with the order tuple (FR-IMRG16/21).
    public long reservedNotional;
    public int reservedQty;

    // YU13 book membership (BLP thread only; rebuilt on restore, never snapshotted).
    RestingOrder bookNext;
    RestingOrder bookPrev;
    int bookLevel = LimitBook.NO_LEVEL;

    RestingOrder nextFree;        // free-list link, BLP thread only

    public boolean isOpen() {
        return status == STATUS_NEW || status == STATUS_PARTIALLY_FILLED;
    }

    /** True while this order is linked into a price level of its security's book. */
    public boolean isResting() {
        return bookLevel != LimitBook.NO_LEVEL;
    }

    @Override
    public long reservedNotional() {
        return reservedNotional;
    }

    @Override
    public int reservedQty() {
        return reservedQty;
    }

    @Override
    public void setReservation(long notional, int qty) {
        this.reservedNotional = notional;
        this.reservedQty = qty;
    }

    public void reset() {
        orderRef = 0;
        accountId = 0;
        securityId = 0;
        side = 0;
        quantity = 0;
        remaining = 0;
        limitPx = 0;
        status = STATUS_NEW;
        riskReason = 0;
        lastExecPx = Px.NONE;
        lastFillQty = 0;
        createdAtMillis = 0;
        updatedAtMillis = 0;
        reservedNotional = 0;
        reservedQty = 0;
        bookNext = null;
        bookPrev = null;
        bookLevel = LimitBook.NO_LEVEL;
    }
}
