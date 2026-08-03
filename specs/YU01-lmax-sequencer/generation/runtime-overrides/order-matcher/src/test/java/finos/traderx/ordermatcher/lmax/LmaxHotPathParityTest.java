package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.api.MarketTradeRequest;
import finos.traderx.ordermatcher.api.OrderCreateRequest;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import finos.traderx.ordermatcher.model.Position;
import finos.traderx.ordermatcher.model.Trade;
import finos.traderx.ordermatcher.model.TradeState;
import finos.traderx.ordermatcher.repository.PositionRepository;
import finos.traderx.ordermatcher.repository.TradeRepository;
import finos.traderx.ordermatcher.service.OrderMatcherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;


import java.math.BigDecimal;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Functional parity of the LMAX hot path with state 009 (SC-09B03): same REST semantics,
 * same auto-fill policy (in-the-money; remaining < 1000 fills fully, otherwise half
 * rounded up), same lifecycle statuses — produced by the event-driven BLP instead of the
 * 009 poll/lock pipeline.
 */
@SpringBootTest(properties = {
    // MySQL compat mode (not PostgreSQL): the projector's batch upserts use MariaDB dialect
    // (INSERT IGNORE / ON DUPLICATE KEY UPDATE) since the read-model DB moved off Postgres.
    "spring.datasource.url=jdbc:h2:mem:ordermatcher9b;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=sa",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "order.matcher.publisher=noop",
    "order.matcher.pricing-subscriber.enabled=false",
    "order.matcher.seed-enabled=true",
    "journal.enabled=false",
    // unreachable trade-service: booking bridge failures are counted, never block matching
    "order.matcher.trade-service-url=http://localhost:1/trade/"
})
class LmaxHotPathParityTest {

    @Autowired
    private OrderMatcherService service;

    @Autowired
    private LmaxEngine engine;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Test
    void createAcksNewThenAutoFillsPerPolicy() {
        OrderResponse created = service.createOrder(request(22214, "PARITYA", OrderSide.Buy, 1800, "100.000"));
        assertEquals(OrderStatus.NEW, created.getStatus());
        assertEquals(1800, created.getRemainingQuantity());
        assertNull(created.getLastExecutionPrice());
        String orderId = created.getOrderId();

        // In-the-money tick: remaining 1800 >= 1000 -> half rounded up = 900.
        service.onPriceTick("PARITYA", new BigDecimal("99.500"));
        OrderResponse partial = awaitOrder(orderId, o -> o.getStatus() == OrderStatus.PARTIALLY_FILLED);
        assertEquals(900, partial.getRemainingQuantity());
        assertEquals(900, partial.getLastFillQuantity());
        assertEquals(0, new BigDecimal("99.500").compareTo(partial.getLastExecutionPrice()));

        // Next tick: remaining 900 < 1000 -> full fill, terminal FILLED.
        service.onPriceTick("PARITYA", new BigDecimal("99.500"));
        OrderResponse filled = awaitOrder(orderId, o -> o.getStatus() == OrderStatus.FILLED);
        assertEquals(0, filled.getRemainingQuantity());
        assertEquals(900, filled.getLastFillQuantity());
    }

    @Test
    void outOfTheMoneyOrdersRest() {
        OrderResponse created = service.createOrder(request(22214, "PARITYB", OrderSide.Buy, 500, "100.000"));
        service.onPriceTick("PARITYB", new BigDecimal("100.001")); // above buy limit: not in the money
        sleep(150);
        OrderResponse after = service.getOrder(created.getOrderId());
        assertEquals(OrderStatus.NEW, after.getStatus());
        assertEquals(500, after.getRemainingQuantity());
    }

