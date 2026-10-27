package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.model.TradeState;
import finos.traderx.tradeprocessor.service.TradeService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradeViewTest {
  static final java.util.Date DAY1 = java.util.Date.from(java.time.Instant.parse("2026-10-22T15:00:00Z"));
  static final java.util.Date DAY2 = java.util.Date.from(java.time.Instant.parse("2026-10-23T23:59:59Z"));
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
    t.setCreated(DAY1);
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

  /** The snapshot path books through TradeService with the row path's bond rule: a bond with no
   *  reference data lands Rejected on the blotter and never reaches a position, not even a flat one. */
  @Test void aBondWithoutReferenceDataIsRejectedAndNeverReachesAPosition() {
    TradeService service = new TradeService(null, null, null, null, 1, null, null);  // no metadata client: fail closed
    TradeView v = new TradeView(5);
    var cache = new HashMap<String, finos.traderx.tradeprocessor.model.InstrumentMetadata>();
    assertTrue(v.apply(fill("b1", 3, "UST-20280630", TradeSide.Buy, 100, 1), 1, (o, p) -> service.book(o, p, cache, DAY1)));
    assertTrue(v.apply(fill("e1", 3, "AAPL", TradeSide.Buy, 10, 2), 2, (o, p) -> service.book(o, p, cache, DAY1)));
    TradeView.Cut cut = v.cut();
    assertEquals(List.of("AAPL"), cut.positions().stream().map(Position::getSecurity).toList());
    assertEquals(1, v.positionCount());
    Trade bond = cut.blotters().get(3).get(0);
    assertEquals(TradeState.Rejected, bond.getState());
    assertEquals("Bond reference metadata is unavailable", bond.getRejectionReason());
    assertEquals(2, cut.applied());
    assertEquals(java.util.Map.of(java.time.LocalDate.parse("2026-10-22"), 1L), cut.days());   // the rejected bond never counts toward settlement
  }

  /** Settlement on the ledger: sides are counted per UTC trade date of the LEDGER time handed to the booker. */
  @Test void sidesAreCountedPerTradeDateAndAFailedCutKeepsThem() {
    TradeService service = new TradeService(null, null, null, null, 1, null, null);
    var cache = new HashMap<String, finos.traderx.tradeprocessor.model.InstrumentMetadata>();
    TradeView v = new TradeView(5);
    v.loadDays(java.util.Map.of(java.time.LocalDate.parse("2026-10-22"), 40L));
    v.apply(fill("a", 1, "AAPL", TradeSide.Buy, 1, 1), 1, (o, p) -> service.book(o, p, cache, DAY1));
    v.apply(fill("b", 2, "AAPL", TradeSide.Sell, 1, 2), 2, (o, p) -> service.book(o, p, cache, DAY2));
    v.apply(fill("c", 2, "AAPL", TradeSide.Sell, 1, 3), 3, (o, p) -> service.book(o, p, cache, DAY2));
    TradeView.Cut cut = v.cut();
    assertEquals(java.util.Map.of(java.time.LocalDate.parse("2026-10-22"), 41L, java.time.LocalDate.parse("2026-10-23"), 2L), cut.days());
    assertEquals(DAY2, cut.blotters().get(2).get(0).getCreated());                              // created = ledger time, not wall clock
    v.remark(cut);
    assertEquals(2, v.cut().days().size());
  }

  /** An idle partition's cursor follows the stream head, so retention never ages it out; a lower head is ignored. */
  @Test void idleMovesTheCursorForwardOnly() {
    TradeView v = new TradeView(5);
    v.load(List.of(), List.of(), 100, 7);
    v.idle(250);
    assertEquals(250, v.streamSeq());
    v.idle(90);
    assertEquals(250, v.streamSeq());
    assertFalse(v.apply(fill("old", 1, "A", TradeSide.Buy, 1, 8), 200, TradeViewTest::book));
  }
}
