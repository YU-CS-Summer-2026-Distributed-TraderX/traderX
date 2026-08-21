package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finos.traderx.tradeprocessor.model.OrderRow;
import finos.traderx.tradeprocessor.model.OrderUpdate;
import finos.traderx.tradeprocessor.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
  void theTraceIdCarriesThroughToTheRow() {
    OrderUpdate u = update();
    u.setTraceId("0123456789abcdef0123456789abcdef");
    assertEquals("0123456789abcdef0123456789abcdef", OrderFeedHandler.toRow(u).getTraceId());
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

  /**
   * THE TRAP THIS TEST EXISTS FOR. {@code toRow} builds a FRESH row from every update and
   * {@code save()} writes it whole on the fixed primary key, so the first status change after NEW
   * — a partial fill — overwrites traceid with null unless the handler preserves it. The bridge
   * deliberately stamps the id only ONCE, on the order's own NEW (a later update could only carry
   * a different order's key), so keeping it is the read model's job and nothing else's.
   *
   * <p>Driven through {@code persist} against a repository that actually stores rows, so it fails
   * if the preservation is removed rather than merely if a helper is renamed.
   */
  @Test
  void aStatusUpdateAfterNewDoesNotWipeTheTraceId() {
    Map<String, OrderRow> table = new HashMap<>();
    OrderRepository repo = mock(OrderRepository.class);
    when(repo.save(any(OrderRow.class))).thenAnswer(inv -> {
      OrderRow r = inv.getArgument(0);
      table.put(r.getId(), r);
      return r;
    });
    when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(0))));

    OrderFeedHandler handler = new OrderFeedHandler();
    ReflectionTestUtils.setField(handler, "orderRepository", repo);

    OrderUpdate newOrder = update();
    newOrder.setStatus("NEW");
    newOrder.setTraceId("0123456789abcdef0123456789abcdef");
    handler.persist(newOrder);
    assertEquals("0123456789abcdef0123456789abcdef", table.get("3-42").getTraceId());

    // The next update off the bridge carries no trace id, exactly as the wire format intends.
    OrderUpdate fill = update();
    fill.setStatus("PARTIALLY_FILLED");
    assertNull(fill.getTraceId(), "precondition: later updates carry no id, or this proves nothing");
    handler.persist(fill);

    assertEquals("0123456789abcdef0123456789abcdef", table.get("3-42").getTraceId(),
        "a NEW followed by a PARTIAL_FILL must still have the trace id — otherwise the feature "
        + "silently undoes itself on the first fill and the row's trace link vanishes");
  }

  /** An incoming id always wins, so a re-published NEW is not blocked by whatever is already there. */
  @Test
  void anIncomingTraceIdOverwritesTheStoredOne() {
    Map<String, OrderRow> table = new HashMap<>();
    OrderRepository repo = mock(OrderRepository.class);
    when(repo.save(any(OrderRow.class))).thenAnswer(inv -> {
      OrderRow r = inv.getArgument(0);
      table.put(r.getId(), r);
      return r;
    });
    when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(0))));

    OrderFeedHandler handler = new OrderFeedHandler();
    ReflectionTestUtils.setField(handler, "orderRepository", repo);

    OrderUpdate first = update();
    first.setTraceId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    handler.persist(first);
    OrderUpdate second = update();
    second.setTraceId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    handler.persist(second);

    assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", table.get("3-42").getTraceId());
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
