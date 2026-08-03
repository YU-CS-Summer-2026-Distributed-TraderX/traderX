package finos.traderx.accountservice.outbox;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The account source epoch (ADR-021/ADR-019): a single row, seeded to 1 on first boot, bumped
 * only for a deliberate unrecoverable resync — never by normal operation.
 */
@Repository
public class SourceEpochRepository {

  private final JdbcTemplate jdbcTemplate;

  public SourceEpochRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @PostConstruct
  public void ensureSeeded() {
    Integer count = jdbcTemplate.queryForObject("select count(*) from account_source_epoch", Integer.class);
    if (count == null || count == 0) {
      jdbcTemplate.update("insert into account_source_epoch (epoch) values (1)");
    }
  }

  public long currentEpoch() {
    Long epoch = jdbcTemplate.queryForObject("select epoch from account_source_epoch limit 1", Long.class);
    return epoch == null ? 1L : epoch;
  }
}
