package finos.traderx.accountservice.service;

import finos.traderx.accountservice.model.RiskControlEvent;
import finos.traderx.accountservice.repository.RiskControlEventRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class RiskControlFeedService {
  public static final long SOURCE_EPOCH = 1L;
  public record Snapshot(long sourceEpoch, long watermark, long highWatermark,
                         List<RiskControlEvent> controls) {}
  public record Mutation(String type, String aggregateKey, long expectedVersion, boolean enabled,
                         long policyVersion, int maxPositionQuantity,
                         long maxConcentrationNotionalTicks) {}

  private final RiskControlEventRepository repository;

  public RiskControlFeedService(RiskControlEventRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public RiskControlEvent mutate(Mutation mutation, String operator) {
    String type = normalizeType(mutation.type());
    String key = normalizeKey(mutation.aggregateKey(), type);
    long current = repository.currentVersion(type, key);
    if (mutation.expectedVersion() != current) {
      throw new StaleControlVersionException(current, mutation.expectedVersion());
    }
    if ("POLICY".equals(type) && (mutation.policyVersion() <= 0
        || mutation.maxPositionQuantity() <= 0 || mutation.maxConcentrationNotionalTicks() <= 0)) {
      throw new IllegalArgumentException("policy limits and policyVersion must be positive");
    }
    long now = System.currentTimeMillis();
    long version = repository.append(SOURCE_EPOCH, type, key, mutation.enabled(),
        mutation.policyVersion(), mutation.maxPositionQuantity(),
        mutation.maxConcentrationNotionalTicks(), operator, now);
    return new RiskControlEvent(version, SOURCE_EPOCH, type, key, mutation.enabled(),
        mutation.policyVersion(), mutation.maxPositionQuantity(),
        mutation.maxConcentrationNotionalTicks(), operator, now);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Snapshot snapshot() {
    long watermark = repository.watermark();
    return new Snapshot(SOURCE_EPOCH, watermark, watermark, repository.state());
  }

  public List<RiskControlEvent> deltasAfter(long version) {
    return repository.after(version);
  }

  private static String normalizeType(String input) {
    String type = input == null ? "" : input.trim().toUpperCase(java.util.Locale.ROOT);
    if (!type.equals("POLICY") && !type.equals("RESTRICTION") && !type.equals("KILL_SWITCH")) {
      throw new IllegalArgumentException("unsupported risk control type: " + input);
    }
    return type;
  }

  private static String normalizeKey(String input, String type) {
    String key = input == null ? "" : input.trim().toUpperCase(java.util.Locale.ROOT);
    if (key.isEmpty()) key = "GLOBAL";
    if ((type.equals("POLICY") || type.equals("KILL_SWITCH")) && !key.equals("GLOBAL")) {
      throw new IllegalArgumentException(type + " aggregate key must be GLOBAL");
    }
    return key;
  }

  public static final class StaleControlVersionException extends RuntimeException {
    private final long current;
    private final long supplied;
    public StaleControlVersionException(long current, long supplied) {
      super("stale expectedVersion=" + supplied + ", currentVersion=" + current);
      this.current = current;
      this.supplied = supplied;
    }
    public long current() { return current; }
    public long supplied() { return supplied; }
  }
}
