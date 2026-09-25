package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.messaging.Publisher;
import finos.traderx.tradeprocessor.model.*;
import finos.traderx.tradeprocessor.repository.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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

/** FIXTURE level: real MariaDB, real services/worker; the source pages are synthetic (the member
 * paging itself is proven by AutomaticRecoveryLiveIT). Faults here are injected exceptions or
 * in-process pauses, not process deaths. */
@Tag("integration") @Testcontainers
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create","spring.sql.init.mode=never","logging.level.finos.traderx.tradeprocessor.service.AutomaticProjectionRecovery=DEBUG"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=AutomaticRecoveryPersistenceIT.Config.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AutomaticRecoveryPersistenceIT {
  @Container static final MariaDBContainer<?> DB=new MariaDBContainer<>("mariadb:11.4").withCommand("--lower_case_table_names=1")
      .withCreateContainerCmdModifier(c->c.withName("traderx-apr-controls-sql").getHostConfig().withMemory(536870912L).withNanoCPUs(500000000L));
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);
    r.add("spring.datasource.password",DB::getPassword);r.add("spring.datasource.driverClassName",()->"org.mariadb.jdbc.Driver");
    r.add("spring.jpa.database-platform",()->"org.hibernate.dialect.MariaDBDialect");
  }
  @Configuration @EnableAutoConfiguration @EntityScan(basePackageClasses=Trade.class)
  @EnableJpaRepositories(basePackageClasses=TradeRepository.class) static class Config {}
  @Autowired TradeRepository trades; @Autowired PositionRepository positions; @Autowired OrderRepository orders;
  @Autowired PlatformTransactionManager transactions; @Autowired JdbcTemplate jdbc;
  static final ObjectMapper JSON=new ObjectMapper();
  static final String HASH="a".repeat(64);
  Publisher<Trade> tradePublisher; Publisher<Position> positionPublisher;

  /** Synthetic source: (seq, wire) in log order, cut into whole-command pages like the member. */
  final List<Map.Entry<Long,String>> source=new ArrayList<>();
  long boundary; String refusal; String sourceHash=HASH;
  final RunPeerClient peer=new RunPeerClient() {
    public JsonNode status(String e){return JSON.createObjectNode().put("descriptorHash",sourceHash).put("projectionScope","fresh-scope");}
    public void control(String e,String h,String op){throw new UnsupportedOperationException();}
    public JsonNode projectionEvents(String e){throw new UnsupportedOperationException();}
    public JsonNode catchupEvents(String e,long after,int max){
      if(refusal!=null) throw new IllegalStateException(refusal);
      var r=JSON.createObjectNode().put("protocol",1).put("descriptorHash",sourceHash).put("projectionScope","fresh-scope")
        .put("afterSeq",after).put("boundarySeq",boundary);
      if(after==boundary) return r.put("idle",true);
      String d=AutomaticProjectionRecovery.GENESIS,afterDigest=null;long through=-1;var page=r.putArray("events");int n=0;
      for(int i=0;i<source.size();) {
        long seq=source.get(i).getKey();int j=i;while(j<source.size() && source.get(j).getKey()==seq) j++;
        if(seq>boundary) break;
        if(seq<=after) {for(int k=i;k<j;k++) d=AutomaticProjectionRecovery.chain(d,source.get(k).getValue());}
        else {
          if(afterDigest==null) afterDigest=d;
          if(n+(j-i)>max) {through=seq-1;break;}
          for(int k=i;k<j;k++) {d=AutomaticProjectionRecovery.chain(d,source.get(k).getValue());page.add(source.get(k).getValue());n++;}
        }
        i=j;
      }
      if(afterDigest==null) afterDigest=d;
      if(through<0) through=boundary;
      return r.put("afterDigest",afterDigest).put("throughSeq",through).put("throughDigest",d).put("complete",through==boundary);
    }
  };
  final Map<String,Runnable> hookActions=new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked") @BeforeEach void prepare() throws Exception {
    trades.deleteAll();positions.deleteAll();orders.deleteAll();
    jdbc.execute("CREATE TABLE IF NOT EXISTS PROJECTION_WRITE_LOCK (LOCK_ID INTEGER PRIMARY KEY)");
    jdbc.update("INSERT IGNORE INTO PROJECTION_WRITE_LOCK VALUES (1)");
    for(String m:List.of("ri06.sql","ri06-event-recovery.sql","ri06-automatic-recovery.sql","ri06-automatic-recovery.sql")) // rerunnable
      try(var c=jdbc.getDataSource().getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
        new org.springframework.core.io.FileSystemResource("../postgres-database-replacement/mariadb-migrations/"+m));}
    for(String t:List.of("projection_catchup_cursor","projection_catchup_log","projection_recovery","projection_transitions","projection_recon")) jdbc.update("DELETE FROM "+t);
    jdbc.update("UPDATE projection_catchup_lease SET owner_id=NULL,fence=0,expires_at=NULL");
    jdbc.update("UPDATE projection_active SET projection_scope='legacy-unknown' WHERE singleton_id=1");
    jdbc.update("DELETE FROM projection_runs WHERE projection_scope<>'legacy-unknown'");
    jdbc.update("INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,descriptor_hash,phase) VALUES ('fresh-scope','fresh','epoch-v1',?,'ACTIVE')",HASH);
    jdbc.update("UPDATE projection_active SET projection_scope='fresh-scope' WHERE singleton_id=1");
    tradePublisher=mock(Publisher.class);positionPublisher=mock(Publisher.class);
    hookActions.clear();refusal=null;sourceHash=HASH;source.clear();
    // seq1 NEW; seq2 fill (trade + order in ONE command); seq3 no output; seq4 fill+FILLED; seq5 second order NEW.
    add(1,order("fresh-1",1,0,20,"NEW"));
    add(2,trade(1,2,"100"));add(2,order("fresh-1",2,1,10,"PARTIALLY_FILLED"));
    add(4,trade(2,4,"200"));add(4,order("fresh-1",4,1,0,"FILLED"));
    add(5,order("fresh-2",5,0,20,"NEW"));
    boundary=5;
  }
  void add(long seq,ScopedEvent e) {
    try {source.add(Map.entry(seq,JSON.writeValueAsString(Map.of("type",e instanceof TradeOrder?"TradeOrder":"OrderUpdate","payload",e))));}
    catch(Exception ex){throw new IllegalStateException(ex);}
  }
  TradeService service(){var s=new TradeService(trades,positions,tradePublisher,positionPublisher,1,null,new TransactionTemplate(transactions));
    s.projectionWriteLock(new ProjectionWriteLock(jdbc));s.runRegistry(new RunRegistry(jdbc));return s;}
  OrderProjectionService orderService(){return new OrderProjectionService(orders,new RunRegistry(jdbc),new ProjectionWriteLock(jdbc),new TransactionTemplate(transactions));}
  AutomaticProjectionRecovery worker(int page) {
    // Each call models a NEW owner after the previous one shut down (graceful release).
    jdbc.update("UPDATE projection_catchup_lease SET owner_id=NULL,expires_at=NULL");
    return worker(page,false);
  }
  AutomaticProjectionRecovery worker(int page,boolean unused) {return worker(page,30000);}
  @SuppressWarnings({"unchecked","rawtypes"}) AutomaticProjectionRecovery worker(int page,long leaseTtlMs) {
    var hooks=new StaticListableBeanFactory();
    hooks.addBean("hooks",(AutomaticProjectionRecovery.Hooks)point->{Runnable r=hookActions.get(point);if(r!=null)r.run();});
    return new AutomaticProjectionRecovery(jdbc,new RunRegistry(jdbc),new ProjectionWriteLock(jdbc),peer,trades,orders,positions,service(),orderService(),
      transactions,hooks.getBeanProvider(AutomaticProjectionRecovery.Hooks.class),(org.springframework.beans.factory.ObjectProvider)new StaticListableBeanFactory().getBeanProvider(finos.traderx.messaging.nats.NatsJSONSubscriber.class),
      true,"http://127.0.0.1:1",1000,leaseTtlMs,60000,page,100);
  }
  TradeOrder trade(int id,long seq,String price){var t=new TradeOrder("e1-fresh-"+id+"-B",1001,"IBM",TradeSide.Buy,10);
    t.setPrice(new BigDecimal(price));t.setSourceOrderId("fresh-1");t.setProjectionScope("fresh-scope");
    t.setClusterEpoch("fresh");t.setEventIdScheme("epoch-v1");t.setRunDescriptorHash(HASH);t.setConsensusSequence(seq);return t;}
  OrderUpdate order(String id,long seq,int ordinal,int remaining,String status){var o=new OrderUpdate();o.setId(id);o.setAccountId(1001);o.setSecurity("IBM");o.setSide("Buy");
    o.setQuantity(20);o.setRemainingQuantity(remaining);o.setLimitPrice(new BigDecimal("300"));o.setStatus(status);
    o.setCreatedAt(1_000_000_000_123L);o.setUpdatedAt(1_000_000_000_123L+seq*1001);o.setProjectionScope("fresh-scope");o.setClusterEpoch("fresh");
    o.setEventIdScheme("epoch-v1");o.setRunDescriptorHash(HASH);o.setConsensusSequence(seq);o.setOutputOrdinal(ordinal);return o;}
  Map<String,Object> cursor(){return jdbc.queryForMap("SELECT * FROM projection_catchup_cursor WHERE projection_scope='fresh-scope'");}
  List<Map<String,Object>> snapshot(){var s=new ArrayList<Map<String,Object>>();
    for(String t:List.of("trades","positions","orderbook","projection_catchup_log")) s.addAll(jdbc.queryForList("SELECT * FROM "+t+" ORDER BY 1"));return s;}
  int count(String sql){return jdbc.queryForObject(sql,Integer.class);}
  void assertConverged() {
    assertEquals(2,count("SELECT count(*) FROM trades WHERE projectionscope='fresh-scope'"));
    var p=positions.findByProjectionScopeAndAccountIdAndSecurity("fresh-scope",1001,"IBM");
    assertEquals(20,p.getQuantity());assertEquals(0,new BigDecimal("150").compareTo(p.getAverageCostBasis()));
    assertEquals("FILLED",orders.findById("fresh-1").orElseThrow().getStatus());
    assertEquals(4L,orders.findById("fresh-1").orElseThrow().getConsensusSequence());
    assertEquals("NEW",orders.findById("fresh-2").orElseThrow().getStatus());
    assertEquals(5L,((Number)cursor().get("verified_through_seq")).longValue());assertEquals("CURRENT",cursor().get("state"));
  }

  @Test void interiorAndPerCommandGapsConvergeAndRetainedTradeIsUntouched() {
    orderService().persist(order("fresh-1",2,1,10,"PARTIALLY_FILLED")); // per-command gap: seq2 order arrived, its trade did not
    service().processTrade(trade(2,4,"200"));                            // interior gap: seq4 arrived before missing seq2 trade
    var retained=jdbc.queryForList("SELECT * FROM trades WHERE id='e1-fresh-2-B'");
    assertTrue(worker(500).cycle());
    assertConverged();
    assertEquals(retained,jdbc.queryForList("SELECT * FROM trades WHERE id='e1-fresh-2-B'"));
    assertEquals(0,count("SELECT count(*) FROM projection_recovery")); // no explicit operator catch-up used
  }

  @Test void boundedPagesNeverSplitACommandAndCoverZeroOutputCommands() {
    assertTrue(worker(2).cycle());
    assertConverged();
    // page size 2: seq1 (1) | seq2 (2) | zero-output seq3 closes page 2 | seq4 (2) | seq5 (1)
    assertEquals(List.of(1L,3L,4L,5L),jdbc.queryForList("SELECT through_seq FROM projection_catchup_log ORDER BY id",Long.class));
    assertEquals(List.of(0L,1L,3L,4L),jdbc.queryForList("SELECT after_seq FROM projection_catchup_log ORDER BY id",Long.class));
  }

  @Test void newerLiveOrderStateAndNewerTradesSurviveCatchUp() {
    // Live delivered versions NEWER than the boundary the page is cut at.
    add(7,order("fresh-1",7,0,0,"FILLED"));add(8,trade(3,8,"300"));boundary=5;
    var late=order("fresh-1",7,0,0,"FILLED");orderService().persist(late);
    service().processTrade(trade(2,4,"200"));service().processTrade(trade(3,8,"300"));
    assertTrue(worker(500).cycle());
    assertEquals(7L,orders.findById("fresh-1").orElseThrow().getConsensusSequence()); // not rewound to seq4
    assertEquals(3,count("SELECT count(*) FROM trades"));                             // not double-booked
    var p=positions.findByProjectionScopeAndAccountIdAndSecurity("fresh-scope",1001,"IBM");
    assertEquals(30,p.getQuantity());                          // newer trade kept in the rebuilt position
    assertEquals(0,new BigDecimal("200").compareTo(p.getAverageCostBasis())); // authoritative order 100,200,300
    boundary=8;assertTrue(worker(500).cycle());                // next page verifies the newer rows exactly
    assertEquals(8L,((Number)cursor().get("verified_through_seq")).longValue());
    assertEquals(3,count("SELECT count(*) FROM trades"));
  }

  @Test void duplicateAndOutOfOrderLiveDeliveryAfterCatchUpChangesNothing() {
    assertTrue(worker(500).cycle());var before=snapshot();
    service().processTrade(trade(1,2,"100"));orderService().persist(order("fresh-1",2,1,10,"PARTIALLY_FILLED"));orderService().persist(order("fresh-1",1,0,20,"NEW"));
    assertTrue(worker(500).cycle()); // idle: source boundary == cursor
    assertEquals(before,snapshot());assertConverged();
  }

  @Test void injectedFailureBeforeCommitRollsBackEverythingThenRetryConverges() {
    var before=snapshot();
    hookActions.put("before-commit",()->{throw new IllegalStateException("INJECTED_BEFORE_COMMIT");});
    assertFalse(worker(500).cycle());
    assertEquals(before,snapshot());
    assertEquals(0L,((Number)cursor().get("verified_through_seq")).longValue());
    verifyNoInteractions(tradePublisher,positionPublisher);
    hookActions.clear();assertTrue(worker(500).cycle());assertConverged();
  }

  @Test void injectedFailureAfterCommitIsSafeToRetry() {
    hookActions.put("after-commit",()->{throw new IllegalStateException("INJECTED_AFTER_COMMIT");});
    assertFalse(worker(500).cycle());
    assertEquals(5L,((Number)cursor().get("verified_through_seq")).longValue());assertEquals("RETRYING",cursor().get("state"));
    var after=snapshot();
    hookActions.clear();assertTrue(worker(500).cycle());assertEquals(after,snapshot());assertConverged(); // idle -> CURRENT, no rewrite
  }

  @Test void pausedStaleOwnerCannotCommitAfterFencedTakeover() throws Exception {
    var a=worker(500);var paused=new CountDownLatch(1);var resume=new CountDownLatch(1);
    hookActions.put("fetched",()->{paused.countDown();try{resume.await();}catch(InterruptedException e){throw new IllegalStateException(e);}});
    var result=Executors.newSingleThreadExecutor().submit(a::cycle);
    assertTrue(paused.await(20,TimeUnit.SECONDS));
    hookActions.clear();
    jdbc.update("UPDATE projection_catchup_lease SET expires_at=NOW(6)-INTERVAL 1 SECOND"); // A's lease lapses while paused
    var b=worker(500,false);assertTrue(b.cycle());assertConverged(); // takeover by EXPIRY, no release
    long bFence=b.status().get("worker") instanceof Map<?,?> m?((Number)m.get("fence")).longValue():-1;
    var afterB=snapshot();
    resume.countDown();
    assertTrue(result.get(20,TimeUnit.SECONDS)); // fenced -> standby, not an error
    assertEquals(afterB,snapshot());              // stale owner committed nothing
    assertEquals(List.of(bFence),jdbc.queryForList("SELECT DISTINCT fence FROM projection_catchup_log",Long.class));
    assertFalse(a.acquireLease());                 // B is still the live owner
  }

  @Test void takeoverCannotInterleaveWithAnOwnerInsideItsTransaction() throws Exception {
    var a=worker(2);var inside=new CountDownLatch(1);var resume=new CountDownLatch(1);
    hookActions.put("before-commit",()->{hookActions.remove("before-commit");inside.countDown();
      try{resume.await();}catch(InterruptedException e){throw new IllegalStateException(e);}});
    var result=Executors.newSingleThreadExecutor().submit(a::cycle);
    assertTrue(inside.await(20,TimeUnit.SECONDS));
    // Even a forced lapse of A's lease cannot land while A holds the lease row: it waits for A's commit.
    var lapse=Executors.newSingleThreadExecutor().submit(()->jdbc.update("UPDATE projection_catchup_lease SET expires_at=NOW(6)-INTERVAL 1 SECOND"));
    Thread.sleep(1500);assertFalse(lapse.isDone(),"lease row must be locked by the in-flight page transaction");
    resume.countDown();
    assertEquals(1,lapse.get(60,TimeUnit.SECONDS));
    result.get(60,TimeUnit.SECONDS);
    assertEquals(1L,jdbc.queryForObject("SELECT through_seq FROM projection_catchup_log ORDER BY id LIMIT 1",Long.class)); // A's page committed before the lapse
    var b=worker(2);assertTrue(b.cycle());assertConverged();
    var fences=jdbc.queryForList("SELECT fence FROM projection_catchup_log ORDER BY id",Long.class);
    for(int i=1;i<fences.size();i++) assertTrue(fences.get(i)>=fences.get(i-1),fences.toString());
  }

  @Test void ownerStalledInsideItsTransactionIsAbortedByTheServerAndCannotCommit() throws Exception {
    // In-process pause inside the page transaction, longer than the 2 s lease TTL (injected, not SIGSTOP).
    var a=worker(500,2000L);var inside=new CountDownLatch(1);
    hookActions.put("before-commit",()->{hookActions.remove("before-commit");inside.countDown();
      try{Thread.sleep(8000);}catch(InterruptedException e){throw new IllegalStateException(e);}});
    var result=Executors.newSingleThreadExecutor().submit(a::cycle);
    assertTrue(inside.await(20,TimeUnit.SECONDS));
    var b=worker(500,2000L);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while(!(b.cycle() && Boolean.TRUE.equals(((Map<?,?>)b.status().get("worker")).get("isLeaseOwner")))) {
      assertTrue(System.nanoTime()<deadline,"no takeover while the stalled owner held its transaction");Thread.sleep(200);}
    assertConverged();var afterB=snapshot();
    long bFence=((Number)((Map<?,?>)b.status().get("worker")).get("fence")).longValue();
    result.get(30,TimeUnit.SECONDS);                  // A resumes on a session the server already aborted
    assertEquals(afterB,snapshot());
    assertEquals(List.of(bFence),jdbc.queryForList("SELECT DISTINCT fence FROM projection_catchup_log",Long.class));
  }

  /** Three commands, one order each, all trades for account 1001 IBM; sides/prices per case. */
  void threeCommandSource(String[] sides,String[] prices,int[] qty) {
    source.clear();boundary=3;
    for(int i=1;i<=3;i++) {
      TradeOrder t=trade(i,i,prices[i-1]);t.setQuantity(qty[i-1]);t.setSourceOrderId("fresh-"+i);
      if("Sell".equals(sides[i-1])) {t.setId("e1-fresh-"+i+"-S");t.setSide(TradeSide.Sell);}
      OrderUpdate o=order("fresh-"+i,i,1,0,"FILLED");o.setSide(sides[i-1]);o.setQuantity(qty[i-1]);
      add(i,t);add(i,o);
    }
  }
  List<ScopedEvent> parsed() {
    var out=new ArrayList<ScopedEvent>();
    for(var e:source) try{var n=JSON.readTree(e.getValue());
      out.add("TradeOrder".equals(n.path("type").asText())?JSON.treeToValue(n.path("payload"),TradeOrder.class):JSON.treeToValue(n.path("payload"),OrderUpdate.class));}
      catch(Exception x){throw new IllegalStateException(x);}
    return out;
  }
  void deliverLive(int... tradeOrder) {
    var events=parsed();
    for(var e:events) if(e instanceof OrderUpdate o) orderService().persist(o);
    for(int i:tradeOrder) service().processTrade((TradeOrder)events.get(2*(i-1)));
  }
  Position ibm(){return positions.findByProjectionScopeAndAccountIdAndSecurity("fresh-scope",1001,"IBM");}

  // Review R1 (coordinator reproduction): every trade already retained, delivered out of order through
  // a FLAT point. Arrival-order basis 200 must not be certified CURRENT; source order gives 300.
  @Test void allTradesRetainedOutOfOrderThroughFlatAreReconciledToSourceOrderBasis() throws Exception {
    threeCommandSource(new String[]{"Buy","Sell","Buy"},new String[]{"100","200","300"},new int[]{10,10,10});
    deliverLive(3,1,2);
    assertEquals(0,new BigDecimal("200").compareTo(ibm().getAverageCostBasis()),"precondition: arrival-order basis");
    var tradesBefore=jdbc.queryForList("SELECT * FROM trades ORDER BY id");clearInvocations(tradePublisher,positionPublisher);
    assertTrue(worker(500).cycle());
    assertEquals("CURRENT",cursor().get("state"));
    assertEquals(10,ibm().getQuantity());assertEquals(0,new BigDecimal("300").compareTo(ibm().getAverageCostBasis()));
    assertEquals(tradesBefore,jdbc.queryForList("SELECT * FROM trades ORDER BY id")); // immutable trades untouched
    verify(positionPublisher,times(1)).publish(eq("/v2/projections/fresh-scope/accounts/1001/positions"),argThat(p->p.getQuantity()==10
        && p.getAverageCostBasis().compareTo(new BigDecimal("300"))==0));
    verifyNoInteractions(tradePublisher);
    clearInvocations(positionPublisher);assertTrue(worker(500).cycle()); // idle rerun: nothing re-sent
    verifyNoInteractions(positionPublisher);
  }

  // A flip through zero without landing flat keeps the signed-notional average order-independent:
  // reconciliation must converge with no corrective write and no spurious notification.
  @Test void allTradesRetainedOutOfOrderThroughAFlipNeedNoCorrection() {
    threeCommandSource(new String[]{"Buy","Sell","Buy"},new String[]{"100","200","400"},new int[]{10,20,20});
    deliverLive(2,1,3); // -20@200, +10@100 (-10, 300), +20@400 (+10, 500); source order also gives 500
    var positionBefore=jdbc.queryForList("SELECT * FROM positions");clearInvocations(positionPublisher);
    assertTrue(worker(500).cycle());
    assertEquals("CURRENT",cursor().get("state"));
    assertEquals(10,ibm().getQuantity());assertEquals(0,new BigDecimal("500").compareTo(ibm().getAverageCostBasis()));
    assertEquals(positionBefore,jdbc.queryForList("SELECT * FROM positions"));
    verifyNoInteractions(positionPublisher);
  }

  @Test void unexplainedBalanceOnAKeyWhoseTradesAreAllRetainedStillRefuses() {
    threeCommandSource(new String[]{"Buy","Sell","Buy"},new String[]{"100","200","300"},new int[]{10,10,10});
    deliverLive(1,2,3);jdbc.update("UPDATE positions SET quantity=11");
    var before=snapshot();clearInvocations(positionPublisher);
    assertFalse(worker(500).cycle());
    assertTrue(((String)cursor().get("blocked_reason")).startsWith("RECOVERY_RETAINED_POSITION_QUANTITY_CONFLICT"));
    assertEquals(before,snapshot());verifyNoInteractions(positionPublisher);
  }

  // Review R2 (coordinator reproduction): normal chronological catch-up must notify the final position.
  @Test void successfulCatchUpPublishesTheFinalPositionAfterCommit() throws Exception {
    assertTrue(worker(500).cycle());assertConverged();
    verify(positionPublisher,times(1)).publish(eq("/v2/projections/fresh-scope/accounts/1001/positions"),argThat(p->p.getQuantity()==20
        && p.getAverageCostBasis().compareTo(new BigDecimal("150"))==0));
  }

  @Test void conflictingImmutableTradeBlocksWithoutWrites() {
    service().processTrade(trade(2,4,"200"));
    jdbc.update("UPDATE trades SET price=201 WHERE id='e1-fresh-2-B'");clearInvocations(tradePublisher,positionPublisher);
    var before=snapshot();
    assertFalse(worker(500).cycle());
    assertEquals(before,snapshot());
    assertEquals("BLOCKED",cursor().get("state"));assertTrue(((String)cursor().get("blocked_reason")).startsWith("TRADE_ID_CONFLICT"),String.valueOf(cursor().get("blocked_reason")));
    verifyNoInteractions(tradePublisher,positionPublisher);
  }

  @Test void orphanUnexplainedBalanceAndHistoryChangeRefuse() {
    service().processTrade(trade(9,3,"100")); // a retained trade the source never produced (seq3 has no output)
    assertFalse(worker(500).cycle());assertTrue(((String)cursor().get("blocked_reason")).startsWith("RECOVERY_ORPHAN_OR_AHEAD_TRADE"));
    trades.deleteAll();positions.deleteAll();jdbc.update("DELETE FROM projection_catchup_cursor");
    service().processTrade(trade(2,4,"200"));jdbc.update("UPDATE positions SET quantity=17");
    assertFalse(worker(500).cycle());assertTrue(((String)cursor().get("blocked_reason")).startsWith("RECOVERY_RETAINED_POSITION_QUANTITY_CONFLICT"));
    assertEquals(17,count("SELECT quantity FROM positions"));
    trades.deleteAll();positions.deleteAll();jdbc.update("DELETE FROM projection_catchup_cursor");
    boundary=2;assertTrue(worker(500).cycle());
    String original=source.get(0).getValue();
    source.set(0,Map.entry(1L,original.replace("\"remainingQuantity\":20","\"remainingQuantity\":19"))); // rewritten history below cursor
    assertNotEquals(original,source.get(0).getValue());
    boundary=5;var before=snapshot();
    assertFalse(worker(500).cycle());assertTrue(((String)cursor().get("blocked_reason")).startsWith("AUTO_RECOVERY_SOURCE_HISTORY_CHANGED"));
    assertEquals(before,snapshot());assertEquals(2L,((Number)cursor().get("verified_through_seq")).longValue());
  }

  @Test void missingArchiveAndIdentityRefusalsWriteNothing() {
    var before=snapshot();
    refusal="RECOVERY_PEER_REFUSED: HTTP 500 {\"error\":\"RECOVERY_ARCHIVE_GENESIS_MISSING\"}";
    assertFalse(worker(500).cycle());assertEquals("BLOCKED",cursor().get("state"));
    assertTrue(((String)cursor().get("blocked_reason")).contains("RECOVERY_ARCHIVE_GENESIS_MISSING"));
    assertEquals(0L,((Number)cursor().get("verified_through_seq")).longValue());
    refusal=null;jdbc.update("DELETE FROM projection_catchup_cursor");
    jdbc.update("INSERT INTO projection_recovery VALUES ('fresh-scope',?,3,?,1)","b".repeat(64),"c".repeat(64));
    assertFalse(worker(500).cycle());assertEquals(0,count("SELECT count(*) FROM projection_catchup_cursor"));
    jdbc.update("DELETE FROM projection_recovery");
    sourceHash="d".repeat(64); // no configured endpoint serves the selected descriptor
    assertFalse(worker(500).cycle());
    assertEquals(before.size(),snapshot().size());assertEquals(0,count("SELECT count(*) FROM trades"));
  }

  @Test void selectedRunTransitionSupersedesInFlightPageAndSealedOrLegacyScopesAreNeverAutomatic() {
    hookActions.put("fetched",()->{
      jdbc.update("UPDATE projection_runs SET phase='SEALED' WHERE projection_scope='fresh-scope'");
      jdbc.update("UPDATE projection_active SET projection_scope='legacy-unknown' WHERE singleton_id=1");});
    var before=snapshot();
    assertTrue(worker(500).cycle());               // superseded is not an error and not BLOCKED
    assertEquals(before,snapshot());assertEquals("CATCHING_UP",cursor().get("state"));
    hookActions.clear();assertTrue(worker(500).cycle()); // legacy selected: nothing automatic
    assertEquals(before,snapshot());
  }

  @Test void drainingScopeCatchesUpOnlyToItsFrozenBoundary() {
    jdbc.update("UPDATE projection_runs SET phase='DRAINING',frozen_seq=5 WHERE projection_scope='fresh-scope'");
    assertTrue(worker(500).cycle());assertConverged();
    add(6,order("fresh-2",6,0,0,"CANCELED"));boundary=6; // a business event beyond the freeze is a refusal
    assertFalse(worker(500).cycle());assertTrue(((String)cursor().get("blocked_reason")).startsWith("RUN_SCOPE_NOT_WRITABLE"));
  }

  @Test void generatedSecondPrecisionSchemaStillVerifiesExactlyAndRefusesStoredCorruption() {
    jdbc.execute("ALTER TABLE orderbook MODIFY createdat DATETIME(0), MODIFY updatedat DATETIME(0)");
    try {
      assertTrue(worker(500).cycle());assertConverged();
      jdbc.update("DELETE FROM projection_catchup_cursor");jdbc.update("DELETE FROM projection_catchup_log");
      jdbc.update("UPDATE orderbook SET updatedat=DATE_ADD(updatedat, INTERVAL 1 SECOND) WHERE orderid='fresh-2'");
      assertFalse(worker(500).cycle());
      assertTrue(((String)cursor().get("blocked_reason")).startsWith("RECOVERY_RETAINED_ORDER_ROW_CONFLICT"));
    } finally {jdbc.execute("ALTER TABLE orderbook MODIFY createdat DATETIME(6), MODIFY updatedat DATETIME(6)");}
  }
}
