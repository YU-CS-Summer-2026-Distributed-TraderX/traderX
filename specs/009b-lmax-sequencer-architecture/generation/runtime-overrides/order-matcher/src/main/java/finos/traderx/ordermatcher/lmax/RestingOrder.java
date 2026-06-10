package finos.traderx.ordermatcher.lmax;

/**
 * BLP-private resting-order entry. Instances are pooled (free list) and reused: taken when
 * an order arrives, returned when it reaches a terminal state — never allocated mid-life
 * in the steady state (NGC-01, technique table "object pooling for domain state").
 */
public final class RestingOrder {
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
    public long lastExecPx = Px.NONE;
    public int lastFillQty;
    public long createdAtMillis;
    public long updatedAtMillis;

    RestingOrder nextFree;        // free-list link, BLP thread only

    public boolean isOpen() {
        return status == STATUS_NEW || status == STATUS_PARTIALLY_FILLED;
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
        lastExecPx = Px.NONE;
        lastFillQty = 0;
        createdAtMillis = 0;
        updatedAtMillis = 0;
    }
}
