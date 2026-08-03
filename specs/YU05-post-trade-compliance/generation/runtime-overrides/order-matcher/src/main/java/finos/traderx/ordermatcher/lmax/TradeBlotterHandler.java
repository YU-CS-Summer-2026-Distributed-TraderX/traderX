package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import finos.traderx.ordermatcher.model.OrderSide;

/**
 * YU05 (post-trade-compliance, ADR-022): captures every {@code KIND_TRADE_BOOKED} output event
 * into the in-memory {@link TradeBlotter}. Deliberately does NOT check {@code
 * readModel.isReplaying()} or the replication role, unlike {@link AccountTradeHandler}/{@link
 * finos.traderx.ordermatcher.lmax.NatsBridgeHandler} — those guards exist to suppress an
 * *external* side effect (a second NATS publish) during recovery replay or on a follower; this
 * handler only writes to its own in-process structure, which must be rebuilt from replay to be
 * useful for reconciliation after a restart.
 */
public final class TradeBlotterHandler implements EventHandler<OutputEvent> {
    private final TradeBlotter blotter;
    private final SymbolTable symbols;

    public TradeBlotterHandler(TradeBlotter blotter, SymbolTable symbols) {
        this.blotter = blotter;
        this.symbols = symbols;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (e.kind != OutputEvent.KIND_TRADE_BOOKED) {
            return;
        }
        String id = OrderSnapshot.tradeIdFor(e.tradeSeq);
        TradeBlotter.TradeRecord record = new TradeBlotter.TradeRecord(
            id,
            e.tradeSeq,
            e.accountId,
            symbols.tickerFor(e.securityId),
            OrderSide.values()[e.side].name(),
            e.tradeQty,
            Px.toBigDecimal(e.tradePx),
            e.updatedAtMillis);
        blotter.record(record);
    }
}
