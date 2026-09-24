package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import finos.traderx.messaging.Publisher;
import finos.traderx.tradeprocessor.model.*;
import finos.traderx.tradeprocessor.repository.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
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

@Tag("integration")
@Testcontainers
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=TradeIdentityPersistenceIT.Config.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class TradeIdentityPersistenceIT {
  @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.4")
      .withCommand("--lower_case_table_names=1")
      .withCreateContainerCmdModifier(cmd -> cmd.withName("traderx-ri06-identity-sql")
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
  @Autowired TradeRepository trades;
  @Autowired PositionRepository positions;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;
  Publisher<Trade> tradePublisher;
  Publisher<Position> positionPublisher;
  @SuppressWarnings("unchecked") @BeforeEach void prepare() throws Exception {
    trades.deleteAll(); positions.deleteAll(); orders.deleteAll();
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
    tradePublisher=mock(Publisher.class);positionPublisher=mock(Publisher.class);
  }
  TradeService service() {
    TradeService s=new TradeService(trades,positions,tradePublisher,positionPublisher,1,null,
                                  new TransactionTemplate(transactions));
    s.projectionWriteLock(new ProjectionWriteLock(jdbc));s.runRegistry(new RunRegistry(jdbc));return s;
  }
  TradeOrder event(String id, String source, int quantity) {
    TradeOrder e=new TradeOrder(id,1001,"IBM",TradeSide.Buy,quantity);
    e.setPrice(new BigDecimal("136.250000"));e.setSourceOrderId(source);return e;
  }
  @Test void restartDuplicateAndProvenanceConflict() throws Exception {
    service().processTrade(event("1-B","old-1",100));
    service().processTrade(event("1-B","old-1",100));
    var error=assertThrows(IllegalArgumentException.class,
        () -> service().processTrade(event("1-B","fresh-1",100)));
    assertTrue(error.getMessage().contains("TRADE_ID_CONFLICT"));
    assertEquals("old-1",trades.findById("1-B").orElseThrow().getSourceOrderId());
    assertEquals(100,positions.findByAccountIdAndSecurity(1001,"IBM").getQuantity());
    verify(tradePublisher,times(1)).publish(anyString(),any());
    verify(positionPublisher,times(1)).publish(anyString(),any());
  }
  @Test void batchConflictRollsBackAndNeverPublishes() throws Exception {
    service().processTrade(event("1-B","old-1",100));
    clearInvocations(tradePublisher,positionPublisher);
    assertThrows(IllegalArgumentException.class,() -> service().processTrades(List.of(
        event("2-B","old-2",100),event("1-B","fresh-1",100))));
    assertFalse(trades.existsById("2-B"));
    assertEquals(100,positions.findByAccountIdAndSecurity(1001,"IBM").getQuantity());
    verifyNoInteractions(tradePublisher,positionPublisher);
  }
  @Test void identicalWithinBatchDedupsButConflictingWithinBatchRefuses() {
    assertThrows(IllegalArgumentException.class,() -> service().processTrades(List.of(
        event("1-B","old-1",100),event("1-B","old-1",200))));
    assertEquals(0,trades.count());assertEquals(0,positions.count());
    verifyNoInteractions(tradePublisher,positionPublisher);
    assertEquals(1,service().processTrades(List.of(event("1-B","old-1",100),
                                                 event("1-B","old-1",100))).size());
    assertEquals(100,positions.findByAccountIdAndSecurity(1001,"IBM").getQuantity());
  }
  @Test void outerTransactionRollbackCannotLeakPublication() {
    new TransactionTemplate(transactions).execute(status -> {
      service().processTrades(List.of(event("1-B","old-1",100),event("2-B","old-2",100)));
      status.setRollbackOnly();return null;
    });
    assertEquals(0,trades.count());assertEquals(0,positions.count());
    verifyNoInteractions(tradePublisher,positionPublisher);
  }
  @Test void concurrentServicesBookOnce() throws Exception {
    ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
    try {
      var a=pool.submit(() -> {start.await();return service().processTrade(event("1-B","old-1",100));});
      var b=pool.submit(() -> {start.await();return service().processTrade(event("1-B","old-1",100));});
      start.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
      assertEquals(1,trades.count());
      assertEquals(100,positions.findByAccountIdAndSecurity(1001,"IBM").getQuantity());
      verify(tradePublisher,times(1)).publish(anyString(),any());
    } finally {pool.shutdownNow();}
  }
  @Test void changedEconomicsRefusedButMutableStateDoesNotBreakReplay() {
    service().processTrade(event("1-B","old-1",100));
    Trade old=trades.findById("1-B").orElseThrow();old.setState(TradeState.Settled);trades.save(old);
    assertEquals(TradeState.Settled,service().processTrade(event("1-B","old-1",100)).getTrade().getState());
    assertThrows(IllegalArgumentException.class,() -> service().processTrade(event("1-B","old-1",200)));
    var price=event("1-B","old-1",100);price.setPrice(new BigDecimal("136.250001"));
    assertThrows(IllegalArgumentException.class,() -> service().processTrade(price));
  }

  void register(String scope,String epoch) {
    jdbc.update("INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,descriptor_hash,phase) VALUES (?,?,'epoch-v1',?,'ACTIVE')",scope,epoch,"a".repeat(64));
  }
  TradeOrder managed(String scope,String epoch,long seq,int quantity) {
    TradeOrder e=event("e1-"+epoch+"-"+seq+"-B",epoch+"-1",quantity);
    e.setProjectionScope(scope);e.setClusterEpoch(epoch);e.setEventIdScheme("epoch-v1");
    e.setRunDescriptorHash("a".repeat(64));e.setConsensusSequence(seq+100);return e;
  }
  @Test void freshEpochSeparatesPositionsAndHistoricalIdsAndLateLegacy() throws Exception {
    service().processTrade(event("1-B","old-1",100));register("fresh-scope","fresh");
    service().processTrade(managed("fresh-scope","fresh",1,200));
    assertEquals(2,trades.count());
    verify(tradePublisher).publish(eq("/v2/projections/fresh-scope/accounts/1001/trades"),any());
    verify(positionPublisher).publish(eq("/v2/projections/fresh-scope/accounts/1001/positions"),any());
    verify(tradePublisher).publish(eq("/accounts/1001/trades"),any());
    assertEquals(100,positions.findByAccountIdAndSecurity(1001,"IBM").getQuantity());
    assertEquals(200,positions.findByProjectionScopeAndAccountIdAndSecurity("fresh-scope",1001,"IBM").getQuantity());
    service().processTrade(managed("fresh-scope","fresh",1,200));
    service().processTrade(event("2-B","old-2",10));
    assertEquals(110,positions.findByAccountIdAndSecurity(1001,"IBM").getQuantity());
    assertEquals(200,positions.findByProjectionScopeAndAccountIdAndSecurity("fresh-scope",1001,"IBM").getQuantity());
    jdbc.update("UPDATE projection_runs SET phase='SEALED' WHERE projection_scope='legacy-unknown'");
    service().processTrade(event("1-B","old-1",100)); // Original bytes still dedup in sealed scope.
    assertThrows(IllegalStateException.class,() -> service().processTrade(event("3-B","old-3",10)));
    assertEquals(3,trades.count());
    assertEquals("old-1",trades.findById("1-B").orElseThrow().getSourceOrderId());
  }
  @Test void descriptorOrKeyMismatchCannotTouchProjection() {
    register("fresh-scope","fresh");
    var e=managed("fresh-scope","fresh",1,100);e.setRunDescriptorHash("b".repeat(64));
    assertThrows(IllegalArgumentException.class,() -> service().processTrade(e));
    var wrong=managed("fresh-scope","fresh",1,100);wrong.setSourceOrderId("old-1");
    assertThrows(IllegalArgumentException.class,() -> service().processTrade(wrong));
    jdbc.update("UPDATE projection_runs SET phase='PREPARED' WHERE projection_scope='fresh-scope'");
    assertThrows(IllegalStateException.class,() -> service().processTrade(managed("fresh-scope","fresh",1,100)));
    assertEquals(0,trades.count());assertEquals(0,positions.count());
    verifyNoInteractions(tradePublisher,positionPublisher);
  }
  @Autowired OrderRepository orders;
  OrderUpdate order(long sequence,int ordinal,int remaining) {
    var e=new OrderUpdate();e.setId("fresh-1");e.setAccountId(1001);e.setSecurity("IBM");e.setSide("Buy");
    e.setQuantity(100);e.setRemainingQuantity(remaining);e.setLimitPrice(new BigDecimal("136.25"));
    e.setStatus("NEW");e.setCreatedAt(1000L);e.setUpdatedAt(2000L);
    e.setProjectionScope("fresh-scope");e.setClusterEpoch("fresh");e.setEventIdScheme("epoch-v1");
    e.setRunDescriptorHash("a".repeat(64));e.setConsensusSequence(sequence);e.setOutputOrdinal(ordinal);
    return e;
  }
  @Test void orderProjectionRejectsConflictsAndIgnoresLateUpdatesAtCommonBoundary() {
    orders.deleteAll();register("fresh-scope","fresh");
    var service=new OrderProjectionService(orders,new RunRegistry(jdbc),new ProjectionWriteLock(jdbc),
                                          new TransactionTemplate(transactions));
    service.persist(order(101,0,100));service.persist(order(101,1,50));
    service.persist(order(101,0,100)); // Earlier output from the same consensus command.
    assertEquals(50,orders.findById("fresh-1").orElseThrow().getRemainingQuantity());
    service.persist(order(101,1,50));
    assertThrows(IllegalArgumentException.class,() -> service.persist(order(101,1,49)));
    assertEquals(50,orders.findById("fresh-1").orElseThrow().getRemainingQuantity());
    assertEquals(2L,jdbc.queryForObject("SELECT order_updates FROM projection_runs WHERE projection_scope='fresh-scope'",Long.class));
  }
  @Test void actualRetainedSchemaMigrationIsRepeatableAndPreservesHistoricalRows() throws Exception {
    var migration=java.nio.file.Files.readString(java.nio.file.Path.of("../postgres-database-replacement/mariadb-migrations/ri06.sql"));
    var initial=java.nio.file.Files.readString(java.nio.file.Path.of("../postgres-database-replacement/mariadb-init/initialSchema.sql"));
    String legacy=initial.substring(0,initial.indexOf("-- RI-06 additive retained-projection migration"));
    try(var connection=java.sql.DriverManager.getConnection(DB.getJdbcUrl(),"root",DB.getPassword())) {
      try(var statement=connection.createStatement()) {statement.execute("CREATE DATABASE ri06_retained_fixture");}
      connection.setCatalog("ri06_retained_fixture");
      var isolated=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
      try {
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ByteArrayResource(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var oldTrades=isolated.queryForList("SELECT id,accountid,security,quantity,price FROM trades ORDER BY id");
        var oldPositions=isolated.queryForList("SELECT accountid,security,quantity,averagecostbasis FROM positions ORDER BY accountid,security");
        assertFalse(oldTrades.isEmpty());assertFalse(oldPositions.isEmpty());
        // Interrupt after the key-shape migration, before adding trade/order scope columns.
        String first=migration.substring(0,migration.indexOf("ALTER TABLE trades"));
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ByteArrayResource(first.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for(int retry=0;retry<2;retry++) {
          org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
            new org.springframework.core.io.ByteArrayResource(migration.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          assertEquals(oldTrades,isolated.queryForList("SELECT id,accountid,security,quantity,price FROM trades ORDER BY id"));
          assertEquals(oldPositions,isolated.queryForList("SELECT accountid,security,quantity,averagecostbasis FROM positions ORDER BY accountid,security"));
          assertEquals(oldPositions.size(),isolated.queryForObject("SELECT count(*) FROM positions WHERE projectionscope='legacy-unknown'",Integer.class));
        }
        var firstPosition=oldPositions.get(0);
        isolated.update("INSERT INTO positions(projectionscope,accountid,security,quantity,averagecostbasis) VALUES ('fresh-fixture',?,?,17,42)",firstPosition.get("accountid"),firstPosition.get("security"));
        assertEquals(oldPositions,isolated.queryForList("SELECT accountid,security,quantity,averagecostbasis FROM positions WHERE projectionscope='legacy-unknown' ORDER BY accountid,security"));
        assertEquals(1,isolated.queryForObject("SELECT count(*) FROM positions WHERE projectionscope='fresh-fixture'",Integer.class));
      } finally {try(var statement=connection.createStatement()) {statement.execute("DROP DATABASE ri06_retained_fixture");}}
    }
  }
  @Test void reconciliationCheckpointsNeverFollowTheActiveScopeIntoAnotherRun() {
    register("one","one");register("two","two");var registry=new RunRegistry(jdbc);
    assertArrayEquals(new long[]{0,0,0,0},registry.recon("one"));
    registry.saveRecon("one",12,4,1,2);
    assertArrayEquals(new long[]{0,0,0,0},registry.recon("two"));
    registry.saveRecon("two",2,2,0,0);
    assertArrayEquals(new long[]{12,4,1,2},registry.recon("one"));
    assertArrayEquals(new long[]{2,2,0,0},registry.recon("two"));
  }

  static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
  String freshDescriptor() {return "{\"schema\":\"traderx.run.v1\",\"epoch\":\"freshnew\",\"eventIdScheme\":\"epoch-v1\",\"storageLineage\":\"fresh-storage\",\"projectionScope\":\"fresh-new\",\"adoptionEvidenceSha256\":null}";}
  String descriptorHash(String raw) throws Exception {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
  final class TransitionPeer implements RunPeerClient {
    int oldPhase=2,newPhase=0;String failOnce;boolean wrongFreshHash;String freshHash;
    com.fasterxml.jackson.databind.node.ObjectNode replay;
    TransitionPeer() throws Exception {freshHash=descriptorHash(freshDescriptor());}
    public com.fasterxml.jackson.databind.JsonNode status(String endpoint) {
      boolean old="old".equals(endpoint);String scope=old?"old-scope":"fresh-new";
      String hash=old?"a".repeat(64):wrongFreshHash?"b".repeat(64):freshHash;int phase=old?oldPhase:newPhase;
      var node=JSON.createObjectNode().put("descriptorHash",hash).put("projectionScope",scope).put("runPhase",phase).put("frozenRunSequence",old&&phase==3?150:0);
      node.putArray("members").add(JSON.createObjectNode().put("started",true).put("runProtocol",1)
          .put("runDescriptorHash",hash).put("projectionScope",scope).put("runPhase",phase).put("trades",old?1:0));return node;
    }
    public void control(String endpoint,String hash,String operation) {
      if("old".equals(endpoint)) {assertEquals("a".repeat(64),hash);if("freeze".equals(operation)) oldPhase=3;}
      else {assertEquals(freshHash,hash);if("declare".equals(operation) && newPhase==0) newPhase=1;else if("activate".equals(operation)) newPhase=2;}
      if(operation.equals(failOnce)) {failOnce=null;throw new IllegalStateException("simulated lost response after committed "+operation);}
    }
    public com.fasterxml.jackson.databind.JsonNode projectionEvents(String endpoint) {return replay.deepCopy();}
  }
  ProjectionTransitionService transitionService(TransitionPeer peer) {
    return new ProjectionTransitionService(jdbc,new ProjectionWriteLock(jdbc),new RunRegistry(jdbc),trades,orders,positions,peer,transactions);
  }
  TransitionPeer transitionFixture() throws Exception {
    register("old-scope","old");jdbc.update("UPDATE projection_active SET projection_scope='old-scope' WHERE singleton_id=1");
    TradeOrder trade=managed("old-scope","old",1,100);service().processTrade(trade);
    OrderUpdate order=order(2,0,0);order.setId("old-1");order.setProjectionScope("old-scope");order.setClusterEpoch("old");order.setStatus("FILLED");
    new OrderProjectionService(orders,new RunRegistry(jdbc),new ProjectionWriteLock(jdbc),new TransactionTemplate(transactions)).persist(order);
    var peer=new TransitionPeer();peer.replay=JSON.createObjectNode().put("descriptorHash","a".repeat(64)).put("projectionScope","old-scope")
        .put("frozenSequence",150).put("replayedMessages",12).put("replayedAppliedSeq",150).put("shadowTradeCounter",1);
    var events=peer.replay.putArray("events");
    events.add(JSON.createObjectNode().put("type","TradeOrder").set("payload",JSON.valueToTree(trade)));
    events.add(JSON.createObjectNode().put("type","OrderUpdate").set("payload",JSON.valueToTree(order)));
    return peer;
  }
  @Test void interruptedTransitionResumesEveryDurableBoundaryWithoutRebindingOrLosingHistory() throws Exception {
    var peer=transitionFixture();var before=jdbc.queryForList("SELECT * FROM trades WHERE projectionscope='old-scope'");
    var beforePositions=jdbc.queryForList("SELECT * FROM positions WHERE projectionscope='old-scope'");
    var first=transitionService(peer);peer.failOnce="declare";
    assertThrows(IllegalStateException.class,()->first.prepare("migration","old-scope",freshDescriptor(),"new"));
    assertEquals("PREPARED",transitionService(peer).state("migration").get("phase"));assertEquals(1,peer.newPhase);
    assertEquals("old-scope",new RunRegistry(jdbc).activeScope());
    transitionService(peer).prepare("migration","old-scope",freshDescriptor(),"new");
    peer.failOnce="freeze";
    assertThrows(IllegalStateException.class,()->transitionService(peer).freeze("migration","old"));
    assertEquals(3,peer.oldPhase);assertEquals("PREPARED",transitionService(peer).state("migration").get("phase"));
    assertEquals("FROZEN",transitionService(peer).freeze("migration","old").get("phase"));
    assertEquals("VERIFIED",transitionService(peer).verify("migration","old","new").get("phase"));
    assertEquals("SEALED",new RunRegistry(jdbc).scope("old-scope").get("phase"));
    assertEquals("old-scope",new RunRegistry(jdbc).activeScope());assertEquals(1,peer.newPhase);
    assertThrows(IllegalStateException.class,()->transitionService(peer).activationReady(peer.freshHash));
    assertEquals("SELECTED",transitionService(peer).select("migration","old","new").get("phase"));
    assertEquals(peer.freshHash,transitionService(peer).activationReady(peer.freshHash).get("descriptor_hash"));
    assertEquals("fresh-new",new RunRegistry(jdbc).activeScope());assertEquals(1,peer.newPhase,"selection does not activate the core");
    assertEquals("SELECTED",transitionService(peer).select("migration","old","new").get("phase"));
    peer.failOnce="activate";
    assertThrows(IllegalStateException.class,()->transitionService(peer).activate("migration","new"));
    assertEquals(2,peer.newPhase);assertEquals("SELECTED",transitionService(peer).state("migration").get("phase"));
    assertEquals("COMPLETE",transitionService(peer).activate("migration","new").get("phase"));
    assertEquals("COMPLETE",transitionService(peer).activate("migration","new").get("phase"));
    var fresh=managed("fresh-new","freshnew",1,200);fresh.setRunDescriptorHash(peer.freshHash);service().processTrade(fresh);service().processTrade(fresh);
    service().processTrade(managed("old-scope","old",1,100));
    assertThrows(IllegalStateException.class,()->service().processTrade(managed("old-scope","old",2,1)));
    assertEquals(before,jdbc.queryForList("SELECT * FROM trades WHERE projectionscope='old-scope'"));
    assertEquals(beforePositions,jdbc.queryForList("SELECT * FROM positions WHERE projectionscope='old-scope'"));
    assertEquals(200,positions.findByProjectionScopeAndAccountIdAndSecurity("fresh-new",1001,"IBM").getQuantity());
    assertEquals(2,trades.count());assertNotNull(transitionService(peer).state("migration").get("witness_hash"));
  }
  @Test void transitionCannotActivateOnMissingProjectionOrChangedDescriptor() throws Exception {
    var peer=transitionFixture();var service=transitionService(peer);service.prepare("migration","old-scope",freshDescriptor(),"new");
    assertThrows(IllegalStateException.class,()->service.activate("migration","new"));assertEquals(1,peer.newPhase);
    service.freeze("migration","old");jdbc.update("UPDATE positions SET quantity=99 WHERE projectionscope='old-scope'");
    assertEquals("RUN_POSITION_QUANTITY_MISMATCH",assertThrows(IllegalStateException.class,()->service.verify("migration","old","new")).getMessage());
    assertEquals("FROZEN",service.state("migration").get("phase"));assertEquals("old-scope",new RunRegistry(jdbc).activeScope());
    jdbc.update("UPDATE positions SET quantity=100 WHERE projectionscope='old-scope'");peer.wrongFreshHash=true;
    assertEquals("RUN_CONSUMER_DESCRIPTOR_DISAGREEMENT",assertThrows(IllegalStateException.class,()->service.verify("migration","old","new")).getMessage());assertEquals(1,peer.newPhase);
    peer.wrongFreshHash=false;
    var payload=(com.fasterxml.jackson.databind.node.ObjectNode)peer.replay.path("events").get(0).get("payload");
    payload.put("consensusSequence",151);
    assertEquals("RUN_EVENT_AFTER_FROZEN_BOUNDARY",assertThrows(IllegalStateException.class,()->service.verify("migration","old","new")).getMessage());
    payload.put("consensusSequence",101);peer.replay.putArray("events");
    assertThrows(IllegalStateException.class,()->service.verify("migration","old","new"));
    assertThrows(IllegalStateException.class,()->service.prepare("migration","old-scope",freshDescriptor().replace("fresh-storage","other-storage"),"new"));
    assertEquals("FROZEN",service.state("migration").get("phase"));
  }
  @Test void unattributedHistoricalOrderPrefixIsReservedWithoutRelabelling() throws Exception {
    jdbc.update("UPDATE projection_runs SET cluster_epoch='different',descriptor_hash=? WHERE projection_scope='legacy-unknown'","c".repeat(64));
    var oldOrder=order(1,0,100);oldOrder.setId("shared-1");oldOrder.setProjectionScope("legacy-unknown");
    oldOrder.setClusterEpoch(null);oldOrder.setEventIdScheme(null);oldOrder.setRunDescriptorHash(null);oldOrder.setConsensusSequence(null);oldOrder.setOutputOrdinal(null);
    orders.saveAndFlush(finos.traderx.tradeprocessor.OrderFeedHandler.toRow(oldOrder));
    var before=jdbc.queryForList("SELECT * FROM orderbook");var peer=new TransitionPeer();
    assertEquals("RUN_ORDER_NAMESPACE_ALREADY_RETAINED",assertThrows(IllegalStateException.class,()->transitionService(peer).prepare("collision","legacy-unknown",freshDescriptor().replace("freshnew","shared"),"new")).getMessage());
    assertEquals(before,jdbc.queryForList("SELECT * FROM orderbook"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM projection_transitions",Integer.class));
  }
  @Test void globalNamespaceIndexRefusesCrossSchemeReuseAndInterruptedUpgradePreservesRows() throws Exception {
    jdbc.execute("DROP INDEX uq_run_order_namespace ON projection_runs");
    jdbc.update("UPDATE projection_schema SET version=1");
    jdbc.update("UPDATE projection_runs SET cluster_epoch='shared' WHERE projection_scope='legacy-unknown'");
    jdbc.update("INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,phase) VALUES ('collision','SHARED','epoch-v1','PREPARED')");
    var before=jdbc.queryForList("SELECT * FROM projection_runs ORDER BY projection_scope");
    try {
      assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class,()->applyMigration());
      assertEquals(before,jdbc.queryForList("SELECT * FROM projection_runs ORDER BY projection_scope"));
      assertEquals(1,jdbc.queryForObject("SELECT version FROM projection_schema",Integer.class));
    } finally {jdbc.update("DELETE FROM projection_runs WHERE projection_scope='collision'");applyMigration();}
    applyMigration();assertEquals(2,jdbc.queryForObject("SELECT version FROM projection_schema",Integer.class));
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,phase) VALUES ('collision','SHARED','epoch-v1','PREPARED')"));
  }
  void applyMigration() throws Exception {
    try(var c=jdbc.getDataSource().getConnection()) {org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.FileSystemResource("../postgres-database-replacement/mariadb-migrations/ri06.sql"));}
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"shared","SHARED","sháred"})
  void legacyEpochCannotBeReusedByFreshSchemeBeforeAnyFreeze(String legacyEpoch) throws Exception {
    jdbc.update("UPDATE projection_runs SET cluster_epoch=?,descriptor_hash=? WHERE projection_scope='legacy-unknown'",legacyEpoch,"c".repeat(64));
    var oldOrder=order(1,0,100);oldOrder.setId(legacyEpoch+"-1");oldOrder.setProjectionScope("legacy-unknown");
    oldOrder.setClusterEpoch(null);oldOrder.setEventIdScheme(null);oldOrder.setRunDescriptorHash(null);oldOrder.setConsensusSequence(null);oldOrder.setOutputOrdinal(null);
    orders.saveAndFlush(finos.traderx.tradeprocessor.OrderFeedHandler.toRow(oldOrder));
    var before=jdbc.queryForList("SELECT * FROM orderbook");var peer=new TransitionPeer();
    var refusal=assertThrows(IllegalStateException.class,()->transitionService(peer).prepare("collision","legacy-unknown",freshDescriptor().replace("freshnew","shared"),"new"));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM projection_transitions",Integer.class),"refuse BEFORE durable prepare or any peer action");
    assertEquals("RUN_EPOCH_NAMESPACE_ALREADY_RETAINED",refusal.getMessage());
    assertEquals(2,peer.oldPhase);assertEquals(0,peer.newPhase);assertEquals("legacy-unknown",new RunRegistry(jdbc).activeScope());
    assertEquals(before,jdbc.queryForList("SELECT * FROM orderbook"));
  }
  @Test void alreadyActiveFreshCoreIsRefusedBeforeProjectionSelection() throws Exception {
    var peer=transitionFixture();var service=transitionService(peer);service.prepare("migration","old-scope",freshDescriptor(),"new");
    service.freeze("migration","old");service.verify("migration","old","new");peer.newPhase=2;
    assertThrows(IllegalStateException.class,()->service.select("migration","old","new"));
    assertEquals("old-scope",new RunRegistry(jdbc).activeScope());assertEquals("VERIFIED",service.state("migration").get("phase"));
  }

}
