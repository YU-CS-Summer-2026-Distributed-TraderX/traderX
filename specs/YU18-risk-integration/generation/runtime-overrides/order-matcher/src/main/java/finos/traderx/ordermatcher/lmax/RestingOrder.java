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
    /**
     * YU17 (ADR-069, format-8 scope section 1.6): the venue is PRE_OPEN and this order is HELD in the
     * session queue -- accepted, addressable, holding its orderRef, and not in any book. It is a
     * live order state, not a terminal one: at the open it is replayed through the engine's normal
     * path and becomes NEW/FILLED/REJECTED like any other; at a close it is CANCELED
     * ({@code RiskReason.SESSION_CANCELED}).
     *
     * <p>APPENDED after {@code STATUS_REJECTED}, never inserted. The status byte is serialized into
     * every snapshot's order rows exactly as {@code riskReason} is, so inserting a value would
     * renumber the ones above it and silently misdecode every snapshot ever written.
     *
     * <p>It is deliberately NOT {@link #isOpen}: an open order is one the ENGINE holds, which is
     * what drives book membership, reservation release and terminal retention. A queued order is
     * held by MECS beside the engine and is invisible to all three.
     */
    public static final byte STATUS_QUEUED = 5;
    /**
     * YU18 (FR-OT27): an untriggered STOP, STOP_LIMIT or TRAILING_STOP. Held by the ENGINE (in its
     * security's pending store, never in the book), reserved, cancellable: so it IS {@link #isOpen}.
     * Appended, never inserted, for the same snapshot reason as QUEUED.
     */
    public static final byte STATUS_PENDING_TRIGGER = 6;
    /** YU18 (FR-OT24/39): a PEGGED order out of the book (no reference, or refused re-reservation). */
    public static final byte STATUS_SUSPENDED = 7;

    /** YU18 suspend reasons for {@link #STATUS_SUSPENDED}. */
    public static final byte SUSPEND_NONE = 0;
    public static final byte SUSPEND_REFERENCE = 1;
    public static final byte SUSPEND_RISK = 2;

    public int orderRef;
    public int accountId;
    public int securityId;
    public byte side;
    public int quantity;
    public int remaining;
    public long limitPx;
    public byte status;
    // RiskReason ordinal; ACCEPTED (0) unless BLP-rejected (FR-IMRG15), or SELF_TRADE_PREVENTED on
    // an order the venue cancelled to stop a self-trade (ADR-057) — the one value that rides a
    // CANCELED rather than a REJECTED order. Serialized in the snapshot order tuple, so the enum is
    // append-only.
    public byte riskReason;
    public long lastExecPx = Px.NONE;
    public int lastFillQty;
    public long createdAtMillis;
    public long updatedAtMillis;
    // Live exposure reservation (in-memory-risk-gateway): rides the pooled entry so its lifetime
    // exactly matches the order's addressability; snapshotted with the order tuple (FR-IMRG16/21).
    public long reservedNotional;
    public int reservedQty;

    // ----- YU18 order types (FR-OT01..41). Snapshotted in format 11's T_ORDER_EXT record. -----
    public byte orderType;        // OrderTypes.*; LEGACY = untyped
    public byte tif;              // OrderTypes.TIF_*; TIF_NONE for legacy orders
    public long stopPx;           // STOP/STOP_LIMIT stop; TRAILING_STOP: the CURRENT stop level
    public int displayQty;        // ICEBERG configured display
    public int displayed;         // ICEBERG visible tranche; conservation: remaining = displayed + hidden
    public byte pegRef;           // PEGGED reference
    public int pegOffset;         // PEGGED offset, grid ticks
    public long capPx;            // PEGGED cap (buy ceiling / sell floor); limitPx is the CURRENT price
    public long riskPx;           // PEGGED reservation price (FR-OT39)
    public byte suspendReason;    // SUSPEND_*
    public byte trailMode;        // TRAILING_STOP
    public long trailValue;
    public long watermark;        // TRAILING_STOP high (sell) / low (buy) print since admission
    public int sessionDate;       // DAY orders: the business date they belong to (FR-OT08)
    public long admissionSeq;     // FR-OT11 total order
    public boolean triggered;     // a STOP/STOP_LIMIT/TRAILING_STOP converted at trigger

    // YU18 store membership (BLP thread only; rebuilt on restore, never snapshotted).
    RestingOrder storeNext;       // pending list or peg list of the order's security
    RestingOrder storePrev;
    byte store;                   // STORE_*
    long bookSeq;                 // append order into a level: snapshot FIFO, never emitted
    int repriceMark;              // peg walk: round id this peg was last visited in
    static final byte STORE_NONE = 0;
    static final byte STORE_PENDING = 1;
    static final byte STORE_PEG = 2;

    // YU13 book membership (BLP thread only; rebuilt on restore, never snapshotted).
    RestingOrder bookNext;
    RestingOrder bookPrev;
    int bookLevel = LimitBook.NO_LEVEL;

    RestingOrder nextFree;        // free-list link, BLP thread only

    public boolean isOpen() {
        return status == STATUS_NEW || status == STATUS_PARTIALLY_FILLED
            || status == STATUS_PENDING_TRIGGER || status == STATUS_SUSPENDED;
    }

    /** Quantity the book shows and matches: an iceberg's tranche, otherwise everything open. */
    public int visibleQty() {
        return orderType == OrderTypes.ICEBERG ? displayed : remaining;
    }

    /** Hidden quantity of a resting iceberg (0 for every other order). */
    public int hiddenQty() {
        return orderType == OrderTypes.ICEBERG ? remaining - displayed : 0;
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
        admissionSeq = 0;
        bookSeq = 0;
        // YU18 (NFR-OT06, review I4): the typed block is written only for typed orders (the store
        // links only while in a store, which terminal() leaves), so a legacy order's is already
        // zero and the pool take skips clearing it.
        if (orderType != 0 || store != STORE_NONE) {
            orderType = 0;
            tif = 0;
            stopPx = 0;
            displayQty = 0;
            displayed = 0;
            pegRef = 0;
            pegOffset = 0;
            capPx = 0;
            riskPx = 0;
            suspendReason = 0;
            trailMode = 0;
            trailValue = 0;
            watermark = 0;
            sessionDate = 0;
            triggered = false;
            storeNext = null;
            storePrev = null;
            store = STORE_NONE;
            repriceMark = 0;
        }
        bookNext = null;
        bookPrev = null;
        bookLevel = LimitBook.NO_LEVEL;
    }
}
