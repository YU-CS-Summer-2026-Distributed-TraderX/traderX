package finos.traderx.algoengine.orders;

import finos.traderx.algoengine.service.AlgoOrderService;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ticks {@link AlgoOrderService#submitDueBuckets} on a fixed delay (research.md Decision 6/7 —
 * warm-path, seconds granularity, not the BLP hot path). */
@Component
public class AlgoScheduler {
  private final AlgoOrderService algoOrderService;

  public AlgoScheduler(AlgoOrderService algoOrderService) {
    this.algoOrderService = algoOrderService;
  }

  @Scheduled(fixedDelayString = "${algo.scheduler.tick-ms:1000}")
  public void tick() {
    algoOrderService.submitDueBuckets(Instant.now().toEpochMilli());
  }
}
