package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import finos.traderx.ordermatcher.risk.RiskReason;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Restriction follow-up uses explicit sequenced CANCEL commands (FR-IMRG24). */
class RestrictionCancellationTest {
    @Test
    void frImrg24CancelsOnlyRestingOrdersOnNewlyRestrictedSecurityThroughCommandSink() {
        SymbolTable symbols = new SymbolTable(8);
        List<OrderSnapshot> orders = List.of(
            order(symbols, 11, "IBM", OrderStatus.NEW),
            order(symbols, 12, "IBM", OrderStatus.PARTIALLY_FILLED),
            order(symbols, 13, "IBM", OrderStatus.CANCELED),
            order(symbols, 14, "MSFT", OrderStatus.NEW));
        List<Integer> sequencedCancelRefs = new ArrayList<>();

        int canceled = LmaxEngine.cancelOpenOrdersForSecurity(
            "ibm", orders, sequencedCancelRefs::add);

        assertEquals(2, canceled);
        assertEquals(List.of(11, 12), sequencedCancelRefs);
    }

    private static OrderSnapshot order(SymbolTable symbols, int orderRef, String ticker,
                                       OrderStatus status) {
        int securityId = symbols.idFor(ticker);
        OutputEvent event = new OutputEvent();
        event.orderRef = orderRef;
        event.accountId = 22214;
        event.securityId = securityId;
        event.side = (byte) OrderSide.Buy.ordinal();
        event.quantity = 100;
        event.remainingQty = status == OrderStatus.PARTIALLY_FILLED ? 50 : 100;
        event.limitPx = Px.toTicks(new BigDecimal("100.000"));
        event.status = (byte) status.ordinal();
        event.riskReason = (byte) RiskReason.ACCEPTED.ordinal();
        event.createdAtMillis = 1_000L;
        event.updatedAtMillis = 1_000L;
        event.lastExecPx = Px.NONE;
        return OrderSnapshot.fromEvent(event, symbols);
    }
}
