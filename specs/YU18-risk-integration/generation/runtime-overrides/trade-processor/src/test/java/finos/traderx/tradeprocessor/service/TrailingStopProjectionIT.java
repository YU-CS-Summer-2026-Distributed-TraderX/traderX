package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.*;

import finos.traderx.tradeprocessor.model.*;
import finos.traderx.tradeprocessor.repository.*;
import java.math.BigDecimal;
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
 * F3 (RI-07), read model: a trailing stop's ratchet updates reach orderbook.stopprice as the
 * CURRENT level (FR-OT33), on real MariaDB with the shipped RI-06 migration. Several ratchets can
 * be emitted by ONE command (a sweep through several levels), so they share a consensus sequence
 * and differ by output ordinal; a redelivered earlier ratchet must never rewind the level.
 */
@Tag("integration")
@Testcontainers
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=TrailingStopProjectionIT.Config.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class TrailingStopProjectionIT {
  @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.4")
      .withCommand("--lower_case_table_names=1")
      .withCreateContainerCmdModifier(cmd -> cmd.withName("traderx-f3-projection-sql")
          .getHostConfig().withMemory(536870912L).withNanoCPUs(500000000L));
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", DB::getUsername);
    r.add("spring.datasource.password", DB::getPassword);
    r.add("spring.datasource.driverClassName", () -> "org.mariadb.jdbc.Driver");
    r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MariaDBDialect");
  }
  @Configuration @EnableAutoConfiguration
  @EntityScan(basePackageClasses=Trade.class)
  @EnableJpaRepositories(basePackageClasses=TradeRepository.class)
  static class Config {}
  @Autowired OrderRepository orders;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach void prepare() throws Exception {
    orders.deleteAll();
    jdbc.execute("CREATE TABLE IF NOT EXISTS PROJECTION_WRITE_LOCK (LOCK_ID INTEGER PRIMARY KEY)");
    jdbc.update("INSERT IGNORE INTO PROJECTION_WRITE_LOCK VALUES (1)");
    try (var c=jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
          new org.springframework.core.io.FileSystemResource("../postgres-database-replacement/mariadb-migrations/ri06.sql"));
    }
    jdbc.update("DELETE FROM projection_transitions");
    jdbc.update("DELETE FROM projection_recon");
    jdbc.update("UPDATE projection_active SET projection_scope='legacy-unknown' WHERE singleton_id=1");
    jdbc.update("DELETE FROM projection_runs WHERE projection_scope<>'legacy-unknown'");
    jdbc.update("UPDATE projection_runs SET phase='LEGACY',cluster_epoch=NULL,descriptor_hash=NULL,descriptor_json=NULL,storage_lineage=NULL WHERE projection_scope='legacy-unknown'");
  }

  OrderProjectionService projection() {
    return new OrderProjectionService(orders,new RunRegistry(jdbc),new ProjectionWriteLock(jdbc),
        new TransactionTemplate(transactions));
  }

  /** The update the bridge publishes for a pending sell trailing stop at {@code stop}. */
  OrderUpdate trail(String id, long updatedAt, String stop, boolean triggered, String status) {
    var e=new OrderUpdate();e.setId(id);e.setAccountId(1001);e.setSecurity("IBM");e.setSide("Sell");
    e.setQuantity(2);e.setRemainingQuantity("FILLED".equals(status) ? 0 : 2);e.setLimitPrice(BigDecimal.ZERO);
    e.setStatus(status);e.setOrderType("TRAILING_STOP");e.setTimeInForce("GTC");
    e.setTrailAmount(new BigDecimal("2.000000"));e.setStopPrice(new BigDecimal(stop));e.setTriggered(triggered);
    e.setCreatedAt(1000L);e.setUpdatedAt(updatedAt);
    return e;
  }

  OrderUpdate managed(OrderUpdate e, long sequence, int ordinal) {
    e.setProjectionScope("fresh-scope");e.setClusterEpoch("fresh");e.setEventIdScheme("epoch-v1");
    e.setRunDescriptorHash("a".repeat(64));e.setConsensusSequence(sequence);e.setOutputOrdinal(ordinal);
    return e;
  }

  BigDecimal stop(String id) {
    return orders.findById(id).orElseThrow().getStopPrice();
  }

  @Test void managedScopeShowsTheLatestRatchetAndNeverRewinds() {
    jdbc.update("INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,descriptor_hash,phase) VALUES ('fresh-scope','fresh','epoch-v1',?,'ACTIVE')","a".repeat(64));
    var p=projection();
    p.persist(managed(trail("fresh-1",2000,"98.000000",false,"PENDING_TRIGGER"),101,0));  // admission
    assertEquals(0,new BigDecimal("98.000000").compareTo(stop("fresh-1")));
    p.persist(managed(trail("fresh-1",3000,"101.000000",false,"PENDING_TRIGGER"),105,2)); // ratchet
    p.persist(managed(trail("fresh-1",3000,"102.500000",false,"PENDING_TRIGGER"),105,5)); // same command, next level
    assertEquals(0,new BigDecimal("102.500000").compareTo(stop("fresh-1")),"two ratchets in one command");
    p.persist(managed(trail("fresh-1",3000,"101.000000",false,"PENDING_TRIGGER"),105,2)); // redelivered
    assertEquals(0,new BigDecimal("102.500000").compareTo(stop("fresh-1")),"a late ratchet cannot rewind");
    p.persist(managed(trail("fresh-1",4000,"102.500000",true,"FILLED"),109,1));          // trigger + fill
    var row=orders.findById("fresh-1").orElseThrow();
    assertEquals("FILLED",row.getStatus());
    assertEquals(Boolean.TRUE,row.getTriggered());
    assertEquals(0,new BigDecimal("102.500000").compareTo(row.getStopPrice()),"level at trigger kept");
  }

  @Test void legacyScopeShowsEachRatchetInArrivalOrder() {
    var p=projection();
    p.persist(trail("1-7",2000,"98.000000",false,"PENDING_TRIGGER"));
    p.persist(trail("1-7",3000,"101.000000",false,"PENDING_TRIGGER"));
    assertEquals(0,new BigDecimal("101.000000").compareTo(stop("1-7")));
    p.persist(trail("1-7",3500,"102.500000",false,"PENDING_TRIGGER"));
    var row=orders.findById("1-7").orElseThrow();
    assertEquals(0,new BigDecimal("102.500000").compareTo(row.getStopPrice()));
    assertEquals("PENDING_TRIGGER",row.getStatus());
    assertEquals(3500L,row.getUpdatedAt().getTime(),"updatedat follows the ratchet");
  }
}