    @Test
    void cancelOpenOrderTerminatesIt() {
        OrderResponse created = service.createOrder(request(44044, "PARITYC", OrderSide.Sell, 300, "500.000"));
        OrderResponse canceled = service.cancelOrder(created.getOrderId());
        assertEquals(OrderStatus.CANCELED, canceled.getStatus());
        assertEquals(0, canceled.getRemainingQuantity());

        // 009 parity: canceling a terminal order returns it unchanged.
        OrderResponse again = service.cancelOrder(created.getOrderId());
        assertEquals(OrderStatus.CANCELED, again.getStatus());
    }

    @Test
    void forceFillFallsBackToLimitPriceWhenNoTickSeen() {
        OrderResponse created = service.createOrder(request(52355, "PARITYD", OrderSide.Buy, 2500, "42.125"));
        OrderResponse filled = service.forceFillOrder(created.getOrderId());
        assertEquals(OrderStatus.FILLED, filled.getStatus());
        assertEquals(0, filled.getRemainingQuantity());
        assertEquals(2500, filled.getLastFillQuantity());
        assertEquals(0, new BigDecimal("42.125").compareTo(filled.getLastExecutionPrice()));
    }

    @Test
    void buyFillIncreasesNetPositionInTheBlp() {
        // Booking + position-keeping fused into the BLP (FR-09B08/B10): a buy fill raises the
        // account's net position, computed in-memory on the single BLP thread.
        int account = 22214;
        OrderResponse created = service.createOrder(request(account, "POSBUY", OrderSide.Buy, 500, "100.000"));
        service.onPriceTick("POSBUY", new BigDecimal("99.500")); // in the money, 500 < 1000 -> full fill
        awaitOrder(created.getOrderId(), o -> o.getStatus() == OrderStatus.FILLED);
        int securityId = engine.symbols().idFor("POSBUY");
        assertEquals(500, engine.blp().positionQuantity(account, securityId));

        // The projector persists the booked trade with the stamped execution price and the
        // position with its weighted average cost basis (booking fused into the BLP, FR-09B40).
        Trade booked = awaitTrade(t -> "POSBUY".equals(t.getSecurity()) && account == t.getAccountId());
        assertEquals(TradeState.Settled, booked.getState());
        assertEquals(500, booked.getQuantity().intValue());
        assertEquals(0, new BigDecimal("99.500").compareTo(booked.getPrice()));
        Position position = awaitPersistedPosition(account, "POSBUY");
        assertEquals(0, new BigDecimal("99.500").compareTo(position.getAverageCostBasis()));
    }

    @Test
    void sellFillDecreasesNetPositionInTheBlp() {
        int account = 44044;
        OrderResponse created = service.createOrder(request(account, "POSSELL", OrderSide.Sell, 300, "10.000"));
        service.onPriceTick("POSSELL", new BigDecimal("10.500")); // sell in the money (px >= limit), full fill
        awaitOrder(created.getOrderId(), o -> o.getStatus() == OrderStatus.FILLED);
        int securityId = engine.symbols().idFor("POSSELL");
        assertEquals(-300, engine.blp().positionQuantity(account, securityId));
    }

    @Test
    void marketTradeBooksAndUpdatesPosition() {
        // FR-09B08: a market trade from the trade ticket is sequenced as TRADE_NEW and booked
        // by the BLP (no matching), moving the position through the single-writer path.
        int account = 52355;
        service.onPriceTick("POSMKT", new BigDecimal("12.500")); // BLP stamps the booking at this price
        MarketTradeRequest trade = new MarketTradeRequest();
        trade.setAccountId(account);
        trade.setSecurity("POSMKT");
        trade.setSide(OrderSide.Buy);
        trade.setQuantity(750);
        service.bookMarketTrade(trade);

        int securityId = engine.symbols().idFor("POSMKT");
        awaitPosition(account, securityId, 750);
        assertEquals(750, engine.blp().positionQuantity(account, securityId));

        // The market trade is booked at the stamped market price and persisted by the projector,
        // and its position carries the resulting average cost basis (single-writer fusion).
        Trade booked = awaitTrade(t -> "POSMKT".equals(t.getSecurity()) && account == t.getAccountId());
        assertEquals(TradeState.Settled, booked.getState());
        assertEquals(0, new BigDecimal("12.500").compareTo(booked.getPrice()));
        Position position = awaitPersistedPosition(account, "POSMKT");
        assertEquals(0, new BigDecimal("12.500").compareTo(position.getAverageCostBasis()));
    }

