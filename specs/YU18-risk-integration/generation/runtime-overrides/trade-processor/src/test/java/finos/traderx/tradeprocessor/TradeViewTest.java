package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.model.TradeSide;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradeViewTest {
  private static TradeOrder fill(String id, int account, String sec, TradeSide side, int qty, long seq) {
    TradeOrder o = new TradeOrder(id, account, sec, side, qty);
    o.setPrice(new BigDecimal("10.000000"));
    o.setConsensusSequence(seq);
    return o;
  }

  /** The booker under test adds the signed quantity; the real one is TradeService.book. */
  private static Trade book(TradeOrder o, Position p) {
    p.setQuantity(p.getQuantity() + (o.getSide() == TradeSide.Buy ? o.getQuantity() : -o.getQuantity()));
    Trade t = new Trade();
    t.setId(o.getId()); t.setAccountId(o.getAccountId()); t.setSecurity(o.getSecurity());
    t.setSide(o.getSide()); t.setQuantity(o.getQuantity()); t.setConsensusSequence(o.getConsensusSequence());
    return t;
  }

  @Test void cutCarriesOnlyWhatChangedAndTheCursorItIsExactAt() {
    TradeView v = new TradeView(2);
    assertTrue(v.apply(fill("1-B", 7, "AAPL", TradeSide.Buy, 100, 10), 101, TradeViewTest::book));
    assertTrue(v.apply(fill("2-S", 7, "AAPL", TradeSide.Sell, 30, 11), 102, TradeViewTest::book));
    assertTrue(v.apply(fill("3-B", 8, "MSFT", TradeSide.Buy, 5, 12), 103, TradeViewTest::book));
    TradeView.Cut cut = v.cut();
    assertEquals(2, cut.positions().size());
    assertEquals(70, cut.positions().stream().filter(p -> p.getAccountId() == 7).findFirst().get().getQuantity());
    assertEquals(List.of("1-B", "2-S"), cut.blotters().get(7).stream().map(Trade::getId).toList());
    assertEquals(103, cut.streamSeq());
    assertEquals(12, cut.consensusSeq());
    assertEquals(3, cut.applied());
    assertTrue(v.cut().isEmpty());                                    // nothing changed since
  }

  @Test void replayAtOrBelowTheCursorIsIgnoredAndTheBlotterRingIsBounded() {
    TradeView v = new TradeView(2);
    v.load(List.of(), List.of(), 500, 40);
    assertFalse(v.apply(fill("x", 1, "A", TradeSide.Buy, 1, 41), 500, TradeViewTest::book));
    for (int i = 1; i <= 3; i++) { v.apply(fill("f" + i, 1, "A", TradeSide.Buy, 1, 41 + i), 500 + i, TradeViewTest::book); }
    TradeView.Cut cut = v.cut();
    assertEquals(List.of("f2", "f3"), cut.blotters().get(1).stream().map(Trade::getId).toList());
    assertEquals(3, cut.positions().get(0).getQuantity());
  }

  @Test void aFailedSnapshotIsRemarkedForTheNextCut() {
    TradeView v = new TradeView(5);
    v.apply(fill("1", 2, "A", TradeSide.Buy, 1, 1), 1, TradeViewTest::book);
    TradeView.Cut first = v.cut();
    v.remark(first);
    TradeView.Cut again = v.cut();
    assertEquals(1, again.positions().size());
    assertEquals(1, again.applied());
  }

  @Test void loadRestoresPositionsAndWindows() {
    TradeView v = new TradeView(3);
    Position p = new Position(); p.setProjectionScope("s"); p.setAccountId(9); p.setSecurity("Z"); p.setQuantity(50);
    Trade t = new Trade(); t.setId("old"); t.setAccountId(9); t.setSecurity("Z"); t.setSide(TradeSide.Buy); t.setQuantity(50);
    v.load(List.of(p), List.of(t), 1000, 70);
    TradeOrder o = fill("new", 9, "Z", TradeSide.Sell, 20, 71); o.setProjectionScope("s");
    v.apply(o, 1001, TradeViewTest::book);
    TradeView.Cut cut = v.cut();
    assertEquals(30, cut.positions().get(0).getQuantity());
    assertEquals(List.of("old", "new"), cut.blotters().get(9).stream().map(Trade::getId).toList());
  }
}
