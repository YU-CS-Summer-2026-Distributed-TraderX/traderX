package finos.traderx.tradeprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// YU05 (post-trade-compliance): @EnableScheduling backs SettlementService's T+N sweep and
// ReconciliationService's blotter-poll sweep.
@SpringBootApplication
@EnableScheduling
public class TradeProcessorApplication {

  public static void main(String[] args) {
    SpringApplication.run(TradeProcessorApplication.class, args);
  }
}