    @Test
    void unknownOrderIs404() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.cancelOrder("ord-013-9999"));
        assertEquals(404, ex.getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> service.getOrder("not-an-id"));
    }

    @Test
    void invalidCreatePayloadIs400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.createOrder(request(0, "PARITYE", OrderSide.Buy, 100, "10.000")));
        assertEquals(400, ex.getStatusCode().value());
    }

    // ----- helpers ------------------------------------------------------------------------

    private OrderCreateRequest request(int accountId, String security, OrderSide side, int qty, String limit) {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setAccountId(accountId);
        request.setSecurity(security);
        request.setSide(side);
        request.setQuantity(qty);
        request.setLimitPrice(new BigDecimal(limit));
        return request;
    }

    private OrderResponse awaitOrder(String orderId, Predicate<OrderResponse> condition) {
        for (int i = 0; i < 200; i++) {
            OrderResponse order = service.getOrder(orderId);
            if (condition.test(order)) {
                return order;
            }
            sleep(20);
        }
        fail("condition not met for order " + orderId + " within timeout");
        return null;
    }

    private void awaitPosition(int accountId, int securityId, int expected) {
        for (int i = 0; i < 200; i++) {
            if (engine.blp().positionQuantity(accountId, securityId) == expected) {
                return;
            }
            sleep(20);
        }
        fail("position " + accountId + ":" + securityId + " did not reach " + expected + " within timeout");
    }

    private Trade awaitTrade(Predicate<Trade> condition) {
        for (int i = 0; i < 200; i++) {
            for (Trade trade : tradeRepository.findAll()) {
                if (trade.getAccountId() != null && condition.test(trade)) {
                    return trade;
                }
            }
            sleep(20);
        }
        fail("no persisted trade matched within timeout");
        return null;
    }

    private Position awaitPersistedPosition(int accountId, String security) {
        for (int i = 0; i < 200; i++) {
            for (Position position : positionRepository.findAll()) {
                if (position.getAccountId() != null && position.getAccountId() == accountId
                    && security.equals(position.getSecurity())) {
                    return position;
                }
            }
            sleep(20);
        }
        fail("no persisted position " + accountId + ":" + security + " within timeout");
        return null;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    @Test
    void seededOrdersAreWarmLoaded() {
        // The 009 seed set is preserved; ord-013-0001 (IBM Buy 1800 @187.250) must be open.
        OrderResponse seeded = service.getOrder("ord-013-0001");
        assertEquals(OrderStatus.NEW, seeded.getStatus());
        assertTrue(service.listOrders("open", null).size() >= 5);
    }

    @Test
    void prometheusExportsRequiredRingAndNoGcFamilies() {
        // Required metric families from contracts/contract-delta.md (input/output ring,
        // projector, allocation) render on the 009-parity scrape surface.
        String metrics = service.prometheusMetrics();
        for (String family : new String[]{
            "traderx_disruptor_input_remaining_capacity",
            "traderx_input_published_seq",
            "traderx_input_gating_seq",
            "traderx_input_seq_lag",
            "traderx_input_backpressure_events_total",
            "traderx_input_events_total",
            "traderx_journal_write_latency_seconds",
            "traderx_blp_event_latency_seconds",
            "traderx_output_publish_latency_seconds",
            "traderx_output_remaining_capacity",
            "traderx_output_events_total",
            "traderx_output_nats_errors_total",
            "traderx_projector_lag_seq",
            "traderx_projector_batch_size",
            "traderx_hotpath_alloc_bytes_total",
            "traderx_order_match_latency_seconds"}) {
            assertTrue(metrics.contains(family), "missing metric family: " + family);
        }
    }
}
