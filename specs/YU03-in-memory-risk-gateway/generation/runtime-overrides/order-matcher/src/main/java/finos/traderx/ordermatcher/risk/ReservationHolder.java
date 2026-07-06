package finos.traderx.ordermatcher.risk;

/**
 * Where an accepted order's live exposure reservation is stored (in-memory-risk-gateway
 * forward-port adaptation). The stale-branch design kept reservations in dense arrays indexed
 * by orderRef, which assumed a bounded orderRef space; on this branch orderRef is monotonic
 * and unbounded (the book grows/evicts by retention), so the reservation instead rides the
 * pooled order entry itself — it lives exactly as long as the order is addressable, is freed
 * with the pool slot, and is snapshotted with the order tuple (FR-IMRG16/21/26).
 *
 * Implemented by {@code lmax.RestingOrder}; all access is BLP-thread-only.
 */
public interface ReservationHolder {
    long reservedNotional();

    int reservedQty();

    void setReservation(long notional, int qty);
}
