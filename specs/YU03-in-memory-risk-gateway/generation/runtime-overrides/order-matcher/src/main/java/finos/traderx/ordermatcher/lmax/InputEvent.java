package finos.traderx.ordermatcher.lmax;

/**
 * Input-ring slot holder (one mutable instance per slot, allocated once at startup and
 * reused forever — NGC-01). Producers overwrite every relevant field in place; nothing is
 * allocated per event. Time is carried in the event (eventTimeMillis stamped at the
 * gateway) so the BLP never reads a clock (FR-09B14).
 *
 * <p><b>Type-discriminated payload slots (in-memory-risk-gateway forward-port).</b> The journal
 * and the NATS replication stream both carry the same fixed 64-byte record; rather than grow it
 * (which would orphan existing journals and the snapshot byte-offset recovery), fields unused by
 * an event type are reused, exactly as {@code priceTicks} was already PRICE_TICK-only:
 * <ul>
 *   <li>{@code priceTicks}: price for PRICE_TICK; the {@code clientOrderKey} idempotency hash for
 *       ORDER_NEW / TRADE_NEW (FR-IMRG14); the control version for *_CONTROL (FR-IMRG11).</li>
 *   <li>{@code side}: order side for order/trade events; the enabled/kill/restricted boolean for
 *       *_CONTROL events.</li>
 *   <li>{@code qty} / {@code limitPx}: POLICY_CONTROL limit payload (maxPositionQuantity /
 *       maxConcentrationNotionalTicks); unused by other control types.</li>
 * </ul>
 * Records journaled before this state carry zeroes in those slots, which decode as
 * "no idempotency key" / version 0 — old journals replay unchanged.
 */
public final class InputEvent {
    public static final byte TYPE_ORDER_NEW = 1;
    public static final byte TYPE_ORDER_CANCEL = 2;
    public static final byte TYPE_FORCE_FILL = 3;
    public static final byte TYPE_PRICE_TICK = 4;
    /** Market trade from the trade ticket, sequenced through the gateway (FR-09B08). */
    public static final byte TYPE_TRADE_NEW = 5;
    /** Snapshot marker (state 009b recovery): sequenced so the BLP checkpoints its state at a
     *  consistent point, bounding journal replay. Carries no business payload. */
    public static final byte TYPE_SNAPSHOT = 6;
    // Versioned risk-control events (FR-IMRG11 / ADR-020): decision-relevant control changes
    // enter the same global input sequence as commands and prices, so replay reproduces the
    // exact original acceptance or rejection. Numbered after TYPE_SNAPSHOT (=6, already
    // journaled on this branch); the stale in-memory-risk-gateway branch used 6-10 for these.
    public static final byte TYPE_ACCOUNT_CONTROL = 7;
    public static final byte TYPE_SECURITY_CONTROL = 8;
    public static final byte TYPE_POLICY_CONTROL = 9;
    public static final byte TYPE_RESTRICTION_CONTROL = 10;
    /**
     * Atomic cancel-and-add of an existing order in ONE apply (YU13, ADR-058). Deliberately a new
     * command type on the EXISTING {@code InputEventMessage} (SBE template 1) rather than a new
     * template: the codec copies {@code commandType} through without interpreting it, so replace
     * costs no schema change at all. Template ids 1-8 are already allocated across the lineage —
     * 8 is YU15's {@code RiskExtractMessage}, which a YU13 worktree cannot see.
     *
     * <p>Payload, using the established type-discriminated slots:
     * {@code orderRef} = the order being replaced (same slot ORDER_CANCEL uses),
     * {@code qty} = the new TOTAL quantity, {@code limitPx} = the new limit price,
     * {@code priceTicks} = the {@code clientOrderKey} of the replace request.
     * {@code accountId}, {@code securityId} and {@code side} are NOT carried: FIX forbids changing
     * them on a replace, so the engine reads them off the original order, which is also the only
     * copy the log can prove.
     */
    public static final byte TYPE_ORDER_REPLACE = 11;

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
    public long priceTicks;       // type-discriminated: see class javadoc
    public long ingressNanos;     // System.nanoTime() at the gateway, for latency histograms
    public long eventTimeMillis;  // wall-clock stamped at the gateway (event-carried time)

    public static InputEvent newInstance() {
        return new InputEvent();
    }

    // ----- type-discriminated payload accessors (aliases; no extra wire bytes) ----------------

    /** ORDER_NEW / TRADE_NEW: stable 64-bit idempotency key derived at the Gateway; 0 = absent. */
    public long clientOrderKey() {
        return priceTicks;
    }

    public void setClientOrderKey(long key) {
        this.priceTicks = key;
    }

    /** *_CONTROL: monotonic control version assigned when the update was admitted (FR-IMRG03). */
    public long controlVersion() {
        return priceTicks;
    }

    public void setControlVersion(long version) {
        this.priceTicks = version;
    }

    /** *_CONTROL: the enabled / kill-switch-armed / restricted boolean. */
    public boolean controlEnabled() {
        return side != 0;
    }

    public void setControlEnabled(boolean enabled) {
        this.side = enabled ? (byte) 1 : (byte) 0;
    }

    /** POLICY_CONTROL: new max position quantity (0 = leave unchanged). */
    public int policyMaxPositionQty() {
        return qty;
    }

    /** POLICY_CONTROL: new max concentration notional in Px ticks (0 = leave unchanged). */
    public long policyMaxConcentrationTicks() {
        return limitPx;
    }
}
