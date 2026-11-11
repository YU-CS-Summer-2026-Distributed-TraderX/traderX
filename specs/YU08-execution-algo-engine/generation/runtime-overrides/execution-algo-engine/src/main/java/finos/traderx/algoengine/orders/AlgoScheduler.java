package finos.traderx.algoengine.orders;

import finos.traderx.algoengine.fills.OrderUpdateSubscriber;
import finos.traderx.algoengine.service.AlgoOrderService;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ticks {@link AlgoOrderService#submitDueBuckets} on a fixed delay (research.md Decision 6/7 —
 * warm-path, seconds granularity, not the BLP hot path). */
@Component
public class AlgoScheduler {
  private final AlgoOrderService algoOrderService;
  private final OrderUpdateSubscriber orderUpdates;

  public AlgoScheduler(AlgoOrderService algoOrderService, OrderUpdateSubscriber orderUpdates) {
    this.algoOrderService = algoOrderService;
    this.orderUpdates = orderUpdates;
  }

  /** FR-AE10: the fill subscription follows the running parents, and is held before any bucket goes out. */
  @Scheduled(fixedDelayString = "${algo.scheduler.tick-ms:1000}")
  public void tick() throws Exception {
    orderUpdates.interest(algoOrderService.hasRunningParent());
    algoOrderService.submitDueBuckets(Instant.now().toEpochMilli());
  }
}
