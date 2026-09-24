package finos.traderx.tradeprocessor.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Database-wide transaction lock. Serialize dedup + read-modify-write across service processes.
 * Deliberately outside the deterministic engine. A missing migration fails closed.
 */
@Component
public final class ProjectionWriteLock {
  private final JdbcTemplate jdbc;
  public ProjectionWriteLock(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  public void acquire() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("projection write lock requires a transaction");
    }
    Integer id = jdbc.queryForObject(
        "SELECT LOCK_ID FROM PROJECTION_WRITE_LOCK WHERE LOCK_ID=1 FOR UPDATE", Integer.class);
    if (id == null || id != 1) { throw new IllegalStateException("missing projection write lock"); }
  }
}
