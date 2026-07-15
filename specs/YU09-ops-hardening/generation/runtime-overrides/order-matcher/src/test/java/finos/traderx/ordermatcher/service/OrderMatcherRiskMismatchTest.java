package finos.traderx.ordermatcher.service;

import finos.traderx.ordermatcher.api.OrderCreateRequest;
import finos.traderx.ordermatcher.lmax.InMemoryOrderReadModel;
import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.lmax.OrderSnapshot;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.Px;
import finos.traderx.ordermatcher.lmax.SymbolTable;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import finos.traderx.ordermatcher.risk.GatewayReplicaStore;
import finos.traderx.ordermatcher.risk.RiskReason;
import finos.traderx.ordermatcher.risk.RiskRejectedException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Two-stage validation disagreement contract updated for YU05 entitlement constructor wiring. */
class OrderMatcherRiskMismatchTest {
    @Test
    void frImrg19AuthoritativeBlpRejectionWinsAndIncrementsMismatchMetric() {
        GatewayReplicaStore replicas = new GatewayReplicaStore("22214", "IBM", 10_000,
            1_000_000_000_000L, 30_000L, 5_000L, 64, 64);
        replicas.seed();
        replicas.alignSecurityIds(ticker -> 0);
        replicas.markReady();

        LmaxEngine engine = mock(LmaxEngine.class);
        when(engine.readModel()).thenReturn(mock(InMemoryOrderReadModel.class));
        when(engine.nextOrderRef()).thenReturn(7);
        when(engine.executeNewOrder(anyInt(), anyInt(), anyString(), any(), anyInt(), any(), anyLong()))
            .thenReturn(rejectedSnapshot(RiskReason.POSITION_LIMIT));

        OrderMatcherService service = new OrderMatcherService(engine, replicas, true,
            "dev-jwt-shared-secret", false,
            "http://price-publisher:18100", "http://trade-service:18092/trade/");
        OrderCreateRequest request = new OrderCreateRequest();
        request.setClientOrderId("client-7");
        request.setAccountId(22214);
        request.setSecurity("IBM");
        request.setSide(OrderSide.Buy);
        request.setQuantity(100);
        request.setLimitPrice(new BigDecimal("100.000"));

        RiskRejectedException rejected = assertThrows(RiskRejectedException.class,
            () -> service.createOrder(request));

        assertEquals(RiskReason.POSITION_LIMIT, rejected.reason());
        StringBuilder metrics = new StringBuilder();
        replicas.metrics().render(metrics, replicas.ready());
        assertTrue(metrics.toString().contains(
            "traderx_gateway_blp_mismatch_total{reason=\"decision\"} 1"));
    }

    private static OrderSnapshot rejectedSnapshot(RiskReason reason) {
        SymbolTable symbols = new SymbolTable(8);
        int securityId = symbols.idFor("IBM");
        OutputEvent event = new OutputEvent();
        event.kind = OutputEvent.KIND_ORDER_REJECTED;
        event.orderRef = 7;
        event.accountId = 22214;
        event.securityId = securityId;
        event.side = (byte) OrderSide.Buy.ordinal();
        event.quantity = 100;
        event.remainingQty = 100;
        event.limitPx = Px.toTicks(new BigDecimal("100.000"));
        event.status = (byte) OrderStatus.REJECTED.ordinal();
        event.riskReason = (byte) reason.ordinal();
        event.createdAtMillis = 1_000L;
        event.updatedAtMillis = 1_000L;
        event.lastExecPx = Px.NONE;
        return OrderSnapshot.fromEvent(event, symbols);
    }
}
