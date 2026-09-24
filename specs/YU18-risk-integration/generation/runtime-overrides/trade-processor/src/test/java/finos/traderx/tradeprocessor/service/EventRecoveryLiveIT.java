package finos.traderx.tradeprocessor.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP gateways, Aeron consensus/archive, NATS consumers, Spring controller/JPA and MariaDB.
 * The only fault injection discards a controller reply AFTER its real freeze has committed. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RI06_MATCHER_CLASSPATH_FILE",matches=".+")
@Tag("integration") @Testcontainers @Timeout(360)
class EventRecoveryLiveIT {
 @Container static final MariaDBContainer<?> DB=new MariaDBContainer<>("mariadb:11.4")
  .withCommand("--lower_case_table_names=1").withCreateContainerCmdModifier(c->c.withName("traderx-o1-live-sql").getHostConfig().withMemory(536870912L).withNanoCPUs(500000000L));
 @Container static final GenericContainer<?> NATS=new GenericContainer<>("nats:2.10-alpine").withExposedPorts(4222)
  .withCreateContainerCmdModifier(c->c.withName("traderx-o1-live-nats").getHostConfig().withMemory(134217728L));
 void unusedConfig(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
  r.add("spring.datasource.driverClassName",()->"org.mariadb.jdbc.Driver");r.add("nats.address",()->"nats://"+NATS.getHost()+":"+NATS.getMappedPort(4222));
 }
 JdbcTemplate jdbc; int port; org.springframework.context.ConfigurableApplicationContext app;
 final ObjectMapper json=new ObjectMapper();final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
 final List<Process> children=new ArrayList<>();Path work;
 String controller(){return "http://127.0.0.1:"+port;}
 String oldGateway="http://127.0.0.1:24881",freshGateway="http://127.0.0.1:25881";
 static final String TOKEN="ri06-proof-token";
 static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
 String descriptor(String epoch,String scope,boolean legacy,String evidence) throws Exception {
  return json.writeValueAsString(Map.of("schema","traderx.run.v1","epoch",epoch,"eventIdScheme",legacy?"legacy-v0":"epoch-v1",
   "storageLineage",scope+"-storage","projectionScope",scope,"adoptionEvidenceSha256",legacy?evidence:"NULL")).replace("\"NULL\"","null");
 }
 void launch(String name,String main,Map<String,String> env) throws Exception {
  String cp=Files.readString(Path.of(Objects.requireNonNull(System.getenv("RI06_MATCHER_CLASSPATH_FILE"),"run the documented live-proof launcher"))).trim();
  var command=List.of(System.getProperty("java.home")+"/bin/java","-Xms64m","-Xmx384m","--add-opens=java.base/sun.nio.ch=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","-cp",cp,main);
  var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(work.resolve(name+".log").toFile());
  builder.environment().clear();builder.environment().putAll(env);children.add(builder.start());
 }
 void startRun(String name,String raw,int base,int health,int gateway) throws Exception {
  Path storage=work.resolve(name+"-storage"),identity=storage.resolve("run-identity.json");Files.createDirectories(storage);Files.writeString(identity,raw);
  if(name.equals("old")) Files.writeString(storage.resolve("run-adoption-evidence.json"),"synthetic archived legacy source evidence");
  Map<String,String> common=new HashMap<>();common.put("RUN_DESCRIPTOR_PATH",identity.toString());common.put("CLUSTER_EPOCH",name.equals("old")?"oldlive":"freshlive");
  common.put("RISK_CONTROL_TOKEN",TOKEN);
  var node=new HashMap<>(common);node.put("CLUSTER_MEMBER_ID","0");node.put("CLUSTER_HOSTNAMES","localhost");node.put("CLUSTER_PORT_BASE",""+base);
  node.put("CLUSTER_BASE_DIR",storage.toString());node.put("CLUSTER_AERON_DIR",work.resolve(name+"-aeron").toString());node.put("HEALTH_PORT",""+health);
  node.put("CLUSTER_IDLE_SLEEP_MS","1");node.put("RECON_BLOTTER_CAPACITY","1000");node.put("RECON_FULL_HISTORY_MAX","1000");node.put("REGULATORY_MAX_RECORDS","1000");
  node.put("TRADE_BRIDGE_NATS_URL","nats://"+NATS.getHost()+":"+NATS.getMappedPort(4222));
  launch(name+"-node","finos.traderx.ordermatcher.cluster.ClusterNodeMain",node);
  await(()->ready("http://127.0.0.1:"+health+"/health"));
  var gate=new HashMap<>(common);gate.put("GATEWAY_INGRESS_ENDPOINTS","0=localhost:"+(base+2));gate.put("GATEWAY_HTTP_PORT",""+gateway);
  gate.put("GATEWAY_PROBE_PORT",""+(gateway+1));gate.put("GATEWAY_MEMBER_HEALTH_PORT",""+health);gate.put("GATEWAY_AERON_DIR",work.resolve(name+"-gateway-aeron").toString());
  gate.put("RUN_PROJECTION_CONTROL_URL",controller());launch(name+"-gateway","finos.traderx.ordermatcher.cluster.ClusterGatewayMain",gate);
  await(()->ready("http://127.0.0.1:"+gateway+"/run/status"));
 }
 boolean ready(String uri){try{return http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200;}catch(Exception e){return false;}}
 void await(BooleanSupplier condition) throws Exception {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
  while(!condition.getAsBoolean()) {for(Process p:children) assertTrue(p.isAlive(),"child process exited; inspect "+work);assertTrue(System.nanoTime()<deadline,"timeout; inspect "+work);Thread.sleep(20);}
 }
 JsonNode post(String base,String path,Object body) throws Exception {
  var response=http.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(60))
   .header("Content-Type","application/json").header("X-Risk-Control-Token",TOKEN).header("X-Risk-Operator","local-live-proof")
   .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(200,response.statusCode(),path+": "+response.body());return json.readTree(response.body());
 }
 void seed(String gateway) throws Exception {for(int account:List.of(11,12))post(gateway,"/seed",Map.of("accountId",account,"tickers","RESERVED,IBM","price",100));}
 void trade(String gateway,int quantity,String key) throws Exception {
  post(gateway,"/orders",Map.of("accountId",11,"security","IBM","side","Buy","quantity",quantity,"limitPrice",100,"clientOrderId",key+"buy"));
  post(gateway,"/orders",Map.of("accountId",12,"security","IBM","side","Sell","quantity",quantity,"limitPrice",100,"clientOrderId",key+"sell"));
 }
 void startConsumer(boolean fresh) {
  app=new org.springframework.boot.builder.SpringApplicationBuilder(finos.traderx.tradeprocessor.TradeProcessorApplication.class)
   .run("--server.port=0","--spring.jpa.hibernate.ddl-auto="+(fresh?"create":"none"),"--spring.sql.init.mode=never",
    "--spring.datasource.url="+DB.getJdbcUrl(),"--spring.datasource.username="+DB.getUsername(),"--spring.datasource.password="+DB.getPassword(),
    "--spring.datasource.driverClassName=org.mariadb.jdbc.Driver","--nats.address=nats://"+NATS.getHost()+":"+NATS.getMappedPort(4222),
    "--RISK_CONTROL_TOKEN="+TOKEN,"--recon.poll.interval-ms=3600000","--order-matcher.base-url=http://127.0.0.1:1");
  jdbc=app.getBean(JdbcTemplate.class);port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)app).getWebServer().getPort();
 }
 @Test void consumerOutageLosesEventsAndRestartDoesNotCatchUp() throws Exception {
  work=Path.of(System.getenv().getOrDefault("RI06_LIVE_PROOF_DIR","build/o1-live-proof")).toAbsolutePath();Files.createDirectories(work);
  try {
   startConsumer(true);
   try(var c=jdbc.getDataSource().getConnection()) {org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.FileSystemResource("../postgres-database-replacement/mariadb-migrations/ri06.sql"));}
   String legacy=descriptor("oldlive","legacy-unknown",true,hash("synthetic archived legacy source evidence".getBytes()));
   startRun("old",legacy,24800,24880,24881);seed(oldGateway);trade(oldGateway,10,"before-");
   await(()->jdbc.queryForObject("SELECT count(*) FROM trades",Integer.class)==2);
   app.close();app=null;
   trade(oldGateway,20,"during-");
   startConsumer(false);
   trade(oldGateway,30,"after-");
   await(()->jdbc.queryForObject("SELECT count(*) FROM trades",Integer.class)==4 && jdbc.queryForObject("SELECT count(*) FROM orderbook",Integer.class)==4);
   assertEquals(40,jdbc.queryForObject("SELECT quantity FROM positions WHERE accountid=11",Integer.class));
   assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM orderbook",Integer.class));
   System.out.println("O1_BASELINE realConsumerClosedAndRestarted=true expectedTradeLegs=6 actualTradeLegs=4 expectedBuyQuantity=60 actualBuyQuantity=40 missedOrders=2");
  } finally {
   if(app!=null)app.close();
   for(int i=children.size()-1;i>=0;i--) {var p=children.get(i);p.destroy();if(!p.waitFor(8,TimeUnit.SECONDS)) {p.destroyForcibly();assertTrue(p.waitFor(5,TimeUnit.SECONDS));}}
  }
 }

 @Test void managedConsumerAndPublisherOutagesRecoverFromArchive() throws Exception {
  work=Path.of(System.getenv().getOrDefault("RI06_LIVE_PROOF_DIR","build/o1-managed-proof")).toAbsolutePath();Files.createDirectories(work);
  try {
   startConsumer(true);
   try(var c=jdbc.getDataSource().getConnection()) {
    for(String migration:List.of("ri06.sql","ri06-event-recovery.sql")) org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.FileSystemResource("../postgres-database-replacement/mariadb-migrations/"+migration));
   }
   String legacy=descriptor("oldlive","legacy-unknown",true,hash("synthetic archived legacy source evidence".getBytes()));
   String fresh=descriptor("freshlive","fresh-live",false,null);
   startRun("old",legacy,24800,24880,24881);startRun("fresh",fresh,25800,25880,25881);
   seed(oldGateway);trade(oldGateway,10,"old-");
   await(()->jdbc.queryForObject("SELECT count(*) FROM trades",Integer.class)==2 && jdbc.queryForObject("SELECT count(*) FROM orderbook WHERE status='FILLED'",Integer.class)==2);
   post(controller(),"/v2/projection-control/adopt-legacy",Map.of("descriptorJson",legacy,"oldEndpoint",oldGateway));
   Map<String,String> request=Map.of("transitionId","o1-migration","oldScope","legacy-unknown","descriptorJson",fresh,"oldEndpoint",oldGateway,"newEndpoint",freshGateway);
   post(controller(),"/v2/projection-control/prepare",request);seed(freshGateway);
   for(String step:List.of("freeze","verify","select","activate"))post(controller(),"/v2/projection-control/"+step,request);
   var legacyTrades=jdbc.queryForList("SELECT * FROM trades WHERE projectionscope='legacy-unknown' ORDER BY id");
   trade(freshGateway,10,"before-");
   await(()->jdbc.queryForObject("SELECT count(*) FROM trades WHERE projectionscope='fresh-live'",Integer.class)==2 && jdbc.queryForObject("SELECT count(*) FROM orderbook WHERE projectionscope='fresh-live' AND status='FILLED'",Integer.class)==2);
   var retained=jdbc.queryForList("SELECT * FROM trades WHERE projectionscope='fresh-live' ORDER BY id");
   app.close();app=null;
   trade(freshGateway,20,"consumer-off-");
   startConsumer(false);
   trade(freshGateway,30,"after-");
   await(()->jdbc.queryForObject("SELECT count(*) FROM trades WHERE projectionscope='fresh-live'",Integer.class)==4 && jdbc.queryForObject("SELECT count(*) FROM orderbook WHERE projectionscope='fresh-live' AND status='FILLED'",Integer.class)==4);
   assertEquals(40,jdbc.queryForObject("SELECT quantity FROM positions WHERE projectionscope='fresh-live' AND accountid=11",Integer.class));
   System.out.println("O1_MANAGED_GAP expectedTradeLegs=6 actual=4 expectedQuantity=60 actual=40 realConsumerRestart=true");
   var recovery=Map.of("projectionScope","fresh-live","endpoint",freshGateway);
   JsonNode first=post(controller(),"/v2/projection-recovery/catch-up",recovery);
   assertEquals(6,jdbc.queryForObject("SELECT count(*) FROM trades WHERE projectionscope='fresh-live'",Integer.class));
   assertEquals(6,jdbc.queryForObject("SELECT count(*) FROM orderbook WHERE projectionscope='fresh-live' AND status='FILLED'",Integer.class));
   assertEquals(60,jdbc.queryForObject("SELECT quantity FROM positions WHERE projectionscope='fresh-live' AND accountid=11",Integer.class));
   assertEquals(-60,jdbc.queryForObject("SELECT quantity FROM positions WHERE projectionscope='fresh-live' AND accountid=12",Integer.class));
   assertEquals(retained,jdbc.queryForList("SELECT * FROM trades WHERE projectionscope='fresh-live' AND consensussequence<=(SELECT MIN(consensussequence) FROM trades WHERE id='e1-freshlive-2-S') ORDER BY id"));
   var after=jdbc.queryForList("SELECT * FROM trades ORDER BY id");
   app.close();app=null;startConsumer(false); // Committed checkpoint must survive actual service restart.
   assertEquals(first,post(controller(),"/v2/projection-recovery/catch-up",recovery));
   assertEquals(after,jdbc.queryForList("SELECT * FROM trades ORDER BY id"));
   // Publisher-side outage: keep consumers running, remove the actual transport while matching.
   var docker=org.testcontainers.DockerClientFactory.instance().client();
   docker.stopContainerCmd(NATS.getContainerId()).withTimeout(2).exec();
   trade(freshGateway,40,"publisher-off-");
   docker.startContainerCmd(NATS.getContainerId()).exec();
   // Catch-up reads the archive independently of NATS reconnection or publisher buffering.
   post(controller(),"/v2/projection-recovery/catch-up",recovery);
   assertEquals(8,jdbc.queryForObject("SELECT count(*) FROM trades WHERE projectionscope='fresh-live'",Integer.class));
   assertEquals(100,jdbc.queryForObject("SELECT quantity FROM positions WHERE projectionscope='fresh-live' AND accountid=11",Integer.class));
   assertEquals(8,jdbc.queryForObject("SELECT count(*) FROM orderbook WHERE projectionscope='fresh-live' AND status='FILLED'",Integer.class));
   assertEquals(legacyTrades,jdbc.queryForList("SELECT * FROM trades WHERE projectionscope='legacy-unknown' ORDER BY id"));
   System.out.println("O1_RECOVERED consumerOutage=true publisherTransportOutage=true tradeLegs=8 finalOrders=8 buyQuantity=100 sellQuantity=-100 checkpointRestart=true legacyHistoryUnchanged=true");
  } finally {
   if(app!=null)app.close();
   for(int i=children.size()-1;i>=0;i--) {var p=children.get(i);p.destroy();if(!p.waitFor(8,TimeUnit.SECONDS)) {p.destroyForcibly();assertTrue(p.waitFor(5,TimeUnit.SECONDS));}}
  }
 }
}
