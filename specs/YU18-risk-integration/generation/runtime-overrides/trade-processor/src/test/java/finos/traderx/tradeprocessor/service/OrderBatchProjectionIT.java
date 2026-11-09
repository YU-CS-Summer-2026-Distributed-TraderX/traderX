package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.*;

import finos.traderx.tradeprocessor.model.*;
import finos.traderx.tradeprocessor.repository.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-28: the batched orderbook writer leaves exactly the rows the per-update path leaves. One
 * adversarial stream (late updates across batch boundaries, exact redeliveries, several outputs of
 * one command, typed orders, a legacy-scope slice) is applied per update through
 * {@link OrderProjectionService#persist} and batched through {@link OrderProjectionService#persistAll}
 * at three batch sizes; every column of every row and the run counters must match. A rolled-back
 * batch and a JVM killed with SIGKILL inside a batch's transaction both leave nothing of that batch,
 * and replaying from the last committed batch ends in the same rows.
 */
@Tag("integration")
@Testcontainers
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=OrderBatchProjectionIT.Config.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class OrderBatchProjectionIT {
  @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.4")
      .withCommand("--lower_case_table_names=1")
      .withCreateContainerCmdModifier(cmd -> cmd.withName("traderx-order-batch-sql")
          .getHostConfig().withMemory(1073741824L).withNanoCPUs(1000000000L));
  /** Production's URL flag (application.properties); the comparison must hold with it set. */
  static String url() { return DB.getJdbcUrl()+"?rewriteBatchedStatements=true"; }
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", OrderBatchProjectionIT::url);
    r.add("spring.datasource.username", DB::getUsername);
    r.add("spring.datasource.password", DB::getPassword);
    r.add("spring.datasource.driverClassName", () -> "org.mariadb.jdbc.Driver");
    r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MariaDBDialect");
    r.add("spring.jpa.properties.hibernate.jdbc.batch_size", () -> "1000");
    r.add("spring.jpa.properties.hibernate.order_inserts", () -> "true");
    r.add("spring.jpa.properties.hibernate.order_updates", () -> "true");
  }
  @Configuration @EnableAutoConfiguration
  @EntityScan(basePackageClasses=Trade.class)
  @EnableJpaRepositories(basePackageClasses=TradeRepository.class)
  static class Config {}
  @Autowired OrderRepository orders;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;

  static final String SCOPE=OrderBatchStream.SCOPE, EPOCH=OrderBatchStream.EPOCH;

  @BeforeEach void prepare() throws Exception { reset(jdbc); }

  static void reset(JdbcTemplate jdbc) throws Exception {
    jdbc.execute("CREATE TABLE IF NOT EXISTS PROJECTION_WRITE_LOCK (LOCK_ID INTEGER PRIMARY KEY)");
    jdbc.update("INSERT IGNORE INTO PROJECTION_WRITE_LOCK VALUES (1)");
    try (var c=jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
          new org.springframework.core.io.FileSystemResource("../postgres-database-replacement/mariadb-migrations/ri06.sql"));
    }
    jdbc.update("DELETE FROM orderbook");
    jdbc.update("DELETE FROM projection_transitions");
    jdbc.update("DELETE FROM projection_recon");
    jdbc.update("UPDATE projection_active SET projection_scope='legacy-unknown' WHERE singleton_id=1");
    jdbc.update("DELETE FROM projection_runs WHERE projection_scope<>'legacy-unknown'");
    jdbc.update("UPDATE projection_runs SET phase='LEGACY',cluster_epoch=NULL,descriptor_hash=NULL,descriptor_json=NULL,storage_lineage=NULL,order_updates=0,checkpoint_seq=0 WHERE projection_scope='legacy-unknown'");
    jdbc.update("INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,descriptor_hash,phase) VALUES (?,?,'epoch-v1',?,'ACTIVE')",
        SCOPE,EPOCH,"a".repeat(64));
  }

  static OrderProjectionService service(OrderRepository orders,JdbcTemplate jdbc,PlatformTransactionManager tx) {
    return new OrderProjectionService(orders,new RunRegistry(jdbc),new ProjectionWriteLock(jdbc),new TransactionTemplate(tx));
  }
  OrderProjectionService service() { return service(orders,jdbc,transactions); }

  /** Every column of every orderbook row, and the run counters: the state the two paths must share. */
  static String state(JdbcTemplate jdbc) {
    StringBuilder out=new StringBuilder();
    for (Map<String,Object> row:jdbc.queryForList("SELECT * FROM orderbook ORDER BY orderid")) {out.append(new TreeMap<>(row)).append('\n');}
    for (Map<String,Object> run:jdbc.queryForList("SELECT projection_scope,order_updates,checkpoint_seq FROM projection_runs ORDER BY projection_scope")) {
      out.append(new TreeMap<>(run)).append('\n');
    }
    return out.toString();
  }

  // ----- the cases --------------------------------------------------------------------------

  /** The per-update reference: exactly what a run of single-update transactions leaves. */
  String reference(List<OrderUpdate> stream) throws Exception {
    reset(jdbc);
    var s=service();
    for (OrderUpdate u:stream) {s.persist(u);}
    return state(jdbc);
  }

  @Test void batchedEqualsPerUpdateOnAnAdversarialStream() throws Exception {
    List<OrderUpdate> stream=OrderBatchStream.adversarial(42,1500);
    String expected=reference(stream);
    assertTrue(expected.split("\n").length>1500, "the reference wrote too little to compare");
    for (int size:new int[]{1000,37,1}) {
      reset(jdbc);
      var s=service();
      for (var b:OrderBatchStream.batches(stream,size)) {s.persistAll(b);}
      assertEquals(expected,state(jdbc),"batch size "+size+" left different rows or counters");
    }
  }

  /** The sealed legacy run, the one branch that reads the run row's phase per update. */
  @Test void aSealedLegacyRunAcceptsAnIdenticalRedeliveryAndRefusesAChangeOnBothPaths() throws Exception {
    List<OrderUpdate> stream=OrderBatchStream.lifecycles(3,200,1);
    var s=service();
    for (var b:OrderBatchStream.batches(stream,100)) {s.persistAll(b);}
    jdbc.update("UPDATE projection_runs SET phase='SEALED' WHERE projection_scope='legacy-unknown'");
    String sealed=state(jdbc);
    Map<String,OrderUpdate> last=new LinkedHashMap<>();
    for (OrderUpdate u:stream) {last.put(u.getId(),u);}
    List<OrderUpdate> finals=new ArrayList<>(last.values());
    s.persistAll(finals);
    for (OrderUpdate u:finals) {s.persist(u);}
    assertEquals(sealed,state(jdbc),"an identical redelivery into a sealed run changed something");
    OrderUpdate changed=finals.get(0);
    OrderUpdate copy=new OrderUpdate();
    org.springframework.beans.BeanUtils.copyProperties(changed,copy);
    copy.setQuantity(changed.getQuantity()+1);   // an economic change (timestamps are not compared)
    assertThrows(IllegalStateException.class,() -> s.persistAll(List.of(finals.get(1),copy)));
    assertThrows(IllegalStateException.class,() -> s.persist(copy));
    assertEquals(sealed,state(jdbc));
  }

  /** FR-DRS-40: a legacy order event carries its position (as the members' bridge writes it with no run
   *  descriptor). Both paths write it, and it counts for no run. Before: RUN_DESCRIPTOR_MISMATCH on the first. */
  @Test void aLegacyUpdateWithAPositionIsWrittenOnBothPathsAndCountsForNoRun() throws Exception {
    List<OrderUpdate> stream=OrderBatchStream.lifecycles(5,200,1);
    long seq=0;
    for (OrderUpdate u:stream) {u.setConsensusSequence(++seq);u.setOutputOrdinal(0);}
    String expected=reference(stream);
    assertTrue(expected.contains("consensussequence="+seq+","),"the last update's position is not in its row:\n"+expected);
    assertTrue(expected.contains("{checkpoint_seq=0, order_updates=0, projection_scope=legacy-unknown}"),
        "a legacy update moved the legacy run's counters:\n"+expected);
    reset(jdbc);
    var s=service();
    for (var b:OrderBatchStream.batches(stream,37)) {s.persistAll(b);}
    assertEquals(expected,state(jdbc));
  }

  @Test void aConflictInABatchRollsTheWholeBatchBack() throws Exception {
    var s=service();
    s.persistAll(List.of(OrderBatchStream.managed(1,10,0)).stream().peek(u->{u.setAccountId(1001);u.setSecurity("IBM");u.setSide("Buy");
      u.setQuantity(10);u.setRemainingQuantity(10);u.setStatus("NEW");u.setCreatedAt(1L);u.setUpdatedAt(2L);}).toList());
    String before=state(jdbc);
    OrderUpdate fresh=OrderBatchStream.managed(2,11,0);fresh.setAccountId(1001);fresh.setSecurity("IBM");fresh.setSide("Buy");
    fresh.setQuantity(5);fresh.setRemainingQuantity(5);fresh.setStatus("NEW");fresh.setCreatedAt(1L);fresh.setUpdatedAt(2L);
    OrderUpdate conflict=OrderBatchStream.managed(1,10,0);conflict.setAccountId(1001);conflict.setSecurity("IBM");conflict.setSide("Buy");
    conflict.setQuantity(10);conflict.setRemainingQuantity(9);conflict.setStatus("NEW");conflict.setCreatedAt(1L);conflict.setUpdatedAt(2L);
    assertThrows(IllegalArgumentException.class,() -> s.persistAll(List.of(fresh,conflict)));
    assertEquals(before,state(jdbc),"a refused batch wrote some of its rows");
  }

  @Test void aRolledBackBatchLeavesNothingAndReplayFromTheLastCommitIsExact() throws Exception {
    List<OrderUpdate> stream=OrderBatchStream.adversarial(7,800);
    String expected=reference(stream);
    reset(jdbc);
    var s=service();
    var bs=OrderBatchStream.batches(stream,500);
    for (int i=0;i<3;i++) {s.persistAll(bs.get(i));}
    String committed=state(jdbc);
    var tx=new TransactionTemplate(transactions);
    tx.executeWithoutResult(status -> {s.persistAll(bs.get(3));status.setRollbackOnly();});
    assertEquals(committed,state(jdbc),"a rolled-back batch left rows or counted updates");
    // At-least-once: the last committed batch is redelivered (its ack was lost), then the rest.
    for (int i=2;i<bs.size();i++) {s.persistAll(bs.get(i));}
    assertEquals(expected,state(jdbc));
  }

  @Test void aSigkillInsideABatchLeavesNothingOfItAndReplayIsExact() throws Exception {
    List<OrderUpdate> stream=OrderBatchStream.adversarial(11,KILL_ORDERS);
    // The batched path is the reference here: the first case proves it equal to per-update.
    var ref=service();
    for (var b:OrderBatchStream.batches(stream,1000)) {ref.persistAll(b);}
    String expected=state(jdbc);
    reset(jdbc);
    // information_schema.innodb_trx needs PROCESS, which the test user lacks; Testcontainers gives root the same password.
    JdbcTemplate root=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(url(),"root",DB.getPassword(),true));
    Path log=Files.createTempFile("order-batch-kill",".log");
    List<String> cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
        "-cp",System.getProperty("java.class.path"),OrderBatchKillMain.class.getName(),
        url(),DB.getUsername(),DB.getPassword(),"11",String.valueOf(KILL_ORDERS),String.valueOf(KILL_BATCH)));
    Process child=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      // Wait for two committed batches, then for the child's transaction to hold written rows.
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
      while (committed(log).size()<2) {assertTrue(System.nanoTime()<deadline && child.isAlive(),"child: "+tail(log));Thread.sleep(5);}
      // Kill when the child has executed an upsert of a batch it has not committed: UPSERTED is its last line.
      while (true) {
        List<String> lines=Files.readAllLines(log);
        if (!lines.isEmpty() && lines.get(lines.size()-1).equals("UPSERTED")) {break;}
        assertTrue(System.nanoTime()<deadline && child.isAlive(),"never caught the child inside a batch: "+tail(log));
        Thread.sleep(2);
      }
      assertEquals(0,new ProcessBuilder("kill","-9",String.valueOf(child.pid())).start().waitFor());
      assertTrue(child.waitFor(20,TimeUnit.SECONDS));
    } finally {child.destroyForcibly();}
    List<String> done=committed(log);
    String last=done.get(done.size()-1);   // "COMMITTED <batch> <order_updates> <rows>"
    String[] f=last.split(" ");
    int lastBatch=Integer.parseInt(f[1]);
    // The server rolls the dead connection's transaction back; nothing of the killed batch may remain.
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
    while (root.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx",Long.class)>0 && System.nanoTime()<deadline) {Thread.sleep(20);}
    assertEquals(Long.parseLong(f[2]),jdbc.queryForObject("SELECT order_updates FROM projection_runs WHERE projection_scope=?",Long.class,SCOPE),
        "the killed batch counted updates: "+last);
    assertEquals(Long.parseLong(f[3]),jdbc.queryForObject("SELECT COUNT(*) FROM orderbook",Long.class),"the killed batch left rows: "+last);
    // Redeliver from the last committed batch (its ack may not have happened), then the rest.
    var s=service();
    var bs=OrderBatchStream.batches(stream,KILL_BATCH);
    for (int i=lastBatch;i<bs.size();i++) {s.persistAll(bs.get(i));}
    assertEquals(expected,state(jdbc),"replay after SIGKILL did not reach the per-update rows");
  }
  static final int KILL_BATCH=4000, KILL_ORDERS=40_000;

  /** The child's last lines: where it failed, not its banner. */
  static String tail(Path log) throws Exception {
    List<String> lines=Files.readAllLines(log);
    return String.join("\n",lines.subList(Math.max(0,lines.size()-25),lines.size()));
  }

  static List<String> committed(Path log) throws Exception {
    return Files.readAllLines(log).stream().filter(l->l.startsWith("COMMITTED ")).toList();
  }
}
