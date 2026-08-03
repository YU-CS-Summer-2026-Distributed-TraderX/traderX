package finos.traderx.accountservice.outbox;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Transactional-outbox table for account existence/identity changes (ADR-021). {@link
 * #recordChange} must always be called from within the same transaction as the {@code accounts}
 * write it accompanies (see {@code AccountService.upsertAccount}) — atomicity comes from that
 * shared local transaction, not from anything in this class.
 */
@Repository
public class AccountControlOutboxRepository {

  private static final RowMapper<OutboxRow> ROW_MAPPER = (rs, rowNum) -> new OutboxRow(
      rs.getLong("version"),
      rs.getInt("account_id"),
      rs.getString("display_name"),
      rs.getTimestamp("created_at").toInstant());

  private final JdbcTemplate jdbcTemplate;

  public AccountControlOutboxRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Inserts one outbox row for an account create/update; returns its assigned version. */
  public long recordChange(int accountId, String displayName) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "insert into account_control_outbox (account_id, display_name, published, created_at) "
              + "values (?, ?, false, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setInt(1, accountId);
      ps.setString(2, displayName);
      ps.setTimestamp(3, Timestamp.from(Instant.now()));
      return ps;
    }, keyHolder);
    return keyHolder.getKey().longValue();
  }

  public List<OutboxRow> findUnpublished(int limit) {
    return jdbcTemplate.query(
        "select version, account_id, display_name, created_at from account_control_outbox "
            + "where published = false order by version asc limit ?",
        ROW_MAPPER, limit);
  }

  public void markPublished(long version) {
    jdbcTemplate.update("update account_control_outbox set published = true where version = ?", version);
  }

  /** Highest version already published — the watermark exposed by the snapshot endpoint. */
  public long publishedWatermark() {
    try {
      Long max = jdbcTemplate.queryForObject(
          "select coalesce(max(version), 0) from account_control_outbox where published = true", Long.class);
      return max == null ? 0L : max;
    } catch (EmptyResultDataAccessException ex) {
      return 0L;
    }
  }

  public int unpublishedCount() {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from account_control_outbox where published = false", Integer.class);
    return count == null ? 0 : count;
  }
}
