package finos.traderx.ordermatcher.cluster;

/**
 * The one seam between a counterparty-protocol front end (FIX, REST) and the cluster client
 * (ADR-047). Front ends terminate their own sessions and call this to sequence an order through
 * the consensus log; the implementation owns the (single-threaded) Aeron Cluster client and
 * follows the leader. Because the FIX/REST session lives entirely on the front-end side of this
 * interface, a cluster-client reconnect on leader change never touches the counterparty session.
 */
public interface OrderSubmitter {
    /**
     * Committed outcome of one order. {@code accepted} false ⇒ risk/validation rejected it, and
     * {@code riskReason} is the engine's own {@code RiskReason} ordinal for WHY, carried on egress
     * ack byte 22. Without it a REST reject was a bare {@code kind:2} with no cause — which is how
     * a 30s bench came to report a green 2xx on every request while the engine silently rejected
     * 296,000 orders on CREDIT_LIMIT. The market-trade path already answered with its reason; the
     * order path now does too.
     */
    record ExecResult(boolean accepted, int orderRef, byte kind, byte riskReason) {
        public ExecResult(boolean accepted, int orderRef, byte kind) {
            this(accepted, orderRef, kind, (byte) 0);
        }
    }

    /**
     * Sequence one order through the cluster and return its committed outcome. Thread-safe:
     * callers are arbitrary FIX session threads; the implementation serializes onto the client
     * owner thread. Returns null if no committed ack arrived within the submit deadline
     * (post-publish ambiguity — the caller must not claim rejection).
     */
    ExecResult submitOrder(String clOrdId, int accountId, String ticker, char side, int qty, long limitPxTicks);

    /**
     * Sequence a cancel of {@code orderRef} and return its committed outcome (FR-LOB09).
     * {@code accepted} true ⇒ the order is gone from the book; false ⇒ it was unknown or already
     * terminal, and {@code kind} says which ({@code KIND_ORDER_NOT_FOUND} vs the order's terminal
     * kind). Returns null on post-publish ambiguity, exactly as {@link #submitOrder}.
     *
     * <p>The verdict is decided inside the replicated state machine from {@code lookup(orderRef)}
     * alone — no wall-clock, no gateway-local state — so all members reach it identically from the
     * same log position.
     *
     * <p>Default: unsupported at this tier, reported as ambiguity so no caller claims a rejection
     * it cannot substantiate.
     */
    default ExecResult submitCancel(int orderRef) {
        return null;
    }

    /**
     * Sequence an ATOMIC replace of {@code orderRef} — one command, cancel-and-add inside a single
     * apply (ADR-058). {@code quantity} is the new TOTAL quantity; account, security and side are
     * not passed because FIX forbids changing them and the engine reads them off the original.
     *
     * <p>{@code accepted} true ⇒ the order now stands at the new size/price under the SAME
     * orderRef. False ⇒ nothing changed, and {@code kind} says why: {@code KIND_ORDER_NOT_FOUND}
     * for an unknown ref, {@code KIND_ORDER_REJECTED} for a rejected replace (bad quantity, price
     * off grid or outside the collar, risk), and the order's own terminal kind when it was already
     * done. Returns null on post-publish ambiguity, exactly as {@link #submitOrder}.
     *
     * <p>The atomicity is the point: there is no committed state in which the client's order has
     * been cancelled but the replacement has not been accepted, so a rejection can never leave them
     * with nothing, and one request never produces two lifecycle messages.
     *
     * <p>Default: unsupported at this tier, reported as ambiguity so no caller claims a rejection
     * it cannot substantiate.
     */
    default ExecResult submitReplace(int orderRef, String clOrdId, int quantity, long limitPxTicks) {
        return null;
    }

    /**
     * Binary fast-path NEW (lever 4). Same committed contract as {@link #submitOrder}, but every
     * field arrives numeric so the hot path allocates nothing: {@code securityId} is pre-resolved on
     * the wire (no ticker String, no map lookup on the caller's thread) and {@code clientKey} is the
     * client's own idempotency key used directly (0 = none), not hashed from a String. The order still
     * rides the identical pipelined window and consensus path, so members apply it byte-for-byte as
     * they would a FIX or REST order.
     *
     * <p>Default: unsupported at this tier, reported as ambiguity — same convention as the others.
     */
    default ExecResult submitOrder(long clientKey, int accountId, int securityId, char side, int qty,
                                   long limitPxTicks) {
        return null;
    }

    /** Binary fast-path atomic replace (lever 4): {@link #submitReplace} with a numeric idempotency
     *  key instead of a String clOrdId. Default unsupported, reported as ambiguity. */
    default ExecResult submitReplace(int orderRef, long clientKey, int qty, long limitPxTicks) {
        return null;
    }
}
