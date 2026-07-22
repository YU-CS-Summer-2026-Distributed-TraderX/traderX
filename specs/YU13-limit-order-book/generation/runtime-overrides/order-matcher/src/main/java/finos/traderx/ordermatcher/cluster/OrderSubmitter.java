package finos.traderx.ordermatcher.cluster;

/**
 * The one seam between a counterparty-protocol front end (FIX, REST) and the cluster client
 * (ADR-047). Front ends terminate their own sessions and call this to sequence an order through
 * the consensus log; the implementation owns the (single-threaded) Aeron Cluster client and
 * follows the leader. Because the FIX/REST session lives entirely on the front-end side of this
 * interface, a cluster-client reconnect on leader change never touches the counterparty session.
 */
public interface OrderSubmitter {
    /** Committed outcome of one order. {@code accepted} false ⇒ risk/validation rejected it. */
    record ExecResult(boolean accepted, int orderRef, byte kind) { }

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
}
