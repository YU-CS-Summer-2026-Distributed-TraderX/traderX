package finos.traderx.algoengine.eventstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.algoengine.model.AlgoType;
import finos.traderx.algoengine.model.OrderSide;
import finos.traderx.algoengine.model.ParentOrder;
import finos.traderx.algoengine.model.ParentOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-030: applying the same sequence of events must reproduce the exact same state whether it
 * happens live (one event at a time as it occurs) or all at once on replay after a restart. */
class AlgoOrderStateTest {

  @Test
  void replayingTheFullEventSequenceReproducesLiveState() {
    List<AlgoEvent> events = List.of(
        AlgoEvent.parentOrderCreated("p1", 22214, "IBM", OrderSide.Buy, 200, AlgoType.TWAP, 20, 10,
            List.of(new AlgoEvent.BucketSeed(0, 0L, 100), new AlgoEvent.BucketSeed(1, 10_000L, 100)),
            Instant.now()),
        AlgoEvent.childOrderSubmitted("p1", 0, "child-0", "p1:0", new BigDecimal("100.10"), Instant.now()),
        AlgoEvent.childOrderFillObserved("p1", 0, 0, new BigDecimal("100.10"), Instant.now()),
        AlgoEvent.childOrderSubmitted("p1", 1, "child-1", "p1:1", new BigDecimal("100.20"), Instant.now()),
        AlgoEvent.childOrderFillObserved("p1", 1, 0, new BigDecimal("100.20"), Instant.now()),
        AlgoEvent.parentOrderCompleted("p1", Instant.now()));

    // Live: apply one at a time as they "occur".
    AlgoOrderState live = new AlgoOrderState();
    events.forEach(live::apply);

    // Replay: a fresh state applying the exact same log after a simulated restart.
    AlgoOrderState replayed = new AlgoOrderState();
    events.forEach(replayed::apply);

    ParentOrder liveOrder = live.get("p1");
    ParentOrder replayedOrder = replayed.get("p1");
    assertEquals(liveOrder.getStatus(), replayedOrder.getStatus());
    assertEquals(ParentOrderStatus.COMPLETED, replayedOrder.getStatus());
    assertTrue(replayedOrder.allBucketsFilled());
    assertEquals(liveOrder.getBuckets().get(0).getChildOrderId(), replayedOrder.getBuckets().get(0).getChildOrderId());
    assertEquals(liveOrder.getBuckets().get(1).getLastExecutionPrice(), replayedOrder.getBuckets().get(1).getLastExecutionPrice());
  }

  @Test
  void reapplyingAnEventIsIdempotent() {
    AlgoEvent created = AlgoEvent.parentOrderCreated("p2", 1, "MSFT", OrderSide.Sell, 50, AlgoType.TWAP,
        10, 10, List.of(new AlgoEvent.BucketSeed(0, 0L, 50)), Instant.now());
    AlgoEvent fillObserved = AlgoEvent.childOrderFillObserved("p2", 0, 0, new BigDecimal("50.00"), Instant.now());

    AlgoOrderState state = new AlgoOrderState();
    state.apply(created);
    state.apply(fillObserved);
    state.apply(fillObserved); // simulated redelivery after a crash between append and ack

    assertTrue(state.get("p2").getBuckets().get(0).isFilled());
  }

  @Test
  void partialFillLeavesBucketUnfilledAndParentRunning() {
    AlgoOrderState state = stateWithTwoBuckets("partial");
    state.apply(AlgoEvent.childOrderFillObserved("partial", 0, 4,
        new BigDecimal("99.50"), Instant.now()));

    assertTrue(!state.get("partial").getBuckets().get(0).isFilled());
    assertEquals(4, state.get("partial").getBuckets().get(0).getRemainingQuantity());
    assertEquals(ParentOrderStatus.RUNNING, state.get("partial").getStatus());
  }

  @Test
  void fillForUnknownParentOrBucketIsIgnored() {
    AlgoOrderState state = stateWithTwoBuckets("known");
    state.apply(AlgoEvent.childOrderFillObserved("missing", 0, 0, BigDecimal.ONE, Instant.now()));
    AlgoEvent outOfRange = AlgoEvent.childOrderFillObserved("known", 99, 0, BigDecimal.ONE, Instant.now());
    // A corrupt/out-of-range event must not take down replay.
    state.apply(outOfRange);
    assertEquals(2, state.get("known").getBuckets().size());
  }

  @Test
  void parentCompletesOnlyAfterAllBucketsAreFilledAndCompletionEventApplied() {
    AlgoOrderState state = stateWithTwoBuckets("all");
    state.apply(AlgoEvent.childOrderFillObserved("all", 0, 0, BigDecimal.ONE, Instant.now()));
    assertEquals(ParentOrderStatus.RUNNING, state.get("all").getStatus());
    state.apply(AlgoEvent.childOrderFillObserved("all", 1, 0, BigDecimal.ONE, Instant.now()));
    assertEquals(ParentOrderStatus.RUNNING, state.get("all").getStatus());
    state.apply(AlgoEvent.parentOrderCompleted("all", Instant.now()));
    assertEquals(ParentOrderStatus.COMPLETED, state.get("all").getStatus());
  }

  private static AlgoOrderState stateWithTwoBuckets(String parentId) {
    AlgoOrderState state = new AlgoOrderState();
    state.apply(AlgoEvent.parentOrderCreated(parentId, 1, "IBM", OrderSide.Buy, 20, AlgoType.TWAP,
        20, 10, List.of(new AlgoEvent.BucketSeed(0, 0L, 10),
            new AlgoEvent.BucketSeed(1, 10_000L, 10)), Instant.now()));
    return state;
  }
}
