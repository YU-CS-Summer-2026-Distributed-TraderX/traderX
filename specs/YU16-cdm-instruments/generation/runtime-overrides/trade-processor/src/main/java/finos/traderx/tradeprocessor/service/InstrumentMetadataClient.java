package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.model.InstrumentMetadata;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * YU16 (FR-CDM24): booking-time Treasury metadata, resolved BEFORE the database transaction with
 * bounded timeouts (see {@link finos.traderx.tradeprocessor.config.RuntimeConfig}). Any failure —
 * timeout, 404, 5xx, unparseable body — returns {@code null}, and the caller fails closed by
 * persisting a Rejected trade. Non-Treasury bookings never call this.
 */
@Service
public class InstrumentMetadataClient {

  private static final Logger log = LoggerFactory.getLogger(InstrumentMetadataClient.class);

  private final RestTemplate restTemplate;
  private final Clock clock;
  private final String referenceDataUrl;

  public InstrumentMetadataClient(
      RestTemplate restTemplate,
      Clock clock,
      @Value("${reference.data.service.url:http://reference-data:18085}") String referenceDataUrl) {
    this.restTemplate = restTemplate;
    this.clock = clock;
    this.referenceDataUrl = referenceDataUrl;
  }

  /** {@code null} on ANY failure — the caller's fail-closed contract depends on it. */
  public InstrumentMetadata resolve(String instrumentKey) {
    try {
      return restTemplate.getForObject(referenceDataUrl + "/instruments/{key}", InstrumentMetadata.class, instrumentKey);
    } catch (RuntimeException ex) {
      log.warn("Treasury metadata resolution failed for {}: {}", instrumentKey, ex.getMessage());
      return null;
    }
  }

  /**
   * Matured means no new Treasury activity (FR-CDM21). Missing economics or maturity date is
   * treated as matured — uncertainty fails closed (NFR-CDM07-adjacent; source NFR-01703).
   */
  public boolean isMatured(InstrumentMetadata metadata) {
    if (metadata.isMatured()) {
      return true;
    }
    if (metadata.getDebtEconomics() == null || metadata.getDebtEconomics().getMaturityDate() == null) {
      return true;
    }
    LocalDate today = LocalDate.now(clock);
    LocalDate maturity = LocalDate.parse(metadata.getDebtEconomics().getMaturityDate());
    return !today.isBefore(maturity);
  }
}
