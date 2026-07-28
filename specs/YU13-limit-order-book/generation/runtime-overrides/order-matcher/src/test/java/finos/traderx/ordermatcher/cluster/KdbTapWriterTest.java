package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The capture CSV is the contract txstore.q parses positionally, so the column order and the
 * drop-signal discipline are pinned here. Everything else about this tap is deliberately boring —
 * the interesting property (it never touches the apply path) is structural, not testable.
 */
class KdbTapWriterTest {

  private static KdbTapWriter writer(File dir, int capacity) {
    return new KdbTapWriter(dir, "7", "1", capacity);
  }

  private static String encodeOrder(File dir, byte status, long limitPx) {
    KdbTapWriter.Rec r = new KdbTapWriter.Rec();
    r.kind = KdbTapWriter.KIND_ORDER;
    r.seq = 12;
    r.orderRef = 42;
    r.accountId = 22214;
    r.security = "AAPL";
    r.side = 0; // buy
    r.quantity = 100;
    r.remainingQty = 60;
    r.limitPx = limitPx;
    r.status = status;
    r.lastExecPx = 150_250_000L;
    r.lastFillQty = 40;
    r.createdAtMillis = 1_700_000_000_000L;
    r.updatedAtMillis = 1_700_000_000_500L;
    return writer(dir, 16).encode(new StringBuilder(), r);
  }

  @Test
  void orderLineMatchesTheColumnOrderTxstoreParses(@TempDir File dir) {
    // seq,epoch,ref,account,sym,side,qty,remaining,limitPx,status,lastExecPx,lastFillQty,createdMs,updatedMs
    assertEquals("12,7,42,22214,AAPL,B,100,60,150.500000,PARTIALLY_FILLED,150.250000,40,"
        + "1700000000000,1700000000500\n", encodeOrder(dir, (byte) 1, 150_500_000L));
    assertEquals(KdbTapWriter.ORDER_HEADER.split(",").length,
        encodeOrder(dir, (byte) 1, 150_500_000L).trim().split(",").length,
        "header and row must have the same column count or q parses the file into garbage");
  }

  @Test
  void tradeLineMatchesTheColumnOrderTxstoreParses(@TempDir File dir) {
    KdbTapWriter.Rec r = new KdbTapWriter.Rec();
    r.kind = KdbTapWriter.KIND_TRADE;
    r.seq = 12;
    r.tradeSeq = 900;
    r.accountId = 22214;
    r.security = "AAPL";
    r.side = 1; // sell
    r.quantity = 40;
    r.limitPx = 150_250_000L; // trade px
    r.updatedAtMillis = 1_700_000_000_500L;
    String line = writer(dir, 16).encode(new StringBuilder(), r);
    // seq,epoch,tradeSeq,account,sym,side,qty,px,tsMs
    assertEquals("12,7,900,22214,AAPL,S,40,150.250000,1700000000500\n", line);
    assertEquals(KdbTapWriter.TRADE_HEADER.split(",").length, line.trim().split(",").length);
  }

  @Test
  void pxNoneRendersAsZeroNotNegative(@TempDir File dir) {
    assertTrue(encodeOrder(dir, (byte) 0, -1).contains(",0.000000,NEW,"),
        "Px.NONE must not leak a negative price into the analytical store");
  }

  @Test
  void capturedRowsLandInTheRightFileUnderTheirHeader(@TempDir File dir) throws Exception {
    KdbTapWriter w = writer(dir, 16);
    w.start();
    w.offerOrder(12, 42, 22214, "AAPL", 3, (byte) 0, 100, 60, 150_500_000L, (byte) 1,
        150_250_000L, 40, 1_700_000_000_000L, 1_700_000_000_500L);
    w.offerTrade(12, 900, 22214, "AAPL", 3, (byte) 1, 40, 150_250_000L, 1_700_000_000_500L);
    for (int i = 0; i < 200 && w.captured() < 2; i++) {
      Thread.sleep(10);
    }
    w.stop();
    assertEquals(2, w.captured());
    assertEquals(0, w.errors());
    // Per-epoch, per-member file names: three members' captures share one directory.
    List<String> orders = Files.readAllLines(new File(dir, "txorder-7-1.csv").toPath());
    List<String> trades = Files.readAllLines(new File(dir, "txtrade-7-1.csv").toPath());
    assertEquals(KdbTapWriter.ORDER_HEADER, orders.get(0));
    assertEquals(KdbTapWriter.TRADE_HEADER, trades.get(0));
    assertEquals(2, orders.size());
    assertEquals(2, trades.size());
    assertTrue(orders.get(1).startsWith("12,7,42,22214,AAPL,B,"), orders.get(1));
    assertTrue(trades.get(1).startsWith("12,7,900,22214,AAPL,S,"), trades.get(1));
  }

  @Test
  void aFullQueueDropsAndCountsRatherThanBlockingTheApplyThread(@TempDir File dir) {
    // No start() — nothing drains, so the queue fills and every further offer must drop, not
    // block. A silent drop here is the bug class this project has hit four times.
    KdbTapWriter w = writer(dir, 8);
    for (int i = 0; i < 40; i++) {
      w.offerOrder(i, i, 1, "AAPL", 3, (byte) 0, 1, 1, 1_000_000L, (byte) 0, -1, 0, 0, 0);
    }
    assertTrue(w.dropped() > 0, "a full capture queue must drop and count");
    assertEquals(40, w.dropped() + 8, "every offer is either queued or counted as dropped");
  }

  @Test
  void theCapStopsCaptureBeforeItCanFillTheArchiveVolume(@TempDir File dir) throws Exception {
    // The capture shares /data with the Aeron Archive. At the measured order ceiling an uncapped
    // tap fills a 1Gi member volume in ~20s, which stops the ARCHIVE writing — the analytical path
    // killing the authoritative one. The cap must hold and must be audible.
    KdbTapWriter w = new KdbTapWriter(dir, "7", "1", 64, 400); // 400 bytes ~ 4 rows
    w.start();
    for (int i = 0; i < 40; i++) {
      w.offerOrder(i, i, 22214, "AAPL", 3, (byte) 0, 100, 60, 150_500_000L, (byte) 1,
          150_250_000L, 40, 1_700_000_000_000L, 1_700_000_000_500L);
    }
    for (int i = 0; i < 200 && w.capped() == 0; i++) {
      Thread.sleep(10);
    }
    w.stop();
    assertTrue(w.capped() > 0, "the cap must actually stop capture");
    assertEquals(40, w.captured() + w.dropped() + w.capped(),
        "every offered row is written, dropped, or capped — none unaccounted for");
    assertTrue(new File(dir, "txorder-7-1.csv").length() <= 400 + KdbTapWriter.ORDER_HEADER.length() + 1,
        "the file must not exceed the cap (header aside)");
  }

  @Test
  void anUnresolvedTickerIsCapturedAsItsNumericIdNeverDropped(@TempDir File dir) {
    // Dropping the row would thin the store silently — the bug class this tap exists to avoid.
    KdbTapWriter.Rec r = new KdbTapWriter.Rec();
    r.kind = KdbTapWriter.KIND_ORDER;
    r.security = null;
    r.securityId = 7;
    assertTrue(writer(dir, 8).encode(new StringBuilder(), r).contains(",#7,"),
        "an unregistered security must appear as #<id>, not vanish");
    // The gateway registers the EMPTY symbol for a malformed order (seen on kind), and an empty
    // CSV column reads as corruption downstream.
    r.security = "";
    assertTrue(writer(dir, 8).encode(new StringBuilder(), r).contains(",#7,"),
        "an empty ticker is unregistered too, not a blank column");
  }
}
