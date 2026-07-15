package finos.traderx.algoengine.eventstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.algoengine.model.AlgoType;
import finos.traderx.algoengine.model.OrderSide;
import finos.traderx.algoengine.model.ParentOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlgoEventStoreReplayTest {

  @Test
  void fullReplayRebuildsEveryParentIncludingAlreadyCompletedOrders() {
    List<AlgoEvent> log = new ArrayList<>();
    log.addAll(completedParent("completed", "child-c"));
    log.addAll(completedParent("second", "child-s"));

    AlgoOrderState restarted = new AlgoOrderState();
    assertEquals(8, AlgoEventStore.replayEvents(log, restarted::apply));

    assertEquals(2, restarted.all().size());
    assertEquals(ParentOrderStatus.COMPLETED, restarted.get("completed").getStatus());
    assertEquals(ParentOrderStatus.COMPLETED, restarted.get("second").getStatus());
    assertTrue(restarted.get("completed").getBuckets().get(0).isFilled());
    assertEquals("child-s", restarted.get("second").getBuckets().get(0).getChildOrderId());
  }

  private static List<AlgoEvent> completedParent(String parentId, String childId) {
    Instant now = Instant.parse("2026-07-15T12:00:00Z");
    return List.of(
        AlgoEvent.parentOrderCreated(parentId, 1, "IBM", OrderSide.Buy, 10, AlgoType.TWAP,
            10, 10, List.of(new AlgoEvent.BucketSeed(0, 0L, 10)), now),
        AlgoEvent.childOrderSubmitted(parentId, 0, childId, parentId + ":0",
            new BigDecimal("100.00"), now),
        AlgoEvent.childOrderFillObserved(parentId, 0, 0, new BigDecimal("100.00"), now),
        AlgoEvent.parentOrderCompleted(parentId, now));
  }
}
