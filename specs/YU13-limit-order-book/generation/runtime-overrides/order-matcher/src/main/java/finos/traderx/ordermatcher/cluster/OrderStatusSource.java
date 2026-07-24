package finos.traderx.ordermatcher.cluster;

import java.util.List;

/**
 * Read-only view of an account's current order state, used to answer FIX order-status requests
 * (H, AF) WITHOUT touching the hot order-entry path. The gateway backs this with the SAME source
 * the REST blotter uses — the trade-processor {@code orderbook} read model (YU13, brief 07) —
 * so a FIX status answer and a {@code GET /accounts/{id}/orders} answer can never disagree.
 *
 * <p>Returns {@code null} when no read model is configured (the trade bridge is off — the common
 * bench case), so the acceptor answers "status unavailable" rather than "no orders": a distinction
 * a client must be able to make. An empty list means the account genuinely has none.
 *
 * <p>ponytail: gateway-local HTTP GET to the read model, not a replicated query — status is a
 * low-volume, off-consensus convenience. The authoritative order state stays in the cluster.
 */
public interface OrderStatusSource {

    /** One order's read-model projection, FIX-neutral: {@code side}/{@code status} are the read
     *  model's own strings ("Buy"/"Sell"; NEW/PARTIALLY_FILLED/FILLED/CANCELED/REJECTED), mapped to
     *  FIX by the acceptor. {@code orderRef} is parsed from the epoch-qualified id so the caller
     *  never has to know the epoch. */
    record OrderView(int orderRef, String side, int quantity, int remaining, String status,
                     String security) {}

    /**
     * Orders for {@code accountId}: open only (NEW + PARTIALLY_FILLED) when {@code includeTerminal}
     * is false, every state when true. Empty list ⇒ none; {@code null} ⇒ read model unavailable.
     */
    List<OrderView> orders(int accountId, boolean includeTerminal);
}
