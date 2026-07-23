package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import finos.traderx.tradeprocessor.model.OrderRow;
import finos.traderx.tradeprocessor.model.OrderUpdate;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The read-model consumer's DTO → entity mapping is what lands in the {@code orderbook} row a proof
 * later asserts on, so it is pinned here: the epoch-qualified id becomes the primary key verbatim,
 * every field carries through, and a null wire timestamp defaults rather than persisting null into
 * the NOT NULL columns.
 */
class OrderFeedHandlerTest {

  private static OrderUpdate update() {
    OrderUpdate u = new OrderUpdate();
    u.setId("3-42");
    u.setAccountId(22214);
    u.setSecurity("AAPL");
    u.setSide("Buy");
    u.setQuantity(100);
    u.setRemainingQuantity(60);
    u.setLimitPrice(new BigDecimal("150.500"));
    u.setStatus("PARTIALLY_FILLED");
    u.setLastExecutionPrice(new BigDecimal("150.500"));
    u.setLastFillQuantity(40);
    u.setCreatedAt(1_700_000_000_000L);
    u.setUpdatedAt(1_700_000_000_500L);
    return u;
  }

  @Test
  void epochQualifiedIdBecomesThePrimaryKeyVerbatim() {
    // If this drifts, the read-model row is keyed differently from what the bridge published and the
    // upsert would insert duplicates instead of overwriting — the collision this design prevents.
    assertEquals("3-42", OrderFeedHandler.toRow(update()).getId());
  }

  @Test
  void everyFieldCarriesThrough() {
    OrderRow row = OrderFeedHandler.toRow(update());
    assertEquals(22214, row.getAccountId());
    assertEquals("AAPL", row.getSecurity());
    assertEquals("Buy", row.getSide());
    assertEquals(100, row.getQuantity());
    assertEquals(60, row.getRemainingQuantity());
    assertEquals(new BigDecimal("150.500"), row.getLimitPrice());
    assertEquals("PARTIALLY_FILLED", row.getStatus());
    assertEquals(40, row.getLastFillQuantity());
    assertEquals(1_700_000_000_000L, row.getCreatedAt().getTime());
    assertEquals(1_700_000_000_500L, row.getUpdatedAt().getTime());
  }

  @Test
  void nullWireTimestampDefaultsInsteadOfViolatingNotNull() {
    OrderUpdate u = update();
    u.setCreatedAt(null);
    u.setUpdatedAt(null);
    OrderRow row = OrderFeedHandler.toRow(u);
    // createdat / updatedat are NOT NULL in orderbook — a null here would be a silent row rejection.
    org.junit.jupiter.api.Assertions.assertNotNull(row.getCreatedAt());
    org.junit.jupiter.api.Assertions.assertNotNull(row.getUpdatedAt());
  }

  @Test
  void nullableFieldsStayNull() {
    OrderUpdate u = update();
    u.setLastExecutionPrice(null);
    u.setLastFillQuantity(null);
    OrderRow row = OrderFeedHandler.toRow(u);
    assertNull(row.getLastExecutionPrice());
    assertNull(row.getLastFillQuantity());
  }
}
