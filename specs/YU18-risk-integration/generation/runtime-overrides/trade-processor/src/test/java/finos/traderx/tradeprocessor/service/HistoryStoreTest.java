package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR-102 cutover: a by-id trade is its SQL exception row, else the history store's side; ids parse both schemes. */
class HistoryStoreTest {
  final List<Map<String, String>> asked = new ArrayList<>();
  final HistoryStore store = new HistoryStore("http://unused") {
    @Override String query(String sql, Map<String, String> params) {
      asked.add(params);
      return "e1-ep7-12-S".equals(params.get("p") + params.get("s") + "-" + params.get("d"))
          ? "7\tIBM\t5\t187.25\t4411\tep7\t946684800000\n" : "";
    }
  };

  @Test void aManagedIdIsLookedUpByItsPartsAndReadBack() {
    HistoryStore.Side s = store.side("e1-ep7-12-S");
    assertEquals(Map.of("s", "12", "d", "S", "p", "e1-ep7-"), asked.get(0));
    assertEquals(new HistoryStore.Side(7, "IBM", "Sell", 5, "187.25", "ep7-4411", 946684800000L), s);
    store.side("12-B");
    assertEquals(Map.of("s", "12", "d", "B", "p", ""), asked.get(1));   // a legacy id: no prefix
  }

  @Test void aNonTradeIdNeverReachesTheStore() {
    for (String id : new String[] {"SEED-17017-20280630", "12-X", "0-B", "", "e1-EP-1-B"}) { assertNull(store.side(id), id); }
    assertTrue(asked.isEmpty());
  }

  @Test void theSqlRowWinsAndOtherwiseTheStoresSideIsTheTrade() {
    TradeRepository sql = mock(TradeRepository.class);
    Trade rejected = new Trade();
    when(sql.findById("e1-ep7-9-B")).thenReturn(Optional.of(rejected));
    when(sql.findById("e1-ep7-12-S")).thenReturn(Optional.empty());
    assertSame(rejected, store.trade(sql, "e1-ep7-9-B").get());
    assertTrue(asked.isEmpty(), "an SQL row needs no store read");
    Trade t = store.trade(sql, "e1-ep7-12-S").get();
    assertEquals(List.of(7, "IBM", TradeSide.Sell, 5, "187.250000", "ep7-4411", 946684800000L),
        List.of(t.getAccountId(), t.getSecurity(), t.getSide(), t.getQuantity(), t.getPrice().toPlainString(), t.getSourceOrderId(),
            t.getCreated().getTime()));
  }
}
