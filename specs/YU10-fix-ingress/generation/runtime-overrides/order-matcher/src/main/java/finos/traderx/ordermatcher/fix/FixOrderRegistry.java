package finos.traderx.ordermatcher.fix;

import quickfix.SessionID;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory admission context for FIX-originated orders: everything the ExecutionReport builder
 * needs that a lifecycle {@link finos.traderx.ordermatcher.lmax.OutputEvent} does not carry
 * (the owning live session, the client's ClOrdID, the ticker string). Session-lifetime data —
 * NOT persisted: after a restart the durable {@link ClOrdIdLedger} still answers cancels,
 * status requests, and duplicate checks, while lifecycle reports for pre-restart orders are
 * recovered by the counterparty via OrderStatusRequest (ADR-037, TD-FIX01).
 */
public final class FixOrderRegistry {

    public record Ctx(SessionID sessionId, long sessionKey, String clOrdId,
                      String ticker, char fixSide, int quantity) { }

    private final ConcurrentHashMap<Integer, Ctx> byOrderRef = new ConcurrentHashMap<>();

    public void put(int orderRef, Ctx ctx) {
        byOrderRef.put(orderRef, ctx);
    }

    /** Output-ring-thread read (lock-free; boxed key acceptable outside the no-GC boundary). */
    public Ctx byOrderRef(int orderRef) {
        return byOrderRef.get(orderRef);
    }

    public int size() {
        return byOrderRef.size();
    }
}
