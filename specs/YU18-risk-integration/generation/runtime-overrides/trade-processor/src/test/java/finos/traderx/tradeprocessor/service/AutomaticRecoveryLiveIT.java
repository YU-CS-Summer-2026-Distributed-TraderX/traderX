package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * LOCAL-LIVE level. Topology: three sequential SINGLE-MEMBER Aeron clusters (legacy, fresh-live,
 * next-live) with their real HTTP gateways and archives, real NATS, one MariaDB initialised from the
 * GENERATED ConfigMap 900 schema + ri06 + event-recovery + automatic-recovery migrations
 * (ddl-auto=validate), and real trade-processor consumers as CHILD JVMs (so SIGKILL/SIGSTOP are
 * real). No operator catch-up call exists in this class. No HA claim.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RI06_MATCHER_CLASSPATH_FILE",matches=".+")
@Tag("integration") @Testcontainers @Timeout(1500)
class AutomaticRecoveryLiveIT {
 // max_allowed_packet raised on this DISPOSABLE container only: the existing transition VERIFY
 // stores its whole witness in one row and exceeded the default with ~1k trades (pre-existing limit, reported).
 @Container static final MariaDBContainer<?> DB=new MariaDBContainer<>("mariadb:11.4").withCommand("--lower_case_table_names=1","--max-allowed-packet=256M")
  .withCreateContainerCmdModifier(c->c.withName("traderx-apr-live-sql").getHostConfig().withMemory(536870912L).withNanoCPUs(1000000000L));
 @Container static final GenericContainer<?> NATS=new GenericContainer<>("nats:2.10-alpine").withExposedPorts(4222)
  .withCreateContainerCmdModifier(c->c.withName("traderx-apr-live-nats").getHostConfig().withMemory(134217728L)
   // Fixed host port so a stop/start outage keeps the SAME address for publisher and consumers.
   .withPortBindings(com.github.dockerjava.api.model.PortBinding.parse("127.0.0.1:28322:4222")));
 static final String NATS_URL="nats://127.0.0.1:28322";
 static final String TOKEN="apr-proof-token";
 static final String OLD_GW="http://127.0.0.1:28281",FRESH_GW="http://127.0.0.1:28381",NEXT_GW="http://127.0.0.1:28285";
 final ObjectMapper json=new ObjectMapper();
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
 final List<Process> engines=new ArrayList<>();
 final Map<String,Process> consumers=new LinkedHashMap<>();
 Path work;JdbcTemplate jdbc;PrintWriter evidence;

