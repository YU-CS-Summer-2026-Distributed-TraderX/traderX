package finos.traderx.tradeprocessor.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Database-wide transaction lock. Serialize dedup + read-modify-write across service processes.
 * Deliberately outside the deterministic engine. A missing migration fails closed.
 *
 * <p>YU18 (downstream redesign, direction c): ONE LOCK ROW PER PARTITION. A feed thread that owns
 * partition {@code p} (accounts with {@code accountId mod P == p}) declares it with
 * {@link #partition(int)} and from then on locks row {@code 1 + p}; partitions never share a trade
 * id, an order or a position, so they commit in parallel instead of behind one database-wide row.
 * Row 1 is the lock every pre-existing caller takes (partition 0, the default).
 * ponytail: a run transition still takes only row 1; take all P rows there before running
 * partitions alongside transitions.
 */
@Component
public final class ProjectionWriteLock {
  private static final ThreadLocal<Integer> PARTITION = ThreadLocal.withInitial(() -> 0);
  private final JdbcTemplate jdbc;
  public ProjectionWriteLock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  /** Bind the calling thread to a partition; every later {@link #acquire} on it locks that row. */
  public static void partition(int p) { PARTITION.set(p); }

  /** Make sure rows 1..n exist, so a partition thread's lock row is there to lock. */
  public void ensurePartitions(int n) {
    for (int p = 2; p <= n; p++) {
      jdbc.update("INSERT IGNORE INTO PROJECTION_WRITE_LOCK (LOCK_ID) VALUES (?)", p);
    }
  }

  public void acquire() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("projection write lock requires a transaction");
    }
    int lockId = 1 + PARTITION.get();
    Integer id = jdbc.queryForObject(
        "SELECT LOCK_ID FROM PROJECTION_WRITE_LOCK WHERE LOCK_ID=? FOR UPDATE", Integer.class, lockId);
    if (id == null || id != lockId) { throw new IllegalStateException("missing projection write lock " + lockId); }
  }
}
