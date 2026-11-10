package finos.traderx.ordermatcher.cluster;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
    /** FR-XOC-06: leader changes this submitter has seen; a null answer spanning one was stranded by the election. */
    default long leaderChanges() {
        return 0;
    }

    default ExecResult submitCancel(int orderRef) {
        return null;
    }

    /**
     * ENGINE-REVIEW G3: cancel with the caller's OWNERSHIP CLAIM. {@code accountId} rides the
     * command and the engine refuses NOT_ENTITLED when it names an account other than the
     * order's; 0 = no claim, unchecked (the binary frames carry none). Defaults to the unclaimed
     * form so a submitter that predates the claim keeps compiling.
     */
    default ExecResult submitCancel(int orderRef, int accountId) {
        return submitCancel(orderRef);
    }

    /** G3: {@link #submitReplace(int, String, int, long)} with the caller's ownership claim. */
    default ExecResult submitReplace(int orderRef, String clOrdId, int quantity, long limitPxTicks,
                                     int accountId) {
        return submitReplace(orderRef, clOrdId, quantity, limitPxTicks);
    }

    /** G3: {@link #submitTypedReplace(int, String, int, TypedOrder)} with the caller's ownership claim. */
    default ExecResult submitTypedReplace(int orderRef, String clOrdId, int qty, TypedOrder typed,
                                          int accountId) {
        return submitTypedReplace(orderRef, clOrdId, qty, typed);
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

    /**
     * FR-BSP-01: {@link #submitOrder(long, int, int, char, int, long)} without waiting, for a front end that keeps many
     * orders in flight per session. The order is queued for the cluster before this returns, so calls made in order
     * from one thread reach the log in that order. The future completes with exactly what the synchronous call would
     * have returned (null = no committed decision); {@code completions} runs the implementation's completion work,
     * so it never runs on the cluster client's owner thread. Default: the synchronous call, already complete.
     */
    default CompletableFuture<ExecResult> submitOrderAsync(long clientKey, int accountId, int securityId, char side,
                                                          int qty, long limitPxTicks, Executor completions) {
        return CompletableFuture.completedFuture(submitOrder(clientKey, accountId, securityId, side, qty, limitPxTicks));
    }

    /** FR-BSP-02: the FIX NEW ({@link #submitTypedOrder} when {@code typed != null}) without waiting. */
    default CompletableFuture<ExecResult> submitOrderAsync(String clOrdId, int accountId, String ticker, char side,
                                                          int qty, long limitPxTicks, TypedOrder typed,
                                                          Executor completions) {
        return CompletableFuture.completedFuture(typed == null
            ? submitOrder(clOrdId, accountId, ticker, side, qty, limitPxTicks)
            : submitTypedOrder(clOrdId, accountId, ticker, side, qty, typed));
    }

    /** FR-BSP-01: {@link #submitSequencedOrder(long, int, int, char, int, long, long, long)} without waiting. */
    default CompletableFuture<ExecResult> submitSequencedOrderAsync(long clientKey, int accountId, int securityId,
                                                                   char side, int qty, long limitPxTicks,
                                                                   long streamId, long streamSeq,
                                                                   Executor completions) {
        return CompletableFuture.completedFuture(submitSequencedOrder(clientKey, accountId, securityId, side, qty,
            limitPxTicks, streamId, streamSeq));
    }

    /** FR-BSP-01: {@link #submitCancel(int)} without waiting. */
    default CompletableFuture<ExecResult> submitCancelAsync(int orderRef, Executor completions) {
        return CompletableFuture.completedFuture(submitCancel(orderRef));
    }

    /** FR-BSP-01: {@link #submitReplace(int, long, int, long)} without waiting. */
    default CompletableFuture<ExecResult> submitReplaceAsync(int orderRef, long clientKey, int qty, long limitPxTicks,
                                                            Executor completions) {
        return CompletableFuture.completedFuture(submitReplace(orderRef, clientKey, qty, limitPxTicks));
    }

    /** ADR-090: a binary NEW on sequenced stream {@code streamId}; {@code streamSeq} from 1. */
    default ExecResult submitSequencedOrder(long clientKey, int accountId, int securityId, char side, int qty,
                                            long limitPxTicks, long streamId, long streamSeq) {
        return null;
    }

    /** ADR-090: a FIX NEW (typed when {@code typed != null}) on a gateway-sequenced stream (FR-XOD-12). */
    default ExecResult submitSequencedOrder(String clOrdId, int accountId, String ticker, char side, int qty,
                                            long limitPxTicks, TypedOrder typed, long streamId, long streamSeq) {
        return null;
    }

    /**
     * YU18 order types (FR-OT01, FR-OT36): the typed part of a NEW or REPLACE. Prices are Px (1e-6);
     * 0 means absent for every optional field. {@code tif} is already resolved (FR-OT06 default
     * applied) by the boundary that built it.
     */
    record TypedOrder(byte orderType, byte tif, long limitPx, long stopPx, int displayQty,
                      byte pegRef, int pegOffset, byte trailMode, long trailValue) {
    }

    /** YU18: a typed NEW, sequenced as SBE template 9. Boundaries validate with OrderTypes first. */
    default ExecResult submitTypedOrder(String clOrdId, int accountId, String ticker, char side, int qty,
                                        TypedOrder typed) {
        return null;
    }

    /** YU18: a typed REPLACE of an order of the same type (FR-OT29). */
    default ExecResult submitTypedReplace(int orderRef, String clOrdId, int qty, TypedOrder typed) {
        return null;
    }
}
