package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-DRS-54: a start that fails exits the process; one that succeeds does not. */
class TradeProcessorApplicationTest {
  @Test
  void aFailedStartExitsOne() {
    List<Integer> exits = new ArrayList<>();
    TradeProcessorApplication.exitWhenStartFails(() -> { throw new IllegalStateException("no NATS"); }, 0L, exits::add);
    assertEquals(List.of(1), exits);
  }

  @Test
  void aCleanStartDoesNotExit() {
    List<Integer> exits = new ArrayList<>();
    TradeProcessorApplication.exitWhenStartFails(() -> { }, 0L, exits::add);
    assertEquals(List.of(), exits);
  }
}
