package finos.traderx.historyfeed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FR-HST-01: the bridges' exact managed wire (TradeNatsPublisher / OrderNatsPublisher encode) into one row each. */
class RowsTest {
  static final String TRADE = "{\"topic\":\"/trades\",\"from\":\"cluster-bridge\",\"type\":\"TradeOrder\",\"date\":1761782400123,"
      + "\"payload\":{\"id\":\"e1-ep7-981-S\",\"state\":\"New\",\"security\":\"IBM\",\"quantity\":250,\"price\":187.250000,"
      + "\"accountId\":22016,\"side\":\"Sell\",\"sourceOrderId\":\"ep7-4411\",\"projectionScope\":\"run-ep7\","
      + "\"clusterEpoch\":\"ep7\",\"eventIdScheme\":\"e1\",\"runDescriptorHash\":\"ab12\",\"consensusSequence\":77}}";
  static final String ORDER = "{\"topic\":\"/orders\",\"from\":\"cluster-bridge\",\"type\":\"OrderUpdate\",\"date\":1761782400200,"
      + "\"payload\":{\"id\":\"ep7-4411\",\"accountId\":22016,\"security\":\"IBM\",\"side\":\"Sell\",\"quantity\":400,"
      + "\"remainingQuantity\":150,\"limitPrice\":187.000000,\"status\":\"PARTIALLY_FILLED\",\"lastExecutionPrice\":187.250000,"
      + "\"lastFillQuantity\":250,\"createdAt\":1761782399000,\"updatedAt\":1761782400200,"
      + "\"traceId\":\"0000000000000000f00d\",\"clientOrderKey\":\"18446744073709551615\",\"reason\":\"NONE\","
      + "\"projectionScope\":\"run-ep7\",\"clusterEpoch\":\"ep7\",\"eventIdScheme\":\"e1\",\"runDescriptorHash\":\"ab12\","
      + "\"consensusSequence\":77,\"outputOrdinal\":2}}";

  @Test void trade_side_is_one_row_with_its_stream_sequence() {
    Rows b = new Rows(true, 4);
    new Rows.Parser().parse(TRADE.getBytes(StandardCharsets.UTF_8), b, 5001);
    assertEquals(1, b.n);
    assertEquals(5001, b.seq[0]);
    assertEquals(1761782400123L, b.ts[0]);
    assertEquals(981, b.key[0]);
    assertEquals('S', b.side[0]);
    assertEquals("IBM", b.sec[0]);
    assertEquals(250, b.qty[0]);
    assertEquals(187.25, b.px[0], 1e-9);
    assertEquals(22016, b.acct[0]);
    assertEquals(4411, b.ref[0]);
    assertEquals(77, b.cseq[0]);
    assertEquals("e1-ep7-", b.idPrefix[0], "the blotter rebuilds e1-ep7-981-S from it (ADR-102 cutover)");
    assertEquals("ep7", b.orderEpoch[0], "and ep7-4411 from this");
  }

  @Test void order_event_keeps_status_fill_and_the_unsigned_client_key() {
    Rows b = new Rows(false, 4);
    new Rows.Parser().parse(ORDER.getBytes(StandardCharsets.UTF_8), b, 5002);
    assertEquals(4411, b.key[0]);
    assertEquals(1761782400200L, b.ts[0], "updatedAt is the order event's time");
    assertEquals('S', b.side[0]);
    assertEquals(400, b.qty[0]);
    assertEquals(150, b.remaining[0]);
    assertEquals(187.0, b.px[0], 1e-9);
    assertEquals("PARTIALLY_FILLED", b.status[0]);
    assertEquals(187.25, b.lastPx[0], 1e-9);
    assertEquals(250, b.lastQty[0]);
    assertEquals(1761782399000L, b.created[0]);
    assertEquals(2, b.ordinal[0]);
    assertEquals("18446744073709551615", Long.toUnsignedString(b.ref[0]));
  }

  @Test void legacy_unmanaged_trade_id_parses_too() {
    Rows b = new Rows(true, 1);
    new Rows.Parser().parse(TRADE.replace("e1-ep7-981-S", "981-B").getBytes(StandardCharsets.UTF_8), b, 1);
    assertEquals(981, b.key[0]);
    assertEquals("", b.idPrefix[0]);
    assertEquals('B', b.side[0]);
  }

  @Test void a_trade_id_without_a_side_is_refused_not_stored() {
    Rows b = new Rows(true, 1);
    assertThrows(RuntimeException.class,
        () -> new Rows.Parser().parse(TRADE.replace("e1-ep7-981-S", "e1-ep7-981-X").getBytes(StandardCharsets.UTF_8), b, 1));
  }

  @Test void stream_naming_and_times_match_trade_processor() {
    assertEquals("TRADERX_FEED_P3", HistoryFeedMain.streamFor("TRADERX_FEED", "partition", 3, 4));
    assertEquals("TRADERX_FEED", HistoryFeedMain.streamFor("TRADERX_FEED", "partition", 0, 1));
    assertEquals("TRADERX_FEED", HistoryFeedMain.streamFor("TRADERX_FEED", "one", 3, 4));
    // FR-THT-02: the bridges' subject rule (bare at one partition); production 2026-11-07 read /trades.0 and stored nothing
    assertEquals("/trades", HistoryFeedMain.subjectFor("trades", 0, 1));
    assertEquals("/orders.3", HistoryFeedMain.subjectFor("orders", 3, 4));
    assertEquals(Map.of("/trades", 6L, "/orders", 4416L), HistoryFeedMain.unowned(
        Map.of("/trades", 6L, "/orders", 4416L, "/other", 9L), java.util.Set.of("/trades.0", "/orders.0")));
    assertEquals(Map.of(), HistoryFeedMain.unowned(Map.of("/trades", 6L, "/orders", 0L), java.util.Set.of("/trades", "/orders")));
    assertEquals("2025-10-30 00:00:00.123", HistoryFeedMain.time(Map.of("t", "1761782400123"), "t"));
    assertEquals("2025-10-30 00:00:00.123", HistoryFeedMain.time(Map.of("t", "2025-10-30T00:00:00.123Z"), "t"));
    assertThrows(RuntimeException.class, () -> HistoryFeedMain.time(Map.of("t", "1; DROP TABLE trades"), "t"));
  }
}
