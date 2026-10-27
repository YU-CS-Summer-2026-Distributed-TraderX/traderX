package finos.traderx.tradeprocessor.service;

import static org.assertj.core.api.Assertions.assertThat;

import finos.traderx.tradeprocessor.TradeView;
import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.model.TradeState;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * YU18 trade store: partition snapshot writers must not deadlock each other. Measured live at P=4
 * (traderx-dsq, 2026-10-27): 8 deadlocks in a minute between partitions' blotter-window writes, which own
 * disjoint accounts. It reproduces here under REPEATABLE READ and under READ COMMITTED alike; the fix is one
 * lock row for every partition's snapshot. Two writers on interleaved accounts (partition = account mod 2),
 * on the SHIPPED schema.
 */
@Tag("integration")
@Testcontainers
class TradeSnapshotStoreConcurrencyIT {
  @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>(
      "mariadb@sha256:f2f5e00999bd335343e09091e27e39c9386eb1822efcd0e0bac40b0003fa47e3")
      .withCommand("--lower-case-table-names=1");
  private static final Path CONFIGMAP = Path.of("../kubernetes-runtime/manifests/base/database-init-configmap.yaml");
  private static final int ACCOUNTS = 40, ROUNDS = 150, WINDOW = 5;

  @BeforeAll static void schema() throws Exception {
    execute(sql("001-initialSchema.sql"));
    execute(sql("900-migrations.sql"));
    StringBuilder accounts = new StringBuilder("INSERT IGNORE INTO accounts(id,displayname) VALUES ");
    for (int a = 1; a <= ACCOUNTS; a++) { accounts.append(a == 1 ? "" : ",").append("(").append(a).append(",'a").append(a).append("')"); }
    execute(accounts + "; INSERT IGNORE INTO projection_write_lock(lock_id) VALUES (1),(2),(3)");
  }

  @Test void interleavedPartitionWritersNeverDeadlock() throws Exception {
    DriverManagerDataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    TradeSnapshotStore store = new TradeSnapshotStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)),
        new ProjectionWriteLock(jdbc));
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> writers = new ArrayList<>();
    for (int p = 0; p < 2; p++) {
      final int partition = p;
      Thread t = new Thread(() -> {
        try { start.await(); } catch (InterruptedException e) { return; }
        for (int round = 0; round < ROUNDS; round++) {
          try { store.write(RunRegistry.UNKNOWN, partition, cut(partition, round)); }
          catch (Throwable x) { failures.add(x); }
        }
      });
      t.start();
      writers.add(t);
    }
    start.countDown();
    for (Thread t : writers) { t.join(); }
    assertThat(failures).as("snapshot writes that failed (deadlock or lock wait)").isEmpty();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trades WHERE accountid BETWEEN 1 AND ?", Integer.class, ACCOUNTS))
        .as("each account holds exactly its last window").isEqualTo(ACCOUNTS * WINDOW);   // the shipped schema seeds other accounts' rows
  }

  /** One snapshot of a partition: every one of its accounts moved, each window replaced by new fills. */
  private static TradeView.Cut cut(int partition, int round) {
    List<Position> positions = new ArrayList<>();
    Map<Integer, List<Trade>> blotters = new HashMap<>();
    for (int a = 1; a <= ACCOUNTS; a++) {
      if (a % 2 != partition) { continue; }
      Position p = new Position();
      p.setProjectionScope(RunRegistry.UNKNOWN); p.setAccountId(a); p.setSecurity("AAPL");
      p.setQuantity(round); p.setAverageCostBasis(new BigDecimal("10.000000")); p.setUpdated(new Date());
      positions.add(p);
      List<Trade> window = new ArrayList<>();
      for (int i = 0; i < WINDOW; i++) {
        Trade t = new Trade();
        t.setId(a + "-" + round + "-" + i + "-B"); t.setAccountId(a); t.setSecurity("AAPL"); t.setSide(TradeSide.Buy);
        t.setState(TradeState.Processing); t.setQuantity(1); t.setPrice(new BigDecimal("10.000000"));
        t.setCreated(new Date()); t.setUpdated(new Date()); t.setSettlementDate(new Date());
        t.setProjectionScope(RunRegistry.UNKNOWN);
        window.add(t);
      }
      blotters.put(a, window);
    }
    return new TradeView.Cut(positions, blotters, Map.of(), round + 1L, round + 1L, 0);
  }

  private static void execute(String sql) throws Exception {
    String url = DB.getJdbcUrl() + (DB.getJdbcUrl().contains("?") ? "&" : "?") + "allowMultiQueries=true";
    try (Connection c = DriverManager.getConnection(url, DB.getUsername(), DB.getPassword()); Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  /** One block-scalar key of the shipped configmap (the same extraction SchemaMatchesShippedDdlIT uses). */
  private static String sql(String key) throws Exception {
    StringBuilder sql = new StringBuilder();
    boolean in = false;
    for (String line : Files.readAllLines(CONFIGMAP)) {
      if (line.equals("  " + key + ": |")) { in = true; continue; }
      if (in) {
        if (!line.isBlank() && !line.startsWith("    ")) { break; }
        sql.append(line.length() > 4 ? line.substring(4) : "").append('\n');
      }
    }
    assertThat(sql).as(key).isNotEmpty();
    return sql.toString();
  }
}
