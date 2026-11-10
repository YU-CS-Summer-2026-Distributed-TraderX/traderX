package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finos.traderx.tradeprocessor.model.OrderUpdate;
import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.model.TradeState;
import finos.traderx.tradeprocessor.service.OrderProjectionService;
import finos.traderx.tradeprocessor.service.RunRegistry;
import finos.traderx.tradeprocessor.service.TradeService;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * FR-DRS-35: a store failure (the database, not the message) is a barrier. Nothing after it is applied, the cursor
 * does not pass it and no ack retires it; the feed redelivers it. Found by codeX's review (2026-10-30 §1): snapshot
 * mode logged the failed side as "skipped", booked the next one, and the snapshot saved a cursor past a valid trade.
 */
class FeedBarrierTest {
  static final DataAccessResourceFailureException DOWN = new DataAccessResourceFailureException("database unavailable");

  static void field(Object o, String name, Object value) throws Exception {
    var f = o.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(o, value);
  }

  static Message message(long seq, String json) {
    Message m = mock(Message.class, RETURNS_DEEP_STUBS);
    when(m.getData()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
    when(m.metaData().streamSequence()).thenReturn(seq);
    return m;
  }

  static Message trade(long seq) {
    return message(seq, "{\"type\":\"TradeOrder\",\"payload\":{\"id\":\"e1-x-" + seq + "-B\",\"accountId\":7,"
        + "\"security\":\"AAPL\",\"side\":\"Buy\",\"quantity\":1,\"price\":10}}");
  }

  static TradeFeedHandler snapshotHandler(TradeView view, RunRegistry registry) throws Exception {
    TradeService service = mock(TradeService.class);
    when(service.book(any(), any(), anyMap(), any())).thenAnswer(inv -> {
      TradeOrder o = inv.getArgument(0);
      Position p = inv.getArgument(1);
      p.setQuantity(p.getQuantity() + 1);
      Trade t = new Trade();
      t.setId(o.getId()); t.setAccountId(7); t.setSecurity("AAPL"); t.setCreated(new Date(0)); t.setState(TradeState.New);
      return t;
    });
    TradeFeedHandler h = new TradeFeedHandler();
    h.views().put(0, view);
    field(h, "tradeService", service);
    field(h, "runRegistry", registry);
    return h;
  }

  @Test void snapshotModeStopsAtAStoreFailureAndBooksItOnRedelivery() throws Exception {
    RunRegistry registry = mock(RunRegistry.class);
    when(registry.scope(anyString())).thenThrow(DOWN).thenReturn(Map.of());
    TradeView view = new TradeView();
    TradeFeedHandler h = snapshotHandler(view, registry);
    List<Message> batch = List.of(trade(1), trade(2));

    assertThrows(RuntimeException.class, () -> h.onSnapshotBatch(0, batch), "the failure reaches the feed");
    assertEquals(0, view.streamSeq(), "the cursor does not pass the side that failed");
    assertEquals(0, view.cut().accounting().sides(), "nothing after it is booked");

    h.onSnapshotBatch(0, batch);   // the feed redelivers the batch
    var cut = view.cut();
    assertEquals(2, cut.streamSeq());
    assertEquals(2, cut.accounting().sides(), "both valid sides are booked");
    assertEquals(2, cut.positions().get(0).getQuantity());
  }

  @Test void snapshotModeSkipsARefusalCountedAndBooksTheRest() throws Exception {
    RunRegistry registry = mock(RunRegistry.class);
    when(registry.scope(anyString())).thenReturn(Map.of());
    doThrow(new IllegalArgumentException("RUN_TRADE_ID_MISMATCH")).when(registry)
        .validate(org.mockito.ArgumentMatchers.argThat(e -> e != null && "e1-x-2-B".equals(((TradeOrder) e).getId())), anyMap());
    TradeView view = new TradeView();
    TradeFeedHandler h = snapshotHandler(view, registry);

    h.onSnapshotBatch(0, List.of(trade(1), trade(2), message(3, "{not json"), trade(4)));
    assertEquals(2, h.refused(), "the failed validation and the malformed message are skipped, counted");
    assertEquals(4, view.streamSeq());
    assertEquals(2, view.cut().accounting().sides());
  }

  /** FR-DRS-41: a bridge message holds one or more sides, newline-separated; each is booked, and the cursor is the message's. */
  @Test void aBatchedMessageBooksEveryRecordItHolds() throws Exception {
    RunRegistry registry = mock(RunRegistry.class);
    when(registry.scope(anyString())).thenReturn(Map.of());
    TradeView view = new TradeView();
    TradeFeedHandler h = snapshotHandler(view, registry);
    String three = new String(trade(1).getData(), StandardCharsets.UTF_8) + "\n" + new String(trade(2).getData(), StandardCharsets.UTF_8)
        + "\n" + new String(trade(3).getData(), StandardCharsets.UTF_8);

    assertEquals(3, finos.traderx.messaging.nats.NatsJSONSubscriber.records(three.getBytes(StandardCharsets.UTF_8)).size());
    h.onSnapshotBatch(0, List.of(message(9, three), message(10, new String(trade(4).getData(), StandardCharsets.UTF_8))));
    assertEquals(0, h.refused());
    assertEquals(4, view.cut().accounting().sides(), "three sides from one message and one from the next");
    assertEquals(10, view.streamSeq());
  }

  @Test void theRetiredRowsModeAndCoreFeedRefuseToStart() {   // FR-THT-01
    for (String[] m : new String[][] {{"jetstream", "rows"}, {"core", "snapshot"}, {"core", "rows"}}) {
      TradeFeedHandler h = new TradeFeedHandler();
      h.feedMode = m[0];
      h.projectionMode = m[1];
      IllegalStateException x = assertThrows(IllegalStateException.class, h::afterPropertiesSet);
      assertTrue(x.getMessage().contains("retired (FR-THT-01)"), x.getMessage());
    }
  }

  @Test void aRefusalInsideAStoreExceptionIsStillARefusal() {
    var constraint = new org.springframework.dao.DataIntegrityViolationException("Data too long for column 'security'");
    assertEquals(false, JetStreamFeed.retryable(new org.springframework.transaction.TransactionSystemException("commit", constraint)));
    assertEquals(true, JetStreamFeed.retryable(new org.springframework.transaction.CannotCreateTransactionException("no connection", DOWN)));
    assertEquals(false, JetStreamFeed.retryable(new IllegalStateException("Data too long for column 'security'")));
  }

  @Test void rowModeOrderStoreFailureIsRedeliveredNotRejected() throws Exception {
    OrderProjectionService projection = mock(OrderProjectionService.class);
    doThrow(DOWN).when(projection).persistAll(anyList());
    doThrow(DOWN).when(projection).persist(any());
    OrderFeedHandler h = new OrderFeedHandler();
    h.feedMode = "jetstream";
    field(h, "projectionService", projection);

    assertThrows(RuntimeException.class, () -> h.persistBatch(List.of(order("e1-x-1"), order("e1-x-2"))));
    assertEquals(0, h.rejected(), "an update the database could not take is not counted as rejected and acked");
  }

  static OrderUpdate order(String id) {
    OrderUpdate u = new OrderUpdate();
    u.setId(id); u.setAccountId(7); u.setStatus("NEW");
    return u;
  }
}
