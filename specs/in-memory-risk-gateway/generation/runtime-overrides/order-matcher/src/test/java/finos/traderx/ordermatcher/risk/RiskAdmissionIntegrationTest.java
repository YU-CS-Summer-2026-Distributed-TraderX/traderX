package finos.traderx.ordermatcher.risk;

import finos.traderx.ordermatcher.api.MarketTradeRequest;
import finos.traderx.ordermatcher.api.OrderCreateRequest;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.service.OrderMatcherService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:riskadmission;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=sa",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "order.matcher.publisher=noop",
    "order.matcher.pricing-subscriber.enabled=false",
    "order.matcher.seed-enabled=false",
    "journal.enabled=false"
})
class RiskAdmissionIntegrationTest {
    @Autowired OrderMatcherService service;
    @Autowired LmaxEngine engine;

    @Test
    void duplicateOrderReturnsOriginalDecisionWithoutSecondReservation() {
        OrderCreateRequest first = order("same-client-id");
        OrderResponse original = service.createOrder(first);
        long reserved = engine.riskState().reservedNotional(22214);

        OrderResponse duplicate = service.createOrder(order("same-client-id"));

        assertEquals(original.getOrderId(), duplicate.getOrderId());
        assertEquals(reserved, engine.riskState().reservedNotional(22214));
    }

    @Test
    void gatewayRejectsUnknownSecurityBeforeSequencing() {
        OrderCreateRequest request = order("unknown-security");
        request.setSecurity("CLIENT-CREATED-TICKER");
        RiskRejectedException failure = assertThrows(RiskRejectedException.class,
            () -> service.createOrder(request));
        assertEquals(RiskReason.UNKNOWN_SECURITY, failure.reason());
    }

    @Test
    void duplicateMarketTradeDoesNotMovePositionTwice() {
        service.onPriceTick("POSMKT", new BigDecimal("100.000"));
        MarketTradeRequest request = new MarketTradeRequest();
        request.setAccountId(22214);
        request.setSecurity("POSMKT");
        request.setSide(OrderSide.Buy);
        request.setQuantity(5);
        request.setClientOrderId("same-market-trade");
        int securityId = engine.symbols().idForExisting("POSMKT");
        int before = engine.blp().positionQuantity(22214, securityId);
        service.bookMarketTrade(request);
        service.bookMarketTrade(request);
        assertEquals(before + 5, engine.blp().positionQuantity(22214, securityId));
    }

    private static OrderCreateRequest order(String clientOrderId) {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setAccountId(22214);
        request.setSecurity("IBM");
        request.setSide(OrderSide.Buy);
        request.setQuantity(10);
        request.setLimitPrice(new BigDecimal("100.000"));
        request.setClientOrderId(clientOrderId);
        return request;
    }
}
