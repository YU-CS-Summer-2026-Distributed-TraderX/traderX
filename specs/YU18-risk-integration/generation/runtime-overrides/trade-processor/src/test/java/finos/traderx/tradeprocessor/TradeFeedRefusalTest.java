package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.service.TradeService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;

/**
 * FR-DRS-02 (suspect 14): a side SQL refuses is acked and never commits, so missingTradeSides counts it,
 * and still counts it after a restart. Measured live: yu15-option-persistence's option fill, refused by
 * narrowed symbol columns, left missingTradeSides 0 with the watermark past it.
 */
class TradeFeedRefusalTest {
  @Test void aRefusedSideIsMissingNowAndAfterARestart() throws Exception {
    Map<String, Long> db = new HashMap<>();
    TradeFeedHandler before = handler(db, "e1-x-12-S");
    List<TradeOrder> batch = List.of(order("e1-x-11-B"), order("e1-x-12-S"), order("e1-x-13-B"));
    for (TradeOrder o : batch) { before.gaps().observe(o.getId()); }   // as onJetStreamBatch does
    process(before, batch);
    assertEquals(1, before.gaps().missing(), "12-S never committed");
    assertEquals(13L, db.get("e1-x-"), "the watermark still advances: the sides after it did commit");

    TradeFeedHandler after = handler(db, null);   // restarted; nothing was booked while it was down
    after.gaps().observe("e1-x-14-S");
    assertEquals(1, after.gaps().missing(), "FR-DRS-02: SHALL NOT read 0 while a never-committed side exists");
  }

  private static TradeFeedHandler handler(Map<String, Long> db, String refusedId) throws Exception {
    TradeService service = mock(TradeService.class);
    when(service.processTrades(anyList())).thenThrow(new IllegalStateException("batch refused"));
    if (refusedId != null) {
      ArgumentMatcher<TradeOrder> refused = o -> o != null && refusedId.equals(o.getId());
      doThrow(new IllegalStateException("Data too long for column 'security'"))
          .when(service).processTrade(ArgumentMatchers.argThat(refused));
    }
    TradeFeedHandler h = new TradeFeedHandler();
    var field = TradeFeedHandler.class.getDeclaredField("tradeService");
    field.setAccessible(true);
    field.set(h, service);
    h.gaps().persistIn(db::get, db::put);
    return h;
  }

  private static void process(TradeFeedHandler h, List<TradeOrder> batch) throws Exception {
    var m = TradeFeedHandler.class.getDeclaredMethod("processBatch", List.class);
    m.setAccessible(true);
    m.invoke(h, batch);
  }

  private static TradeOrder order(String id) {
    return new TradeOrder(id, 22214, "AAPL260918C00260000", TradeSide.Buy, 1);
  }
}
