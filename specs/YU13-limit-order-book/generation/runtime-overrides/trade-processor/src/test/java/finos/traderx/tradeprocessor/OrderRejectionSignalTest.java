package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finos.traderx.tradeprocessor.model.OrderRow;
import finos.traderx.tradeprocessor.model.OrderUpdate;
import finos.traderx.tradeprocessor.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The rejection signal (brief 05 item 3): a cluster→read-model write that the DB refuses — VARCHAR
 * truncation, a synthetic/risk-gated account with no FK parent, an epoch-id collision — must NOT
 * drop silently. Here the repository throws exactly as those constraint violations would, and the
 * handler is required to COUNT it (visible signal) and swallow it (one bad row never wedges the
 * feed), instead of the silent divergence that is this project's most expensive bug shape.
 */
class OrderRejectionSignalTest {

  private static OrderUpdate update(String id) {
    OrderUpdate u = new OrderUpdate();
    u.setId(id);
    u.setAccountId(999999); // e.g. a synthetic account with no FK parent
    u.setSecurity("AAPL");
    u.setSide("Buy");
    u.setQuantity(100);
    u.setRemainingQuantity(100);
    u.setStatus("NEW");
    return u;
  }

  @Test
  void aRejectedWriteIsCountedNotSilentlyDropped() {
    OrderRepository repo = mock(OrderRepository.class);
    doThrow(new RuntimeException("FK violation: accountid 999999")).when(repo).save(any(OrderRow.class));

    OrderFeedHandler handler = new OrderFeedHandler();
    ReflectionTestUtils.setField(handler, "orderRepository", repo);

    handler.persist(update("1-1"));
    handler.persist(update("1-2"));

    assertEquals(2, handler.rejected(), "every rejected write must increment the visible counter");
  }

  @Test
  void aSuccessfulWriteDoesNotIncrementTheRejectCounter() {
    OrderRepository repo = mock(OrderRepository.class);
    when(repo.save(any(OrderRow.class))).thenAnswer(inv -> inv.getArgument(0));

    OrderFeedHandler handler = new OrderFeedHandler();
    ReflectionTestUtils.setField(handler, "orderRepository", repo);

    handler.persist(update("1-1"));

    assertEquals(0, handler.rejected());
  }
}
