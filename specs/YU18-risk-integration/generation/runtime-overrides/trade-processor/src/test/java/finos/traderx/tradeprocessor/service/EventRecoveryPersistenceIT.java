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
@ContextConfiguration(classes=EventRecoveryPersistenceIT.Config.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class EventRecoveryPersistenceIT {
  @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.4")
      .withCommand("--lower_case_table_names=1")
      .withCreateContainerCmdModifier(cmd -> cmd.withName("traderx-o1-controls-sql")
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
    try(var c=jdbc.getDataSource().getConnection()) {org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
      new org.springframework.core.io.FileSystemResource("../postgres-database-replacement/mariadb-migrations/ri06-event-recovery.sql"));}
    jdbc.update("DELETE FROM projection_recovery");
    jdbc.update("DELETE FROM projection_transitions");
    jdbc.update("DELETE FROM projection_recon");
    jdbc.update("UPDATE projection_active SET projection_scope='legacy-unknown' WHERE singleton_id=1");
    jdbc.update("DELETE FROM projection_runs WHERE projection_scope<>'legacy-unknown'");
    jdbc.update("UPDATE projection_runs SET phase='LEGACY',cluster_epoch=NULL,descriptor_hash=NULL,descriptor_json=NULL,storage_lineage=NULL WHERE projection_scope='legacy-unknown'");
    tradePublisher=mock(Publisher.class);positionPublisher=mock(Publisher.class);source();
  }
  @Autowired OrderRepository orders;
  static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
  com.fasterxml.jackson.databind.node.ObjectNode witness;
  Runnable fetchAction=()->{};
  TradeService service() {
    TradeService s=new TradeService(trades,positions,tradePublisher,positionPublisher,1,null,new TransactionTemplate(transactions));
    s.projectionWriteLock(new ProjectionWriteLock(jdbc));s.runRegistry(new RunRegistry(jdbc));return s;
  }
  OrderProjectionService orderService() {return new OrderProjectionService(orders,new RunRegistry(jdbc),new ProjectionWriteLock(jdbc),new TransactionTemplate(transactions));}
  EventRecoveryService recovery() {
    RunPeerClient peer=new RunPeerClient() {
      public com.fasterxml.jackson.databind.JsonNode status(String e){throw new UnsupportedOperationException();}
      public void control(String e,String h,String op){throw new UnsupportedOperationException();}
      public com.fasterxml.jackson.databind.JsonNode projectionEvents(String e){throw new UnsupportedOperationException();}
      public com.fasterxml.jackson.databind.JsonNode recoveryEvents(String e){fetchAction.run();return witness;}
    };
    return new EventRecoveryService(jdbc,new RunRegistry(jdbc),new ProjectionWriteLock(jdbc),peer,trades,orders,positions,service(),orderService(),transactions,100);
  }
  TradeOrder trade(int id,long seq,String price) {
    var t=new TradeOrder("e1-fresh-"+id+"-B",1001,"IBM",TradeSide.Buy,10);
    t.setPrice(new BigDecimal(price));t.setSourceOrderId("fresh-1");t.setProjectionScope("fresh-scope");
    t.setClusterEpoch("fresh");t.setEventIdScheme("epoch-v1");t.setRunDescriptorHash("a".repeat(64));t.setConsensusSequence(seq);return t;
  }
  OrderUpdate order(long seq,int remaining) {
    var o=new OrderUpdate();o.setId("fresh-1");o.setAccountId(1001);o.setSecurity("IBM");o.setSide("Buy");
    o.setQuantity(20);o.setRemainingQuantity(remaining);o.setLimitPrice(new BigDecimal("200"));o.setStatus(remaining==0?"FILLED":"NEW");
    o.setCreatedAt(1000L);o.setUpdatedAt(1000L+seq);o.setProjectionScope("fresh-scope");o.setClusterEpoch("fresh");
    o.setEventIdScheme("epoch-v1");o.setRunDescriptorHash("a".repeat(64));o.setConsensusSequence(seq);o.setOutputOrdinal(0);return o;
  }
  void source() {
    jdbc.update("INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,descriptor_hash,phase) VALUES ('fresh-scope','fresh','epoch-v1',?,'ACTIVE')","a".repeat(64));
    jdbc.update("UPDATE projection_active SET projection_scope='fresh-scope' WHERE singleton_id=1");
    witness=JSON.createObjectNode().put("projectionScope","fresh-scope").put("descriptorHash","a".repeat(64)).put("completeSequence",5)
      .put("replayedAppliedSeq",5).put("replayedMessages",5).put("shadowTradeCounter",2).put("runPhase",2);
    var events=witness.putArray("events");
    events.add(JSON.createObjectNode().put("type","OrderUpdate").set("payload",JSON.valueToTree(order(1,20))));
    events.add(JSON.createObjectNode().put("type","TradeOrder").set("payload",JSON.valueToTree(trade(1,2,"100"))));
    events.add(JSON.createObjectNode().put("type","TradeOrder").set("payload",JSON.valueToTree(trade(2,4,"200"))));
    events.add(JSON.createObjectNode().put("type","OrderUpdate").set("payload",JSON.valueToTree(order(5,0))));
  }
  Object run(){return recovery().catchUp("fresh-scope","fixture");}
  @Test void interiorGapAndOutOfOrderUpdatesConvergeWithoutChangingRetainedTrades() {
    service().processTrade(trade(2,4,"200"));orderService().persist(order(5,0));orderService().persist(order(1,20));
    var before=jdbc.queryForList("SELECT * FROM trades WHERE id='e1-fresh-2-B'");
    run();assertEquals(2,trades.count());assertEquals(20,positions.findByProjectionScopeAndAccountIdAndSecurity("fresh-scope",1001,"IBM").getQuantity());
    assertEquals(0,new BigDecimal("150").compareTo(positions.findByProjectionScopeAndAccountIdAndSecurity("fresh-scope",1001,"IBM").getAverageCostBasis()));
    assertEquals(before,jdbc.queryForList("SELECT * FROM trades WHERE id='e1-fresh-2-B'"));
    assertEquals("FILLED",orders.findById("fresh-1").orElseThrow().getStatus());
    var all=jdbc.queryForList("SELECT * FROM trades ORDER BY id");var checkpoint=run();assertEquals(checkpoint,run());assertEquals(all,jdbc.queryForList("SELECT * FROM trades ORDER BY id"));
  }
  @Test void generatedConfigMapOrderRowRoundTripAndConflictControls() throws Exception {
    String initial=GeneratedDatabaseManifestTest.scripts().get("001-initialSchema.sql");
    var create=java.util.regex.Pattern.compile("CREATE TABLE orderbook \\(.*?\\);",java.util.regex.Pattern.DOTALL).matcher(initial);
    assertTrue(create.find());
    jdbc.execute("DROP TABLE orderbook");jdbc.execute(create.group());
    try(var connection=jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
        new org.springframework.core.io.FileSystemResource("../postgres-database-replacement/mariadb-migrations/ri06.sql"));
    }
    service().processTrade(trade(2,4,"200"));orderService().persist(order(5,0));
    var a=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.valueToTree(orders.findById("fresh-1").orElseThrow());
    var b=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.valueToTree(finos.traderx.tradeprocessor.OrderFeedHandler.toRow(order(5,0)));
    a.fieldNames().forEachRemaining(f->{if(!java.util.Objects.equals(a.get(f),b.get(f)))System.out.println("GENERATED_ROW_DIFF "+f+" retained="+a.get(f)+" source="+b.get(f));});
    var retained=jdbc.queryForList("SELECT * FROM orderbook");
    assertNotNull(run());assertEquals(retained,jdbc.queryForList("SELECT * FROM orderbook"));
    var state=retainedState();assertNotNull(run());assertEquals(state,retainedState());
    // Even a wire-only subsecond alteration cannot hide behind seconds storage: digest binds it.
    ((com.fasterxml.jackson.databind.node.ObjectNode)witness.path("events").get(3).path("payload")).put("updatedAt",1006);
    refusesWithoutMutation("RECOVERY_ORDER_HISTORY_CONFLICT_OR_AHEAD");
    ((com.fasterxml.jackson.databind.node.ObjectNode)witness.path("events").get(3).path("payload")).put("updatedAt",1005);
    for(String mutation:List.of("createdat=DATE_ADD(createdat,INTERVAL 1 SECOND)","updatedat=DATE_ADD(updatedat,INTERVAL 1 SECOND)","quantity=999","rundescriptorhash='wrong'")) {
      jdbc.update("UPDATE orderbook SET "+mutation+" WHERE orderid='fresh-1'");
      refusesWithoutMutation("RECOVERY_RETAINED_ORDER_ROW_CONFLICT");
      jdbc.update("DELETE FROM orderbook WHERE orderid='fresh-1'");orderService().persist(order(5,0));
    }
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints={0,3,6})
  void retainedOrderComparisonHonorsActualSqlTimestampPrecision(int precision) {
    jdbc.execute("ALTER TABLE orderbook MODIFY createdat DATETIME("+precision+"), MODIFY updatedat DATETIME("+precision+")");
    try {
      service().processTrade(trade(2,4,"200")); orderService().persist(order(5,0));
      var retained=jdbc.queryForList("SELECT * FROM orderbook");
      assertNotNull(run());
      assertEquals(retained,jdbc.queryForList("SELECT * FROM orderbook"));
      assertEquals(2,trades.count());
      var state=retainedState(); assertNotNull(run()); assertEquals(state,retainedState());
      // A stored timestamp difference that SQL CAN represent is a real conflict.
      jdbc.update("UPDATE orderbook SET updatedat=DATE_ADD(updatedat, INTERVAL "+(precision==0?1000000:1000)+" MICROSECOND)");
      refusesWithoutMutation("RECOVERY_RETAINED_ORDER_ROW_CONFLICT");
    } finally {
      jdbc.execute("ALTER TABLE orderbook MODIFY createdat DATETIME(6), MODIFY updatedat DATETIME(6)");
    }
  }
  @Test void checkpointFailureRollsBackEveryProjectionAndNotificationThenRetrySucceeds() {
    jdbc.execute("CREATE TRIGGER o1_fail_checkpoint BEFORE INSERT ON projection_recovery FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected checkpoint failure'");
    try {assertThrows(RuntimeException.class,this::run);}finally{jdbc.execute("DROP TRIGGER o1_fail_checkpoint");}
    assertEquals(0,trades.count());assertEquals(0,orders.count());assertEquals(0,positions.count());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM projection_recovery",Integer.class));verifyNoInteractions(tradePublisher,positionPublisher);
    run();assertEquals(2,trades.count());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM projection_recovery",Integer.class));
  }
  @Test void outerTransactionCrashBeforeCommitAndLostReplyAfterCommit() {
    assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(s->{run();throw new IllegalStateException("crash before commit");}));
    assertEquals(0,trades.count());verifyNoInteractions(tradePublisher,positionPublisher);
    var committed=run();assertEquals(committed,recovery().catchUp("fresh-scope","fixture"));assertEquals(2,trades.count());
  }
  @Test void conflictingTradeAndAlteredOrderRefuseWithoutCheckpoint() {
    service().processTrade(trade(1,2,"99"));assertThrows(IllegalArgumentException.class,this::run);
    assertEquals(1,trades.count());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM projection_recovery",Integer.class));
    trades.deleteAll();positions.deleteAll();orderService().persist(order(1,20));jdbc.update("UPDATE orderbook SET quantity=999 WHERE orderid='fresh-1'");
    assertThrows(IllegalStateException.class,this::run);assertEquals(0,trades.count());
  }
  @Test void missingDuplicateUnorderedAndWrongIdentitySourceRefuse() {
    var original=witness.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)witness.get("events")).remove(1);
    assertThrows(IllegalStateException.class,this::run);witness=original.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode)witness.get("events")).add(witness.path("events").get(1));assertThrows(IllegalStateException.class,this::run);
    witness=original.deepCopy();witness.put("descriptorHash","b".repeat(64));assertThrows(IllegalStateException.class,this::run);
    witness=original.deepCopy();witness.put("completeSequence",6);assertThrows(IllegalStateException.class,this::run);
    assertEquals(0,trades.count());assertEquals(0,positions.count());
  }
  @Test void frozenAndSelectedScopeBarriersRemainEffective() {
    jdbc.update("UPDATE projection_runs SET phase='DRAINING',frozen_seq=5 WHERE projection_scope='fresh-scope'");
    witness.put("runPhase",3).put("frozenSequence",5);run();
    jdbc.update("UPDATE projection_runs SET phase='SEALED' WHERE projection_scope='fresh-scope'");assertThrows(IllegalStateException.class,this::run);
    jdbc.update("UPDATE projection_runs SET phase='ACTIVE' WHERE projection_scope='fresh-scope'");witness.put("runPhase",2);
    fetchAction=()->jdbc.update("UPDATE projection_active SET projection_scope='legacy-unknown' WHERE singleton_id=1");
    assertThrows(IllegalStateException.class,this::run);
    assertEquals(2,trades.count());assertEquals(0,trades.findByProjectionScope("legacy-unknown").size());
  }

  java.util.Map<String,Object> retainedState() {
    var state=new java.util.LinkedHashMap<String,Object>();
    for(String table:List.of("trades","positions","orderbook","projection_runs","projection_recovery"))
      state.put(table,jdbc.queryForList("SELECT * FROM "+table));
    return state;
  }
  void refusesWithoutMutation(String reason) {
    var before=retainedState();clearInvocations(tradePublisher,positionPublisher);
    var error=assertThrows(IllegalStateException.class,()->{
      run();
      System.out.println("UNEXPECTED_RECOVERY_ACCEPTANCE before="+before+" after="+retainedState());
    });
    assertTrue(error.getMessage().contains(reason),error.getMessage());
    assertEquals(before,retainedState());verifyNoInteractions(tradePublisher,positionPublisher);
  }
  @Test void unexplainedSameKeyBalanceRefusesBeforeAnyRecoveryWrite() {
    service().processTrade(trade(2,4,"200"));
    jdbc.update("UPDATE positions SET quantity=17,averagecostbasis=999 WHERE projectionscope='fresh-scope'");
    refusesWithoutMutation("RECOVERY_RETAINED_POSITION_QUANTITY_CONFLICT");
  }
  @Test void missingPositionWithRetainedTradesRequiresReview() {
    service().processTrade(trade(2,4,"200"));positions.deleteAll();
    refusesWithoutMutation("RECOVERY_RETAINED_POSITION_MISSING");
  }
  @Test void initialBalanceOnFutureSourceKeyCannotBeAttributedToMissingTrades() {
    var p=new Position();p.setProjectionScope("fresh-scope");p.setAccountId(1001);p.setSecurity("IBM");
    p.setQuantity(7);p.setAverageCostBasis(new BigDecimal("99"));positions.save(p);
    refusesWithoutMutation("RECOVERY_RETAINED_POSITION_QUANTITY_CONFLICT");
  }
  @Test void tradeSourceOrderAccountSecurityAndSideMustAgree() {
    var original=witness.deepCopy();
    for(String field:List.of("accountId","security","side")) {
      witness=original.deepCopy();
      for(int index:List.of(0,3)) {
        var order=(com.fasterxml.jackson.databind.node.ObjectNode)witness.path("events").get(index).path("payload");
        if(field.equals("accountId"))order.put(field,2002);else order.put(field,field.equals("security")?"MSFT":"Sell");
      }
      refusesWithoutMutation("RECOVERY_TRADE_ORDER_PROVENANCE_CONFLICT");
    }
  }
  @Test void sourceOrderCannotChangeAccountAcrossLifecycleVersions() {
    ((com.fasterxml.jackson.databind.node.ObjectNode)witness.path("events").get(3).path("payload")).put("accountId",2002);
    refusesWithoutMutation("RECOVERY_SOURCE_ORDER_PROVENANCE_CONFLICT");
  }
  @Test void unattributedBasisOnFlatUnbookedPositionRefuses() {
    var p=new Position();p.setProjectionScope("fresh-scope");p.setAccountId(1001);p.setSecurity("IBM");
    p.setQuantity(0);p.setAverageCostBasis(new BigDecimal("99"));positions.save(p);
    refusesWithoutMutation("RECOVERY_UNATTRIBUTED_POSITION_BASIS");
  }
  @Test void economicallyEmptyPositionWithoutRetainedTradesCanRecover() {
    var p=new Position();p.setProjectionScope("fresh-scope");p.setAccountId(1001);p.setSecurity("IBM");
    p.setQuantity(0);p.setAverageCostBasis(BigDecimal.ZERO);positions.save(p);
    run();assertEquals(2,trades.count());
    assertEquals(20,positions.findByProjectionScopeAndAccountIdAndSecurity("fresh-scope",1001,"IBM").getQuantity());
  }
}