 void ev(String line){System.out.println(line);evidence.println(System.currentTimeMillis()+" "+line);evidence.flush();}
 static String hash(byte[] b) throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));}
 String descriptor(String epoch,String scope,boolean legacy,String evidenceHash) throws Exception {
  return json.writeValueAsString(Map.of("schema","traderx.run.v1","epoch",epoch,"eventIdScheme",legacy?"legacy-v0":"epoch-v1",
   "storageLineage",scope+"-storage","projectionScope",scope,"adoptionEvidenceSha256",legacy?evidenceHash:"NULL")).replace("\"NULL\"","null");
 }
 Process launch(String name,String main,Map<String,String> env,List<String> jvm,String cp) throws Exception {
  var command=new ArrayList<>(List.of(System.getProperty("java.home")+"/bin/java","-Xms64m","-Xmx384m"));command.addAll(jvm);
  command.addAll(List.of("-cp",cp,main));
  var b=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(work.resolve(name+".log").toFile()));
  b.environment().clear();b.environment().put("PATH",System.getenv("PATH"));b.environment().putAll(env);return b.start();
 }
 void startRun(String name,String epoch,String raw,int base,int health,int gateway) throws Exception {
  String cp=Files.readString(Path.of(System.getenv("RI06_MATCHER_CLASSPATH_FILE"))).trim();
  Path storage=work.resolve(name+"-storage"),identity=storage.resolve("run-identity.json");Files.createDirectories(storage);Files.writeString(identity,raw);
  if(name.equals("old")) Files.writeString(storage.resolve("run-adoption-evidence.json"),"synthetic archived legacy source evidence");
  Map<String,String> common=new HashMap<>(Map.of("RUN_DESCRIPTOR_PATH",identity.toString(),"CLUSTER_EPOCH",epoch,"RISK_CONTROL_TOKEN",TOKEN));
  var node=new HashMap<>(common);node.putAll(Map.of("CLUSTER_MEMBER_ID","0","CLUSTER_HOSTNAMES","localhost","CLUSTER_PORT_BASE",""+base,
   "CLUSTER_BASE_DIR",storage.toString(),"CLUSTER_AERON_DIR",work.resolve(name+"-aeron").toString(),"HEALTH_PORT",""+health,"CLUSTER_IDLE_SLEEP_MS","1"));
  node.putAll(Map.of("RECON_BLOTTER_CAPACITY","10000","RECON_FULL_HISTORY_MAX","100000","REGULATORY_MAX_RECORDS","100000",
   "TRADE_BRIDGE_NATS_URL",NATS_URL));
  var jvm=List.of("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED");
  engines.add(launch(name+"-node","finos.traderx.ordermatcher.cluster.ClusterNodeMain",node,jvm,cp));
  await("member "+name,()->ok("http://127.0.0.1:"+health+"/health"),60);
  var gate=new HashMap<>(common);gate.putAll(Map.of("GATEWAY_INGRESS_ENDPOINTS","0=localhost:"+(base+2),"GATEWAY_HTTP_PORT",""+gateway,
   "GATEWAY_PROBE_PORT",""+(gateway+1),"GATEWAY_MEMBER_HEALTH_PORT",""+health,"GATEWAY_AERON_DIR",work.resolve(name+"-gateway-aeron").toString(),
   "RUN_PROJECTION_CONTROL_URL","http://127.0.0.1:28290"));
  engines.add(launch(name+"-gateway","finos.traderx.ordermatcher.cluster.ClusterGatewayMain",gate,jvm,cp));
  await("gateway "+name,()->ok("http://127.0.0.1:"+gateway+"/run/status"),60);
 }
 /** Real trade-processor child JVM. auto=true activates ONLY the disposable auto-recovery-local profile. */
 Process consumer(String name,int port,boolean auto,String haltAt) throws Exception {
  var env=new HashMap<String,String>(Map.of("AUTO_RECOVERY_ENDPOINTS",FRESH_GW+","+NEXT_GW,"AUTO_RECOVERY_LEASE_TTL_MS","5000"));
  if(haltAt!=null) env.put("AUTO_RECOVERY_HALT_AT",haltAt);
  var args=new ArrayList<>(List.of("--server.port="+port,"--spring.sql.init.mode=never","--spring.datasource.url="+DB.getJdbcUrl(),
   "--spring.datasource.username="+DB.getUsername(),"--spring.datasource.password="+DB.getPassword(),"--spring.datasource.driverClassName=org.mariadb.jdbc.Driver","--spring.jpa.hibernate.ddl-auto=validate","--spring.jpa.database-platform=org.hibernate.dialect.MariaDBDialect",
   "--nats.address=nats://"+NATS.getHost()+":"+NATS.getMappedPort(4222),"--RISK_CONTROL_TOKEN="+TOKEN,"--recon.poll.interval-ms=3600000",
   "--order-matcher.base-url=http://127.0.0.1:1","--settlement.sweep.interval-ms=3600000","--eod.event.enabled=false"));
  if(auto) args.add("--spring.profiles.active=auto-recovery-local");
  var cmd=new ArrayList<>(List.of("--add-opens=java.base/java.lang=ALL-UNNAMED"));
  var list=new ArrayList<String>();list.add(System.getProperty("java.home")+"/bin/java");list.add("-Xmx384m");list.add("-cp");
  // Production resources only: the test-classpath application.properties (H2, ddl-auto=create-drop)
  // must never reach a consumer pointed at the proof database.
  list.add(Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
   .filter(e->!e.replace('\\','/').endsWith("/resources/test")).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)));
  list.add("finos.traderx.tradeprocessor.service.AutoRecoveryConsumerMain");list.addAll(args);
  var b=new ProcessBuilder(list).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(work.resolve(name+".log").toFile()));
  b.environment().putAll(env);Process p=b.start();consumers.put(name+"@"+p.pid(),p);
  ev("APR_CONSUMER_START name="+name+" pid="+p.pid()+" port="+port+" auto="+auto+" haltAt="+haltAt);
  await("consumer "+name,()->!p.isAlive() || ok("http://127.0.0.1:"+port+"/v2/projection-completeness"),120);
  if(haltAt==null) assertTrue(p.isAlive(),"consumer "+name+" died during startup; inspect "+work);
  assertFalse(Files.readString(work.resolve(name+".log")).contains("H2Dialect"),"consumer must run the production MariaDB configuration");
  return p;
 }
 void stopGracefully(Process p) throws Exception {p.destroy();assertTrue(p.waitFor(30,TimeUnit.SECONDS));ev("APR_CONSUMER_STOP pid="+p.pid()+" exit="+p.exitValue());}
 void kill9(Process p) throws Exception {new ProcessBuilder("kill","-9",""+p.pid()).start().waitFor();assertTrue(p.waitFor(20,TimeUnit.SECONDS));ev("APR_SIGKILL pid="+p.pid()+" exit="+p.exitValue());}
 void signal(Process p,String sig) throws Exception {assertEquals(0,new ProcessBuilder("kill","-"+sig,""+p.pid()).start().waitFor());ev("APR_SIG"+sig+" pid="+p.pid());}
 boolean ok(String uri){try{return http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200;}catch(Exception e){return false;}}
 void await(String what,BooleanSupplier c,int seconds) throws Exception {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
  while(!c.getAsBoolean()){for(Process p:engines) assertTrue(p.isAlive(),"engine exited; inspect "+work);
   assertTrue(System.nanoTime()<deadline,"timeout: "+what+"; inspect "+work);Thread.sleep(100);}
 }
 HttpResponse<String> send(String base,String path,Object body) throws Exception {
  return http.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json")
   .header("X-Risk-Control-Token",TOKEN).header("X-Risk-Operator","apr-live-proof").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
 }
 JsonNode post(String base,String path,Object body) throws Exception {var r=send(base,path,body);assertEquals(200,r.statusCode(),path+": "+r.body());return json.readTree(r.body());}
 JsonNode status(int port) throws Exception {return json.readTree(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v2/projection-completeness")).GET().build(),HttpResponse.BodyHandlers.ofString()).body());}
 JsonNode scopeStatus(int port,String scope) throws Exception {for(JsonNode s:status(port).path("completeness")) if(scope.equals(s.path("projectionScope").asText())) return s;return json.createObjectNode();}
 void seed(String gw) throws Exception {for(int a:List.of(22214,11413)) post(gw,"/seed",Map.of("accountId",a,"tickers","RESERVED,IBM","price",100));}
 void pair(String gw,int qty,String key) throws Exception {
  post(gw,"/orders",Map.of("accountId",22214,"security","IBM","side","Buy","quantity",qty,"limitPrice",100,"clientOrderId",key+"b"));
  post(gw,"/orders",Map.of("accountId",11413,"security","IBM","side","Sell","quantity",qty,"limitPrice",100,"clientOrderId",key+"s"));
 }
 int count(String sql,Object... a){return jdbc.queryForObject(sql,Integer.class,a);}
 long gatewayApplied(String gw) throws Exception {return json.readTree(http.send(HttpRequest.newBuilder(URI.create(gw+"/run/status")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()).path("members").get(0).path("applied").asLong(-1);}

 /** Continuous trading source; records every submission time so overlap with page commits is measured, not assumed. */
 final class Trader implements AutoCloseable {
  final AtomicBoolean on=new AtomicBoolean(true);final AtomicInteger pairs=new AtomicInteger(),failures=new AtomicInteger();
  final List<Long> times=Collections.synchronizedList(new ArrayList<>());final Thread t;final String gw;
  final String startDb=jdbc.queryForObject("SELECT NOW(6)",String.class);
  Trader(String gw,String tag){this.gw=gw;ev("APR_TRADER_START gw="+gw+" dbTime="+startDb);t=new Thread(()->{int i=0;while(on.get()){try{pair(gw,1+(i%5),tag+"-"+(i++));pairs.incrementAndGet();times.add(System.currentTimeMillis());Thread.sleep(120);}
    catch(Throwable e){failures.incrementAndGet();try{Thread.sleep(200);}catch(InterruptedException x){return;}}}},"apr-trader-"+tag);t.start();}
  public void close() throws Exception {
   String lastSubmit=jdbc.queryForObject("SELECT NOW(6)",String.class);on.set(false);t.join(30000);
   ev("APR_TRADER_STOP gw="+gw+" pairs="+pairs.get()+" failures="+failures.get()
    +" pagesCommittedWhileTrading="+count("SELECT count(*) FROM projection_catchup_log WHERE committed_at BETWEEN ? AND ?",startDb,lastSubmit)
    +" ofWhichInsertedMissedTrades="+count("SELECT count(*) FROM projection_catchup_log WHERE inserted_trades>0 AND committed_at BETWEEN ? AND ?",startDb,lastSubmit));}
 }

 /** Authoritative comparison: the archive at a quiet boundary (independent of the automatic page path). */
 void assertMatchesArchive(String scope,String gw) throws Exception {
  var auth="Bearer "+new finos.traderx.tradeprocessor.auth.JwtTokenMinter("dev-jwt-shared-secret").mint("apr-proof",Set.of(),true,0L);
  var body=http.send(HttpRequest.newBuilder(URI.create(gw+"/recon/recovery-events")).header("Authorization",auth).timeout(Duration.ofMinutes(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(200,body.statusCode(),body.body());
  var src=json.readTree(body.body());Map<String,JsonNode> trades=new TreeMap<>(),orders=new TreeMap<>();
  for(JsonNode e:src.path("events")) {var p=e.path("payload");if("TradeOrder".equals(e.path("type").asText())) trades.put(p.path("id").asText(),p);else orders.put(p.path("id").asText(),p);}
  var sql=jdbc.queryForList("SELECT * FROM trades WHERE projectionscope=? ORDER BY id",scope);
  assertEquals(trades.keySet(),new TreeSet<>(sql.stream().map(r->(String)r.get("id")).toList()),"trade ids");
  long buy=0,sell=0;
  for(var r:sql){var t=trades.get(r.get("id"));
   assertEquals(t.path("quantity").asInt(),((Number)r.get("quantity")).intValue());assertEquals(0,new java.math.BigDecimal(t.path("price").asText()).compareTo((java.math.BigDecimal)r.get("price")));
   assertEquals(t.path("accountId").asInt(),((Number)r.get("accountid")).intValue());assertEquals(t.path("sourceOrderId").asText(),r.get("sourceorderid"));
   assertEquals(t.path("consensusSequence").asLong(),((Number)r.get("consensussequence")).longValue());assertEquals("Processing",r.get("state"));
   if("Buy".equals(t.path("side").asText())) buy+=t.path("quantity").asInt();else sell+=t.path("quantity").asInt();}
  var so=jdbc.queryForList("SELECT * FROM orderbook WHERE projectionscope=? ORDER BY orderid",scope);
  assertEquals(orders.keySet(),new TreeSet<>(so.stream().map(r->(String)r.get("orderid")).toList()),"order ids");
  for(var r:so){var o=orders.get(r.get("orderid"));
   assertEquals(o.path("consensusSequence").asLong(),((Number)r.get("consensussequence")).longValue(),"final version "+r.get("orderid"));
   assertEquals(o.path("status").asText(),r.get("status"));assertEquals(o.path("remainingQuantity").asInt(),((Number)r.get("remainingquantity")).intValue());}
  var b=jdbc.queryForMap("SELECT quantity,averagecostbasis FROM positions WHERE projectionscope=? AND accountid=22214",scope);
  var s=jdbc.queryForMap("SELECT quantity,averagecostbasis FROM positions WHERE projectionscope=? AND accountid=11413",scope);
  assertEquals(buy,((Number)b.get("quantity")).longValue());assertEquals(-sell,((Number)s.get("quantity")).longValue());assertEquals(buy,sell);
  assertEquals(0,new java.math.BigDecimal("100").compareTo((java.math.BigDecimal)b.get("averagecostbasis")));
  assertEquals(0,new java.math.BigDecimal("100").compareTo((java.math.BigDecimal)s.get("averagecostbasis")));
  ev("APR_ARCHIVE_MATCH scope="+scope+" boundary="+src.path("completeSequence")+" tradeLegs="+trades.size()+" finalOrders="+orders.size()+" buy=+"+buy+" sell=-"+sell+" basis=100");
 }
 void awaitCurrent(int port,String scope,String gw,int seconds) throws Exception {
  await("CURRENT "+scope,()->{try{var s=scopeStatus(port,scope);return "CURRENT".equals(s.path("state").asText())
   && s.path("verifiedThroughSeq").asLong()>=gatewayApplied(gw);}catch(Exception e){return false;}},seconds);
  ev("APR_CURRENT scope="+scope+" status="+scopeStatus(port,scope));
 }

 @Test void automaticCatchUpUnderContinuousTrading() throws Exception {
  work=Path.of(System.getenv().getOrDefault("RI06_LIVE_PROOF_DIR","build/apr-live-proof")).toAbsolutePath();Files.createDirectories(work);
  evidence=new PrintWriter(Files.newBufferedWriter(work.resolve("apr-evidence.log")));
  var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);
  try {
   // Schema: GENERATED ConfigMap 900 key, then the three additive migrations (automatic twice: rerunnable).
   String schema=GeneratedDatabaseManifestTest.scripts().get("900-migrations.sql");
   try(var c=ds.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ByteArrayResource(schema.getBytes()));
    for(String m:List.of("ri06.sql","ri06-event-recovery.sql","ri06-automatic-recovery.sql","ri06-automatic-recovery.sql"))
     org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.FileSystemResource("../postgres-database-replacement/mariadb-migrations/"+m));}
   assertEquals(0,count("SELECT count(*) FROM trades"));assertEquals(0,count("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orderbook' AND COLUMN_NAME='updatedat'"));

   String legacy=descriptor("oldlive","legacy-unknown",true,hash("synthetic archived legacy source evidence".getBytes()));
   String fresh=descriptor("freshlive","fresh-live",false,null),next=descriptor("nextlive","next-live",false,null);
   startRun("old","oldlive",legacy,28200,28280,28281);startRun("fresh","freshlive",fresh,28300,28380,28381);
   // ---- P0: unchanged baseline behaviour with automatic recovery DISABLED (default profile).
   Process a=consumer("tp-a-disabled",28290,false,null);
   seed(OLD_GW);pair(OLD_GW,10,"old-");
   await("legacy",()->count("SELECT count(*) FROM trades")==2 && count("SELECT count(*) FROM orderbook WHERE status='FILLED'")==2,60);
   post("http://127.0.0.1:28290","/v2/projection-control/adopt-legacy",Map.of("descriptorJson",legacy,"oldEndpoint",OLD_GW));
   Map<String,String> t1=Map.of("transitionId","apr-t1","oldScope","legacy-unknown","descriptorJson",fresh,"oldEndpoint",OLD_GW,"newEndpoint",FRESH_GW);
   post("http://127.0.0.1:28290","/v2/projection-control/prepare",t1);seed(FRESH_GW);
   for(String step:List.of("freeze","verify","select","activate")) post("http://127.0.0.1:28290","/v2/projection-control/"+step,t1);
   var legacyRows=jdbc.queryForList("SELECT * FROM trades WHERE projectionscope='legacy-unknown' ORDER BY id");
   pair(FRESH_GW,10,"f0-");
   await("f0",()->count("SELECT count(*) FROM trades WHERE projectionscope='fresh-live'")==2,60);
   stopGracefully(a);pair(FRESH_GW,20,"f-gap-");a=consumer("tp-a-disabled2",28290,false,null);
   Thread.sleep(8000); // four automatic intervals would have elapsed
   assertEquals(2,count("SELECT count(*) FROM trades WHERE projectionscope='fresh-live'"));
   assertEquals(0,count("SELECT count(*) FROM projection_catchup_cursor"));
   ev("APR_BASELINE_UNCHANGED autoDisabled=true restartDidNotCatchUp=true retainedLegs=2 expectedLegs=4 cursorRows=0");
   stopGracefully(a);

   try(var trader=new Trader(FRESH_GW,"p1")) {
    // ---- P1: consumer restart with automatic recovery ON while trading continues.
    Thread.sleep(1500);
    a=consumer("tp-a",28290,true,null);
    await("p1 progress",()->{try{return scopeStatus(28290,"fresh-live").path("verifiedThroughSeq").asLong()>0;}catch(Exception e){return false;}},90);
    Thread.sleep(4000);
    // ---- P2: publisher/NATS outage; consumer process stays connected-and-running throughout.
    var docker=org.testcontainers.DockerClientFactory.instance().client();
    docker.stopContainerCmd(NATS.getContainerId()).withTimeout(2).exec();ev("APR_NATS_STOPPED consumerAlive="+a.isAlive());
    Thread.sleep(5000);
    docker.startContainerCmd(NATS.getContainerId()).exec();ev("APR_NATS_STARTED consumerAlive="+a.isAlive());
    Thread.sleep(6000);assertTrue(a.isAlive());
    // ---- P3: external SIGKILL mid-stream, then restart.
    kill9(a);Thread.sleep(3000);a=consumer("tp-a-after-kill",28290,true,null);Thread.sleep(5000);
    // ---- P4: exact process death before / after the page commit.
    stopGracefully(a);Thread.sleep(2000);
    int logBefore=count("SELECT count(*) FROM projection_catchup_log");long through0=scopeStatus0();
    Process h=consumer("tp-halt-before",28290,true,"before-commit");assertTrue(h.waitFor(90,TimeUnit.SECONDS));assertEquals(137,h.exitValue());
    assertEquals(logBefore,count("SELECT count(*) FROM projection_catchup_log"));assertEquals(through0,scopeStatus0());
    ev("APR_HALT_BEFORE_COMMIT exit=137 logRowsUnchanged="+logBefore+" cursorUnchanged="+through0);
    h=consumer("tp-halt-after",28290,true,"after-commit");assertTrue(h.waitFor(90,TimeUnit.SECONDS));assertEquals(137,h.exitValue());
    assertEquals(logBefore+1,count("SELECT count(*) FROM projection_catchup_log"));assertTrue(scopeStatus0()>through0);
    ev("APR_HALT_AFTER_COMMIT exit=137 logRows="+(logBefore+1)+" cursorAdvancedTo="+scopeStatus0());
    // ---- P5: two competing workers; SIGSTOP the owner; fenced takeover; SIGCONT the stale owner.
    a=consumer("tp-a2",28290,true,null);Process b=consumer("tp-b",28292,true,null);
    await("single owner",()->{try{return status(28290).path("worker").path("isLeaseOwner").asBoolean()^status(28292).path("worker").path("isLeaseOwner").asBoolean();}catch(Exception e){return false;}},30);
    boolean aOwns=status(28290).path("worker").path("isLeaseOwner").asBoolean();Process owner=aOwns?a:b;int standbyPort=aOwns?28292:28290;
    long staleFence=status(aOwns?28290:28292).path("worker").path("fence").asLong();
    ev("APR_OWNER port="+(aOwns?28290:28292)+" fence="+staleFence);
    signal(owner,"STOP");long stoppedAt=System.currentTimeMillis();
    await("takeover",()->{try{var w=status(standbyPort).path("worker");return w.path("isLeaseOwner").asBoolean() && w.path("fence").asLong()>staleFence;}catch(Exception e){return false;}},180); // a SIGSTOPped owner inside its page transaction holds the lease row; takeover waits for its session to end
    long newFence=status(standbyPort).path("worker").path("fence").asLong();ev("APR_TAKEOVER port="+standbyPort+" fence="+newFence+" afterMs="+(System.currentTimeMillis()-stoppedAt));
    Thread.sleep(4000);String resumedAt=jdbc.queryForObject("SELECT NOW(6)",String.class);signal(owner,"CONT");Thread.sleep(8000);
    assertEquals(0,count("SELECT count(*) FROM projection_catchup_log WHERE fence=? AND committed_at>=?",staleFence,resumedAt),"stale owner committed after replacement");
    assertEquals(0,count("SELECT count(*) FROM projection_catchup_log a JOIN projection_catchup_log b ON b.id>a.id AND b.fence<a.fence"),"fence regressed");
    ev("APR_STALE_OWNER_FENCED staleFence="+staleFence+" commitsAfterResume=0 fencesMonotonic=true");
    stopGracefully(b);
   }
   awaitCurrent(28290,"fresh-live",FRESH_GW,120);
   assertMatchesArchive("fresh-live",FRESH_GW);

   // ---- P6: conflicting retained row refuses (BLOCKED, SQL unchanged) until a reviewed repair.
   // The victim must be a row ABOVE the verified cursor: rows below it were verified once and are not re-read.
   long verified=scopeStatus0();
   stopGracefully(a);a=consumer("tp-a-disabled3",28290,false,null);pair(FRESH_GW,3,"conflict-");
   await("conflict rows",()->count("SELECT count(*) FROM trades WHERE projectionscope='fresh-live' AND consensussequence>?",verified)==2,60);
   String victim=jdbc.queryForObject("SELECT id FROM trades WHERE projectionscope='fresh-live' AND consensussequence>? ORDER BY id LIMIT 1",String.class,verified);
   jdbc.update("UPDATE trades SET price=101 WHERE id=?",victim);stopGracefully(a);
   var frozenView=jdbc.queryForList("SELECT * FROM trades ORDER BY id");
   a=consumer("tp-a-conflict",28290,true,null);
   await("blocked",()->{try{return "BLOCKED".equals(scopeStatus(28290,"fresh-live").path("state").asText());}catch(Exception e){return false;}},60);
   assertTrue(scopeStatus(28290,"fresh-live").path("blockedReason").asText().startsWith("TRADE_ID_CONFLICT"));
   assertEquals(frozenView,jdbc.queryForList("SELECT * FROM trades ORDER BY id"));
   ev("APR_CONFLICT_BLOCKED victim="+victim+" status="+scopeStatus(28290,"fresh-live")+" sqlUnchanged=true");
   jdbc.update("UPDATE trades SET price=100 WHERE id=?",victim); // reviewed repair (test restores the true value)
   awaitCurrent(28290,"fresh-live",FRESH_GW,120);

   // ---- P7: selected-run transition while a gap is being caught up; worker never drives it.
   startRun("next","nextlive",next,28220,28284,28285);
   try(var trader=new Trader(FRESH_GW,"p7")) {
    Thread.sleep(2000);
    var docker=org.testcontainers.DockerClientFactory.instance().client();
    docker.stopContainerCmd(NATS.getContainerId()).withTimeout(2).exec();Thread.sleep(3000);docker.startContainerCmd(NATS.getContainerId()).exec();
    Thread.sleep(1500);
   }
   Map<String,String> t2=Map.of("transitionId","apr-t2","oldScope","fresh-live","descriptorJson",next,"oldEndpoint",FRESH_GW,"newEndpoint",NEXT_GW);
   post("http://127.0.0.1:28290","/v2/projection-control/prepare",t2);seed(NEXT_GW);
   post("http://127.0.0.1:28290","/v2/projection-control/freeze",t2);
   ev("APR_FROZEN_DURING_CATCHUP status="+scopeStatus(28290,"fresh-live"));
   int verifyAttempts=0;HttpResponse<String> v;
   do {v=send("http://127.0.0.1:28290","/v2/projection-control/verify",t2);verifyAttempts++;if(v.statusCode()!=200) Thread.sleep(1000);} while(v.statusCode()!=200 && verifyAttempts<120);
   assertEquals(200,v.statusCode(),v.body());ev("APR_VERIFY_AFTER_AUTOMATIC_DRAIN attempts="+verifyAttempts);
   for(String step:List.of("select","activate")) post("http://127.0.0.1:28290","/v2/projection-control/"+step,t2);
   var sealedRows=jdbc.queryForList("SELECT * FROM trades WHERE projectionscope='fresh-live' ORDER BY id");
   try(var trader=new Trader(NEXT_GW,"p8")) {Thread.sleep(3000);stopGracefully(a);Thread.sleep(3000);a=consumer("tp-a-next",28290,true,null);Thread.sleep(4000);}
   awaitCurrent(28290,"next-live",NEXT_GW,120);
   assertMatchesArchive("next-live",NEXT_GW);
   assertEquals(sealedRows,jdbc.queryForList("SELECT * FROM trades WHERE projectionscope='fresh-live' ORDER BY id"));
   assertEquals(legacyRows,jdbc.queryForList("SELECT * FROM trades WHERE projectionscope='legacy-unknown' ORDER BY id"));
   assertEquals("SEALED",jdbc.queryForObject("SELECT phase FROM projection_runs WHERE projection_scope='fresh-live'",String.class));
   assertEquals(0,count("SELECT count(*) FROM projection_recovery"),"no operator catch-up was invoked");
   ev("APR_FINAL sealedFreshUnchanged=true legacyUnchanged=true operatorCatchUpRows=0 pageLog="+count("SELECT count(*) FROM projection_catchup_log")
     +" pagesWithInsertedTrades="+count("SELECT count(*) FROM projection_catchup_log WHERE inserted_trades>0"));
   Files.writeString(work.resolve("catchup-log.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(jdbc.queryForList("SELECT * FROM projection_catchup_log ORDER BY id")));
   Files.writeString(work.resolve("final-status.json"),status(28290).toPrettyString());
   for(String t:List.of("trades","positions","orderbook","projection_catchup_cursor","projection_runs"))
    Files.writeString(work.resolve("sql-"+t+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(jdbc.queryForList("SELECT * FROM "+t+" ORDER BY 1")));
  } finally {
   for(var p:consumers.values()) if(p.isAlive()){new ProcessBuilder("kill","-CONT",""+p.pid()).start().waitFor();p.destroy();if(!p.waitFor(20,TimeUnit.SECONDS))p.destroyForcibly().waitFor();}
   for(int i=engines.size()-1;i>=0;i--){var p=engines.get(i);p.destroy();if(!p.waitFor(8,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor(5,TimeUnit.SECONDS);}}
   evidence.close();
  }
 }
 long scopeStatus0(){return jdbc.queryForObject("SELECT verified_through_seq FROM projection_catchup_cursor WHERE projection_scope='fresh-live'",Long.class);}
}
