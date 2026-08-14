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

    /**
     * Book an OTC interest-rate swap (YU17, ADR-062). Like replace, a new command type on the
     * EXISTING {@code InputEventMessage} (SBE template 1): {@code AeronReplicationCodec} copies
     * {@code commandType} through without interpreting it. Template ids 1-8 are allocated across
     * the lineage (8 is YU15's {@code RiskExtractMessage}), and a swap needs no field the record
     * does not already carry — so it costs no schema change at all.
     *
     * <p>Payload. Five of the six values land in a slot whose EXISTING meaning already fits, which
     * is what keeps this honest rather than clever:
     * <ul>
     *   <li>{@code accountId} — the booking account, unchanged;</li>
     *   <li>{@code side} — {@code SWAP_RECEIVE_FIXED} / {@code SWAP_PAY_FIXED}. A swap has exactly
     *       one binary direction and this is the slot that carries one;</li>
     *   <li>{@code qty} — the notional in whole currency units. An {@code int}, so this caps at
     *       2,147,483,647; the gateway refuses anything larger BEFORE sequencing rather than
     *       letting it wrap into a small notional that books silently;</li>
     *   <li>{@code limitPx} — the fixed rate. Already a 1e6 fixed-point long, and a rate IS a 1e6
     *       fixed-point number (4.2% = 0.042 = 42000 ticks), so nothing is reinterpreted here;</li>
     *   <li>{@code priceTicks} — the {@code clientOrderKey}, exactly as ORDER_NEW uses it. A swap
     *       booking is a bilateral confirmation and a retried one must not create a second
     *       contract, so it keeps the full 64-bit idempotency key rather than spending the slot;</li>
     *   <li>{@code securityId} — an index into {@link SwapConventions}, NOT a symbol-table id. A
     *       swap gets no symbol-table entry (every trade is a new identity and MAX_SECURITIES is
     *       1024), so the slot is free and carries float index, payment frequency and day count
     *       as one value.</li>
     * </ul>
     * That leaves the two dates, which are the one genuinely packed field — see
     * {@link #setSwapDates(int, int)}.
     */
    public static final byte TYPE_SWAP_BOOK = 12;

    public static final byte SIDE_BUY = 0;
    public static final byte SIDE_SELL = 1;
    /** TYPE_SWAP_BOOK direction: the booking account receives / pays the FIXED leg. */
    public static final byte SWAP_RECEIVE_FIXED = SIDE_BUY;
    public static final byte SWAP_PAY_FIXED = SIDE_SELL;
    /** Largest epoch day the packed date pair holds: 65535 days after 1970-01-01 = 2149-06-06. */
    public static final int MAX_SWAP_EPOCH_DAY = 0xFFFF;

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

    // ----- TYPE_SWAP_BOOK (YU17) --------------------------------------------------------------
    //
    // The one packed field. Both dates ride `orderRef`, which is otherwise untouched for a swap:
    // the sequenced generator overwrites orderRef only for TYPE_ORDER_NEW, so the slot is free.
    // 16 unsigned bits each reaches 2149-06-06 — a booking outside that range is refused at the
    // gateway, so nothing can wrap into a plausible-looking date. Effective in the LOW half so a
    // record written with a zeroed orderRef decodes as (1970-01-01, 1970-01-01), which is
    // obviously wrong rather than subtly wrong.

    public void setSwapDates(final int effectiveEpochDay, final int maturityEpochDay) {
        this.orderRef = (effectiveEpochDay & 0xFFFF) | ((maturityEpochDay & 0xFFFF) << 16);
    }

    public int swapEffectiveEpochDay() {
        return orderRef & 0xFFFF;
    }

    public int swapMaturityEpochDay() {
        return (orderRef >>> 16) & 0xFFFF;
    }

    /** Convention-table index (see {@code SwapConventions}); rides the free securityId slot. */
    public int swapConventionIndex() {
        return securityId;
    }

    /** Notional in whole currency units. */
    public int swapNotional() {
        return qty;
    }

    /** Fixed rate in 1e6 ticks (0.042 = 42000). */
    public long swapFixedRateTicks() {
        return limitPx;
    }

    public boolean swapPaysFixed() {
        return side == SWAP_PAY_FIXED;
    }
}
