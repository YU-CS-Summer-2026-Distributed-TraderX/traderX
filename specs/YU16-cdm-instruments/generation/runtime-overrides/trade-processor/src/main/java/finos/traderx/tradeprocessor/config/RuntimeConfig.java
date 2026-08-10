package finos.traderx.tradeprocessor.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * YU16 runtime beans for Treasury booking (FR-CDM24, NFR-CDM09):
 * - a Clock honoring the one optional fixed-clock contract, so maturity behavior is testable;
 * - a RestTemplate with bounded, configurable timeouts (the metadata client's fail-closed
 *   contract needs an upper bound, not hope);
 * - a TransactionTemplate so TradeService can resolve metadata BEFORE the database transaction
 *   rather than inside an annotation-opened one.
 */
@Configuration
public class RuntimeConfig {

  @Bean
  public Clock clock(@Value("${traderx.fixed-utc-instant:}") String fixedUtcInstant) {
    if (fixedUtcInstant == null || fixedUtcInstant.isBlank()) {
      return Clock.systemUTC();
    }
    return Clock.fixed(Instant.parse(fixedUtcInstant), ZoneOffset.UTC);
  }

  @Bean
  public RestTemplate restTemplate(
      RestTemplateBuilder builder,
      @Value("${reference.data.connect.timeout.ms:2000}") long connectTimeoutMs,
      @Value("${reference.data.read.timeout.ms:5000}") long readTimeoutMs) {
    return builder
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .readTimeout(Duration.ofMillis(readTimeoutMs))
        .build();
  }

  @Bean
  public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }
}
