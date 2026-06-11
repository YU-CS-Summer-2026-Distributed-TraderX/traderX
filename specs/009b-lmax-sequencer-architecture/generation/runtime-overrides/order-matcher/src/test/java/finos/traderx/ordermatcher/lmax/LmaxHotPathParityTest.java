package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.api.OrderCreateRequest;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
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
    "spring.datasource.url=jdbc:h2:mem:ordermatcher9b;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
