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
}
