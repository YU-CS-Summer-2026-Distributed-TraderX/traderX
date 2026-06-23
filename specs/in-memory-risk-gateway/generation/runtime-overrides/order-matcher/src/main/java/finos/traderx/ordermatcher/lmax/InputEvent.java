package finos.traderx.ordermatcher.lmax;

/**
 * Input-ring slot holder (one mutable instance per slot, allocated once at startup and
 * reused forever — NGC-01). Producers overwrite every relevant field in place; nothing is
 * allocated per event. Time is carried in the event (eventTimeMillis stamped at the
 * gateway) so the BLP never reads a clock (FR-09B14).
 */
public final class InputEvent {
    public static final byte TYPE_ORDER_NEW = 1;
    public static final byte TYPE_ORDER_CANCEL = 2;
    public static final byte TYPE_FORCE_FILL = 3;
    public static final byte TYPE_PRICE_TICK = 4;
    /** Market trade from the trade ticket, sequenced through the gateway (FR-09B08). */
    public static final byte TYPE_TRADE_NEW = 5;
    public static final byte TYPE_ACCOUNT_CONTROL = 6;
    public static final byte TYPE_SECURITY_CONTROL = 7;
    public static final byte TYPE_POLICY_CONTROL = 8;
    public static final byte TYPE_ENTITLEMENT_CONTROL = 9;
    public static final byte TYPE_RESTRICTION_CONTROL = 10;

    public static final byte SIDE_BUY = 0;
    public static final byte SIDE_SELL = 1;

    public long seq;              // global sequence number (the ring sequence)
    public byte type;
    public int orderRef;          // numeric part of the external ord-013-%04d id
    public int accountId;
    public int securityId;        // ticker mapped to int at the gateway
    public byte side;
    public int qty;
    public long limitPx;          // long fixed-point (x 1e6)
    public long priceTicks;       // for PRICE_TICK
    public long ingressNanos;     // System.nanoTime() at the gateway, for latency histograms
    public long eventTimeMillis;  // wall-clock stamped at the gateway (event-carried time)
    public long clientOrderKey;   // stable 64-bit idempotency key derived at the Gateway
    public long principalKey;     // trusted principal hash stamped at the Gateway
    public long controlVersion;
    public boolean controlEnabled;

    public static InputEvent newInstance() {
        return new InputEvent();
    }
}
