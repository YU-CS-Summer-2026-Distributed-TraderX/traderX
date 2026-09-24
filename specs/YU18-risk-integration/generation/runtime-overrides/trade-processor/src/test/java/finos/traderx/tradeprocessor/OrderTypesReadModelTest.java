package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.tradeprocessor.controller.OrderController;
import finos.traderx.tradeprocessor.model.OrderRow;
import finos.traderx.tradeprocessor.model.OrderUpdate;
import finos.traderx.tradeprocessor.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * YU18 order types, read model (FR-OT33/34): the payload the order bridge publishes lands in
 * typed columns; an untyped order still names a type and a TIF; the live statuses are open.
 */
class OrderTypesReadModelTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** The payload shape OrderNatsPublisher.encode writes for a typed, triggered stop. */
  private static final String TYPED = "{\"id\":\"3-7\",\"accountId\":22214,\"security\":\"IBM\","
      + "\"side\":\"Buy\",\"quantity\":10,\"remainingQuantity\":10,\"limitPrice\":0.000000,"
      + "\"status\":\"PENDING_TRIGGER\",\"lastExecutionPrice\":0.000000,\"lastFillQuantity\":0,"
      + "\"createdAt\":1700000000000,\"updatedAt\":1700000000000,\"reason\":\"DAY_EXPIRED\","
      + "\"orderType\":\"TRAILING_STOP\",\"timeInForce\":\"DAY\",\"stopPrice\":98.860000,"
      + "\"triggered\":false,\"trailPercentBps\":150,\"sessionDate\":20260923}";

  @Test
  void aTypedPayloadLandsInItsColumns() throws Exception {
    final OrderRow row = OrderFeedHandler.toRow(JSON.readValue(TYPED, OrderUpdate.class));
    assertEquals("TRAILING_STOP", row.getOrderType());
    assertEquals("DAY", row.getTimeInForce());
    assertEquals(new BigDecimal("98.860000"), row.getStopPrice());
    assertEquals(150, row.getTrailPercentBps());
    assertEquals(20260923, row.getSessionDate());
    assertEquals(Boolean.FALSE, row.getTriggered());
    assertEquals("DAY_EXPIRED", row.getReason());
    assertEquals("PENDING_TRIGGER", row.getStatus());
    assertNull(row.getPegCap());
  }

  @Test
  void aPegPayloadCarriesReferenceOffsetCapAndSuspendReason() throws Exception {
    final String peg = "{\"id\":\"3-8\",\"accountId\":1,\"security\":\"IBM\",\"side\":\"Sell\","
        + "\"quantity\":10,\"remainingQuantity\":8,\"limitPrice\":100.800000,\"status\":\"SUSPENDED\","
        + "\"orderType\":\"PEGGED\",\"timeInForce\":\"GTC\",\"pegReference\":\"PRIMARY\","
        + "\"pegOffset\":0,\"pegCap\":100.000000,\"suspendReason\":\"RISK\",\"reason\":\"CREDIT_LIMIT\"}";
    final OrderRow row = OrderFeedHandler.toRow(JSON.readValue(peg, OrderUpdate.class));
    assertEquals("PRIMARY", row.getPegReference());
    assertEquals(0, row.getPegOffset());
    assertEquals(new BigDecimal("100.000000"), row.getPegCap());
    assertEquals("RISK", row.getSuspendReason());
    assertEquals("CREDIT_LIMIT", row.getReason());
  }

  @Test
  void anUntypedOrderIsReadAsWhatTheEngineInferredFromItsPrice() {
    final OrderUpdate limit = new OrderUpdate();
    limit.setId("1-1");
    limit.setLimitPrice(new BigDecimal("150.5"));
    final OrderRow l = OrderFeedHandler.toRow(limit);
    assertEquals("LIMIT", l.getOrderType());
    assertEquals("GTC", l.getTimeInForce());
    final OrderUpdate market = new OrderUpdate();
    market.setId("1-2");
    market.setLimitPrice(BigDecimal.ZERO);
    final OrderRow m = OrderFeedHandler.toRow(market);
    assertEquals("MARKET", m.getOrderType());
    assertEquals("IOC", m.getTimeInForce());
  }

  @Test
  @SuppressWarnings("unchecked")
  void pendingAndSuspendedOrdersAreOpen() {
    final OrderRepository repository = mock(OrderRepository.class);
    new OrderController(repository).ordersForAccount(22214, null);
    final ArgumentCaptor<List<String>> statuses = ArgumentCaptor.forClass(List.class);
    verify(repository).findByAccountIdAndStatusIn(eq(22214), statuses.capture());
    assertTrue(statuses.getValue().contains("PENDING_TRIGGER"));
    assertTrue(statuses.getValue().contains("SUSPENDED"));
    assertTrue(statuses.getValue().contains("QUEUED"), "the YU17 live state is kept");
  }
}
