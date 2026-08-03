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
 * Functional behavior of the LMAX hot path through the REST surface: same REST create/cancel/
 * force-fill/market-trade semantics and lifecycle statuses as state 009, produced by the
 * event-driven BLP instead of the 009 poll/lock pipeline. YU13: the matching POLICY is no
 * longer 009's price-triggered auto-fill — it is the crossing limit-order book, so every fill
 * here is driven by a genuine resting opposite order, not by an in-the-money price tick. The
 * REST lifecycle contract (create acks NEW, cancel terminates, force-fill executes the
 * remainder, market trades book without matching) is retained; the fill mechanism is the book.
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
    "order.matcher.trade-service-url=http://localhost:1/trade/",
    // YU04: this test exercises BLP/matching hot-path parity, not the durable-feed bootstrap
    // protocol (that has its own dedicated tests — ControlFeedBootstrapStateTest). Disabling
    // bootstrap grants Gateway readiness on seeds immediately (ReplicaBootstrap.start()) instead
    // of waiting on a JetStream/account-service/reference-data connection this test never provides.
    "risk.bootstrap.enabled=false"
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
    void createAcksNewThenCrossesRestingOpposite() {
        // A resting sell provides the opposite side; a marketable buy crosses it best-price-first
        // at the resting price (YU13 crossing book), acking NEW first, then filling. Replaces the
        // retired price-triggered half-fill policy.
        OrderResponse resting = service.createOrder(request(22214, "PARITYA", OrderSide.Sell, 900, "99.500"));
        assertEquals(OrderStatus.NEW, resting.getStatus());

        OrderResponse created = service.createOrder(request(44044, "PARITYA", OrderSide.Buy, 900, "100.000"));
        assertEquals(OrderStatus.NEW, created.getStatus());
        assertEquals(900, created.getRemainingQuantity());
        assertNull(created.getLastExecutionPrice());
        String orderId = created.getOrderId();

        // The buy crosses the resting sell in full at the RESTING price 99.500 (price improvement).
        OrderResponse filled = awaitOrder(orderId, o -> o.getStatus() == OrderStatus.FILLED);
        assertEquals(0, filled.getRemainingQuantity());
        assertEquals(900, filled.getLastFillQuantity());
        assertEquals(0, new BigDecimal("99.500").compareTo(filled.getLastExecutionPrice()));

        // The resting sell is consumed to terminal FILLED by the same crossing.
        OrderResponse restingFilled = awaitOrder(resting.getOrderId(), o -> o.getStatus() == OrderStatus.FILLED);
        assertEquals(0, restingFilled.getRemainingQuantity());
        assertEquals(900, restingFilled.getLastFillQuantity());
    }

    @Test
    void nonMarketableOrdersRest() {
        // No opposite order to cross: a buy rests at NEW regardless of the market-data tick — a
        // tick never fills an order in the crossing book (ADR-051).
        OrderResponse created = service.createOrder(request(22214, "PARITYB", OrderSide.Buy, 500, "100.000"));
        service.onPriceTick("PARITYB", new BigDecimal("99.000")); // would have been "in the money" pre-YU13
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
        // Booking + position-keeping fused into the BLP (FR-09B08/B10): a buy that crosses a
        // resting sell raises the buyer's net position, computed in-memory on the single BLP
        // thread. The seller (a distinct account) provides the opposite side.
        int buyer = 22214;
        int seller = 44044;
        service.createOrder(request(seller, "POSBUY", OrderSide.Sell, 500, "99.500")); // resting opposite
        OrderResponse created = service.createOrder(request(buyer, "POSBUY", OrderSide.Buy, 500, "100.000"));
        awaitOrder(created.getOrderId(), o -> o.getStatus() == OrderStatus.FILLED);
        int securityId = engine.symbols().idFor("POSBUY");
        assertEquals(500, engine.blp().positionQuantity(buyer, securityId));

        // The projector persists the buyer's booked trade at the resting execution price and the
        // position with its weighted average cost basis (booking fused into the BLP, FR-09B40).
        // YU05 (post-trade-compliance, FR-PTC02/06): the projector now starts a booked trade at
        // Processing with a real settlement date, instead of the old instant-Settled shortcut.
        Trade booked = awaitTrade(t -> "POSBUY".equals(t.getSecurity()) && buyer == t.getAccountId());
        assertEquals(TradeState.Processing, booked.getState());
        assertEquals(500, booked.getQuantity().intValue());
        assertEquals(0, new BigDecimal("99.500").compareTo(booked.getPrice()));
        Position position = awaitPersistedPosition(buyer, "POSBUY");
        assertEquals(0, new BigDecimal("99.500").compareTo(position.getAverageCostBasis()));
    }

    @Test
    void sellFillDecreasesNetPositionInTheBlp() {
        // The mirror of the buy path: a sell that crosses a resting bid lowers the seller's net
        // position. Execution is at the resting BID price (10.500), best-price-first.
        int seller = 44044;
        int buyer = 22214;
        service.createOrder(request(buyer, "POSSELL", OrderSide.Buy, 300, "10.500")); // resting bid
        OrderResponse created = service.createOrder(request(seller, "POSSELL", OrderSide.Sell, 300, "10.000"));
        awaitOrder(created.getOrderId(), o -> o.getStatus() == OrderStatus.FILLED);
        int securityId = engine.symbols().idFor("POSSELL");
        assertEquals(-300, engine.blp().positionQuantity(seller, securityId));
    }

    @Test
    void onPriceTickRawSeedsTheMarkLikeTheDecimalPath() {
        // The binary NATS tick subscriber calls onPriceTickRaw(ticker, priceTicks, sourceEpochMillis)
        // with a price already in Px fixed-point ticks. YU13: a tick no longer fills — it seeds the
        // security's mark until the book first trades (ADR-051) — so the raw path is proven by the
        // mark it seeds: a market trade booked afterward stamps exactly that price, identical to the
        // JSON/BigDecimal onPriceTick path for the same price.
        int account = 62654;
        long priceTicks = Px.toTicks(new BigDecimal("49.750"));
        service.onPriceTickRaw("GS", priceTicks, System.currentTimeMillis());

        MarketTradeRequest trade = new MarketTradeRequest();
        trade.setAccountId(account);
        trade.setSecurity("GS");
        trade.setSide(OrderSide.Buy);
        trade.setQuantity(400);
        service.bookMarketTrade(trade);

        int securityId = engine.symbols().idFor("GS");
        awaitPosition(account, securityId, 400);
        Trade booked = awaitTrade(t -> "GS".equals(t.getSecurity()) && account == t.getAccountId());
        assertEquals(0, new BigDecimal("49.750").compareTo(booked.getPrice()),
            "the raw tick seeded the mark the market trade booked at");
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
        // YU05 (post-trade-compliance, FR-PTC02/06): Processing, not instant-Settled — see above.
        Trade booked = awaitTrade(t -> "POSMKT".equals(t.getSecurity()) && account == t.getAccountId());
        assertEquals(TradeState.Processing, booked.getState());
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
