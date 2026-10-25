package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TradeSeqGapsTest {
  @Test void countsMissingLegsAcrossManagedAndLegacyIdsAndIgnoresReplays() {
    var g = new TradeFeedHandler.TradeSeqGaps();
    assertEquals(41, TradeFeedHandler.TradeSeqGaps.seqOf("e1-epoch_7-41-B"));
    assertEquals(9, TradeFeedHandler.TradeSeqGaps.seqOf("9-S"));
    assertEquals(-1, TradeFeedHandler.TradeSeqGaps.seqOf("garbage"));
    g.observe("e1-x-10-B"); g.observe("e1-x-11-S");   // baseline, contiguous
    g.observe("e1-x-15-B");                            // 12,13,14 lost
    g.observe("e1-x-12-S");                            // late duplicate/replay: no change
    g.observe("e1-x-16-S");
    assertEquals(3, g.missing());
    assertEquals(16, g.last());
    assertEquals(10, g.firstSeen());
  }
}
