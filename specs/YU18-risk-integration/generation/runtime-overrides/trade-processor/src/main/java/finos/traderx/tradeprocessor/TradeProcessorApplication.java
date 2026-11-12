package finos.traderx.tradeprocessor;

import java.util.function.IntConsumer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// YU05 (post-trade-compliance): @EnableScheduling backs SettlementService's T+N sweep and
// ReconciliationService's blotter-poll sweep.
@SpringBootApplication
@EnableScheduling
public class TradeProcessorApplication {

  public static void main(String[] args) {
    exitWhenStartFails(() -> SpringApplication.run(TradeProcessorApplication.class, args), 10_000L, System::exit);
  }

  /**
   * FR-DRS-54: a failed start ends the process. Spring logs the failure and closes the context, but beans already built
   * hold non-daemon NATS threads, so the JVM stayed up: run 6's replica 0 sat 0/1 Running for 50 min after a NATS
   * timeout at start, its partitions unprojected. The wait lets a feed refusal's own exit 3 (JetStreamFeed
   * .exitAfterRefusal, 5 s) go first.
   */
  static void exitWhenStartFails(Runnable start, long waitMillis, IntConsumer exit) {
    try {
      start.run();
    } catch (Throwable failed) {
      try { Thread.sleep(waitMillis); } catch (InterruptedException ignore) { }
      exit.accept(1);
    }
  }
}
