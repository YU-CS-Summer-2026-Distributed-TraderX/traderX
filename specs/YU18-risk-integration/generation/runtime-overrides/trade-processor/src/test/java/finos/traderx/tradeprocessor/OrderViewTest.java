package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.tradeprocessor.model.OrderUpdate;
import org.junit.jupiter.api.Test;

class OrderViewTest {
  private static OrderUpdate u(String id, int account, String status, Long seq, Integer ordinal, String trace) {
    OrderUpdate o = new OrderUpdate();
    o.setId(id); o.setAccountId(account); o.setStatus(status);
    o.setConsensusSequence(seq); o.setOutputOrdinal(ordinal); o.setTraceId(trace);
    return o;
  }

  @Test void laterSequenceReplacesEarlierIsIgnoredAndTraceIdSurvives() {
    OrderView v = new OrderView(10);
    v.apply(u("1-7", 5, "NEW", 10L, 0, "abc"));
    v.apply(u("1-7", 5, "PARTIALLY_FILLED", 12L, 1, null));
    v.apply(u("1-7", 5, "NEW", 11L, 0, null));          // late: must not rewind
    assertEquals(1, v.open(5).size());
    assertEquals("PARTIALLY_FILLED", v.open(5).get(0).getStatus());
    assertEquals("abc", v.open(5).get(0).getTraceId());
    assertEquals(2, v.applied());
    assertEquals(1, v.ignored());
  }

  @Test void terminalOrdersLeaveOpenButStayInAllUntilTheRingEvictsThem() {
    OrderView v = new OrderView(2);
    v.apply(u("1-1", 9, "NEW", 1L, 0, null));
    v.apply(u("1-1", 9, "FILLED", 2L, 0, null));
    v.apply(u("1-2", 9, "CANCELED", 3L, 0, null));
    assertEquals(0, v.open(9).size());
    assertEquals(2, v.all(9).size());
    v.apply(u("1-3", 9, "REJECTED", 4L, 0, null));      // ring holds 2: 1-1 is evicted
    assertEquals(2, v.all(9).size());
    assertTrue(v.all(9).stream().noneMatch(o -> o.getId().equals("1-1")));
    assertEquals(2, v.size());
  }

  @Test void evictionNeverRemovesANewerEntryForTheSameId() {
    OrderView v = new OrderView(1);
    v.apply(u("1-1", 3, "CANCELED", 1L, 0, null));
    v.apply(u("1-1", 3, "CANCELED", 2L, 0, null));      // newer terminal for the same id
    v.apply(u("1-2", 3, "FILLED", 3L, 0, null));        // evicts the seq-2 entry object, not seq-1's slot
    assertEquals(1, v.all(3).size());
    assertEquals("1-2", v.all(3).get(0).getId());
  }

  @Test void legacyUpdatesWithoutSequenceAreLastWriteWins() {
    OrderView v = new OrderView(10);
    v.apply(u("9-1", 1, "NEW", null, null, null));
    v.apply(u("9-1", 1, "CANCELED", null, null, null));
    assertEquals("CANCELED", v.all(1).get(0).getStatus());
  }
}
