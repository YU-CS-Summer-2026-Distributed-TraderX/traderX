package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.model.*;
import finos.traderx.tradeprocessor.repository.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-28 measurement, opt-in ({@code -Dtraderx.bench=true}): order updates per second through
 * {@link OrderProjectionService#persistAll} in 1000-update batches, the production feed's batch.
 * Put latency between this JVM and the database (e.g. {@code tc qdisc add dev docker0 root netem
 * delay 1ms}); on loopback the per-row round trip this measures is invisible. Prints one
 * {@code ORDER-BATCH-BENCH} line with updates/s, distinct-order rows/s and statements per batch.
 */
@Tag("integration")
@EnabledIfSystemProperty(named="traderx.bench",matches="true")
@Testcontainers
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=OrderBatchThroughputIT.Config.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class OrderBatchThroughputIT {
  @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.4")
      .withCommand("--lower_case_table_names=1")
      .withCreateContainerCmdModifier(cmd -> cmd.withName("traderx-order-batch-bench")
          .getHostConfig().withMemory(2147483648L).withNanoCPUs(2000000000L));
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> DB.getJdbcUrl()+"?rewriteBatchedStatements=true");
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

  long status(String name) {
    return Long.parseLong(jdbc.queryForList("SHOW GLOBAL STATUS LIKE '"+name+"'").get(0).get("Value").toString());
  }

  @Test void updatesPerSecond() throws Exception {
    jdbc.execute("CREATE TABLE IF NOT EXISTS PROJECTION_WRITE_LOCK (LOCK_ID INTEGER PRIMARY KEY)");
    jdbc.update("INSERT IGNORE INTO PROJECTION_WRITE_LOCK VALUES (1)");
    try (var c=jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
          new org.springframework.core.io.FileSystemResource("../postgres-database-replacement/mariadb-migrations/ri06.sql"));
    }
    jdbc.update("INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,descriptor_hash,phase) VALUES (?,?,'epoch-v1',?,'ACTIVE')",
        OrderBatchStream.SCOPE,OrderBatchStream.EPOCH,"a".repeat(64));
    var service=new OrderProjectionService(orders,new RunRegistry(jdbc),new ProjectionWriteLock(jdbc),
        new org.springframework.transaction.support.TransactionTemplate(transactions));
    long secs=Long.getLong("traderx.bench.seconds",60);
    // Engine order, no redelivery: a crossing order's NEW and fills mostly share a batch, as live.
    // traderx.bench.legacy-every: 1 = every order unattributed (the CompSci render today), 0 = all managed.
    List<OrderUpdate> stream=OrderBatchStream.lifecycles(5,Integer.getInteger("traderx.bench.orders",200_000),
        Integer.getInteger("traderx.bench.legacy-every",1));
    stream.sort(Comparator.comparing((OrderUpdate u)->u.getConsensusSequence()==null ? u.getUpdatedAt()/7 : u.getConsensusSequence()));
    var batches=OrderBatchStream.batches(stream,1000);
    service.persistAll(batches.get(0));   // warm-up: connection, JIT, plans
    long q0=status("Questions"),i0=status("Com_insert"),u0=status("Com_update"),r0=jdbc.queryForObject("SELECT COUNT(*) FROM orderbook",Long.class);
    long t0=System.nanoTime(),updates=0;int n=1;
    for (;n<batches.size() && System.nanoTime()-t0<secs*1_000_000_000L;n++) {service.persistAll(batches.get(n));updates+=batches.get(n).size();}
    double s=(System.nanoTime()-t0)/1e9;
    long q=status("Questions")-q0,ins=status("Com_insert")-i0,upd=status("Com_update")-u0,rows=jdbc.queryForObject("SELECT COUNT(*) FROM orderbook",Long.class)-r0;
    System.out.printf("ORDER-BATCH-BENCH legacyEvery=%d batches=%d updates=%d secs=%.1f updates/s=%.0f newRows/s=%.0f statements/batch=%.1f insert/batch=%.1f update/batch=%.1f%n",
        Integer.getInteger("traderx.bench.legacy-every",1),n-1,updates,s,updates/s,rows/s,q/(double)(n-1),ins/(double)(n-1),upd/(double)(n-1));
  }
}
