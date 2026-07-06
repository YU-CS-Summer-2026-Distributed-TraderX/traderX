package finos.traderx.ordermatcher.lmax;

/**
 * BLP-private resting-order entry. The pool (free list) is filled once at startup
 * ({@code blp.book.pool-size}) and entries are taken as orders arrive (NGC-01, technique
 * table "object pooling for domain state"). Entries are retained for the life of the
 * process — terminal orders stay addressable in the book so cancel/force-fill of a
 * completed order reproduces 009's "return it unchanged" semantics (FR-09B13) — so the
 * steady state allocates nothing while the pre-allocated pool lasts; beyond it, growth
 * follows the same amortized-doubling rule as the indexes. Recycling terminal entries
 * (and bounding the book) arrives with the snapshot/eviction milestone (T09B14).
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

    RestingOrder nextFree;        // free-list link, BLP thread only

    public boolean isOpen() {
        return status == STATUS_NEW || status == STATUS_PARTIALLY_FILLED;
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
    }
}
