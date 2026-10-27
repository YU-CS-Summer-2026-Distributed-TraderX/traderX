package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
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

  @Test void aRestartCountsTheSidesBookedWhileDownAgainstTheSavedWatermark() {
    Map<String, Long> db = new HashMap<>();
    var before = new TradeFeedHandler.TradeSeqGaps();
    before.persistIn(db::get, db::put);
    before.observe("e1-x-10-B"); before.observe("e1-x-11-S"); before.save();
    assertEquals(11L, db.get("e1-x-"));

    var after = new TradeFeedHandler.TradeSeqGaps();   // restarted; 12 and 13 were booked while it was down
    after.persistIn(db::get, db::put);
    after.observe("e1-x-14-B");
    assertEquals(2, after.missing());
    assertEquals(14, after.firstSeen());
    after.save();
    assertEquals(14L, db.get("e1-x-"));
  }

  @Test void aRedeliveredSideOrANewEpochIsNotAGap() {
    Map<String, Long> db = new HashMap<>(Map.of("e1-x-", 11L));
    var g = new TradeFeedHandler.TradeSeqGaps();
    g.persistIn(db::get, db::put);
    g.observe("e1-x-10-B");   // a durable consumer redelivers a side the last process already committed
    g.observe("e1-x-11-S");
    g.observe("e1-x-12-B");
    assertEquals(0, g.missing());
    assertEquals(12, g.last());

    var fresh = new TradeFeedHandler.TradeSeqGaps();   // a new epoch restarts tradeSeq and has no watermark
    fresh.persistIn(db::get, db::put);
    fresh.observe("e1-y-1-B");
    assertEquals(0, fresh.missing());
  }
}
