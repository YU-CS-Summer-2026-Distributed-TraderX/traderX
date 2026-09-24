package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RunIdentityTest {
 @TempDir Path root;
 RunDescriptor descriptor(String epoch) throws Exception {
  Path p=root.resolve(epoch+".json");
  Files.writeString(p,"{\"schema\":\"traderx.run.v1\",\"epoch\":\""+epoch+"\",\"eventIdScheme\":\"epoch-v1\",\"storageLineage\":\"storage-"+epoch+"\",\"projectionScope\":\"scope-"+epoch+"\",\"adoptionEvidenceSha256\":null}");
  return RunDescriptor.read(p);
 }
 MatchingEngineClusteredService service(RunDescriptor d) {
  var s=new MatchingEngineClusteredService();s.runDescriptor(d);s.initEngine();return s;
 }
 void apply(MatchingEngineClusteredService s,InputEvent e) {
  var b=new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
  new AeronReplicationCodec().encodeInput(b,0,e,100,0,0);
  s.onSessionMessage(null,1000,b,0,b.capacity(),null);
 }
 void control(MatchingEngineClusteredService s,RunDescriptor d,int op) {
  var e=new InputEvent();d.control(e,(byte)op);apply(s,e);
 }
 List<byte[]> snapshot(MatchingEngineClusteredService s) {
  List<byte[]> records=new ArrayList<>();
  s.writeSnapshot((b,o,n)->{byte[] r=new byte[n];b.getBytes(o,r);records.add(r);});return records;
 }
 void restore(MatchingEngineClusteredService s,List<byte[]> records) {
  for(byte[] r:records) s.onSnapshotRecord(new UnsafeBuffer(r),0);
 }
 @Test void gatewayRequiresSelectedSqlWitnessAndMatchingScopeBeforeActivation() throws Exception {
  var d=descriptor("gate");var gateway=new ClusterGatewayMain();
  var f=ClusterGatewayMain.class.getDeclaredField("runDescriptor");f.setAccessible(true);f.set(gateway,d);
  var body=new java.util.concurrent.atomic.AtomicReference<String>("{}");
  var status=new java.util.concurrent.atomic.AtomicInteger(200);
  var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/v2/projection-control/activation/"+d.hash(),e->{
   if(!"token".equals(e.getRequestHeaders().getFirst("X-Risk-Control-Token"))) {e.sendResponseHeaders(401,-1);e.close();return;}
   byte[] bytes=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
   e.sendResponseHeaders(status.get(),bytes.length);e.getResponseBody().write(bytes);e.close();
  });server.start();
  String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
  try {
   assertFalse(gateway.projectionSelected("","token","test"));
   assertFalse(gateway.projectionSelected("https://example.com","token","test"));
   assertFalse(gateway.projectionSelected(endpoint,"token","test"));
   String valid="{\"descriptor_hash\":\""+d.hash()+"\",\"new_scope\":\"scope-gate\",\"witness_hash\":\""+"a".repeat(64)+"\",\"phase\":\"SELECTED\"}";
   body.set(valid);assertTrue(gateway.projectionSelected(endpoint,"token","test"));
   assertFalse(gateway.projectionSelected(endpoint,"wrong","test"));
   body.set(valid.replace("scope-gate","scope-other"));assertFalse(gateway.projectionSelected(endpoint,"token","test"));
   body.set(valid.replace("SELECTED","VERIFIED"));assertFalse(gateway.projectionSelected(endpoint,"token","test"));
   body.set(valid);status.set(409);assertFalse(gateway.projectionSelected(endpoint,"token","test"));
  } finally {server.stop(0);}
 }
 @Test void descriptorMustAlreadyBindStorageAndConfiguredIdentityMustMatch() throws Exception {
  descriptor("old");descriptor("fresh");Path store=Files.createDirectory(root.resolve("store"));
  assertThrows(IllegalStateException.class,()->RunDescriptor.load(store,root.resolve("old.json").toString(),"old"));
  Files.copy(root.resolve("old.json"),store.resolve(RunDescriptor.FILE));
  assertEquals(descriptor("old").hash(),RunDescriptor.load(store,null,"old").hash());
  assertThrows(IllegalStateException.class,()->RunDescriptor.load(store,root.resolve("fresh.json").toString(),"fresh"));
  assertThrows(IllegalStateException.class,()->RunDescriptor.load(store,null,"fresh"));
 }
 @Test void maximalV1KeyFitsAllFiftyCharacterColumns() throws Exception {
  var d=descriptor("a".repeat(25));
  assertEquals(50,d.tradeId(Long.MAX_VALUE,(byte)0).length());
  assertTrue(d.orderId(Integer.MAX_VALUE).length()<=50);
  assertNotEquals(d.tradeId(1,(byte)0),descriptor("fresh").tradeId(1,(byte)0));
  assertThrows(IllegalArgumentException.class,()->descriptor("a".repeat(26)));
  assertThrows(IllegalArgumentException.class,()->descriptor("MixedCase"));
 }
 @Test void activeSnapshotAndFrozenTailKeepIdentityAndCannotReopen() throws Exception {
  var d=descriptor("old");var s=service(d);
  assertEquals(0,s.runPhase());control(s,d,1);control(s,d,2);assertEquals(2,s.runPhase());
  var records=snapshot(s);assertTrue(records.size()>5);
  var restored=service(d);restore(restored,records);assertEquals(s.appliedSeq(),restored.appliedSeq());
  assertEquals(2,restored.runPhase());control(restored,d,3);
  long frozen=restored.frozenRunSequence();assertTrue(frozen>0);
  var again=service(d);restore(again,snapshot(restored));
  assertEquals(3,again.runPhase());assertEquals(frozen,again.frozenRunSequence());
  control(again,d,2);assertEquals(3,again.runPhase());assertEquals(frozen,again.frozenRunSequence());
  assertThrows(IllegalStateException.class,()->restore(service(descriptor("fresh")),records));
  assertThrows(IllegalStateException.class,()->restore(service(null),records));
 }
 @Test void unboundAndFrozenAdmissionDoNotAllocateOrderIds() throws Exception {
  var d=descriptor("old");var s=service(d);var e=new InputEvent();
  e.type=InputEvent.TYPE_ORDER_NEW;e.accountId=1;e.securityId=1;e.qty=10;e.limitPx=100;
  long before=s.appliedSeq();apply(s,e);assertEquals(before,s.appliedSeq());
  control(s,d,1);control(s,d,2);control(s,d,3);before=s.appliedSeq();
  apply(s,e);assertEquals(before,s.appliedSeq());assertTrue(s.engine().openOrderTuples().isEmpty());
 }
 @Test void descriptorCannotChangeThroughControlOrLegacySnapshot() throws Exception {
  var old=descriptor("old");var fresh=descriptor("fresh");var s=service(old);
  assertThrows(IllegalStateException.class,()->control(s,fresh,1));assertEquals(0,s.runPhase());
  assertThrows(IllegalStateException.class,()->restore(service(fresh),snapshot(service(null))));
 }
 @Test void evidencedLegacyBindingPreservesLongEpochAndHistoricalWireIdsThroughSnapshot() throws Exception {
  String epoch="historical_epoch_more_than_twenty_five";byte[] evidence="archived source evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  String evidenceHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(evidence));
  Path store=Files.createDirectory(root.resolve("legacy-store"));Files.write(store.resolve("run-adoption-evidence.json"),evidence);
  Files.writeString(store.resolve(RunDescriptor.FILE),"{\"schema\":\"traderx.run.v1\",\"epoch\":\""+epoch+"\",\"eventIdScheme\":\"legacy-v0\",\"storageLineage\":\"retained\",\"projectionScope\":\"legacy-unknown\",\"adoptionEvidenceSha256\":\""+evidenceHash+"\"}");
  var d=RunDescriptor.load(store,null,epoch);assertFalse(d.managedIds());assertEquals("1-B",d.tradeId(1,(byte)0));
  var adopted=service(d);restore(adopted,snapshot(service(null)));assertEquals(2,adopted.runPhase());
  var restarted=service(RunDescriptor.load(store,null,epoch));restore(restarted,snapshot(adopted));assertEquals(2,restarted.runPhase());
  var out=new OutputEvent();out.tradeSeq=1;out.orderRef=7;out.accountId=11;out.securityId=1;out.tradeQty=10;out.tradePx=100_000_000L;out.inputSeq=23;
  var trade=new com.fasterxml.jackson.databind.ObjectMapper().readTree(TradeNatsPublisher.projectionEvent(out,"IBM",d)).path("payload");
  assertEquals("1-B",trade.path("id").asText());assertEquals(epoch+"-7",trade.path("sourceOrderId").asText());assertFalse(trade.has("runDescriptorHash"));
  Files.writeString(store.resolve("run-adoption-evidence.json"),"different evidence");
  assertThrows(IllegalStateException.class,()->RunDescriptor.load(store,null,epoch));
 }
 @Test void realPublisherSerializersShareOneManagedOrderIdentity() throws Exception {
  var d=descriptor("wire");var out=new OutputEvent();out.tradeSeq=1;out.orderRef=7;out.accountId=11;
  out.securityId=1;out.side=InputEvent.SIDE_BUY;out.tradeQty=10;out.tradePx=100_000_000L;out.inputSeq=23;
  out.quantity=10;out.remainingQty=10;out.limitPx=100_000_000L;
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  var trade=mapper.readTree(TradeNatsPublisher.projectionEvent(out,"IBM",d)).path("payload");
  var order=mapper.readTree(OrderNatsPublisher.projectionEvent(out,"IBM",d,4)).path("payload");
  assertEquals("e1-wire-1-B",trade.path("id").asText());assertEquals("wire-7",order.path("id").asText());
  assertEquals(order.path("id"),trade.path("sourceOrderId"));assertEquals(d.hash(),trade.path("runDescriptorHash").asText());
  assertEquals(trade.path("runDescriptorHash"),order.path("runDescriptorHash"));
  assertEquals(23,trade.path("consensusSequence").asLong());assertEquals(23,order.path("consensusSequence").asLong());
  assertEquals(4,order.path("outputOrdinal").asInt());
 }
 @Test void managedReceiptRequiresTheDescriptorCarriedByTheActualCut() throws Exception {
  var d=descriptor("receipt");var other=descriptor("other");
  String legacy="#cut schema=3 seq=42 sessionDateEpochDay=20241 priceVersion=1\nheader\n";
  String cut=legacy.replace("priceVersion=1","priceVersion=1 runDescriptorHash="+d.hash()+" projectionScope="+d.projectionScope()+" eventIdScheme="+d.scheme());
  assertEquals(d.hash(),RiskExtractReady.descriptorForCut(cut,root.resolve("receipt.json").toString()).hash());
  assertThrows(IllegalArgumentException.class,()->RiskExtractReady.descriptorForCut(cut,""));
  assertThrows(IllegalArgumentException.class,()->RiskExtractReady.descriptorForCut(cut,root.resolve("other.json").toString()));
  assertThrows(IllegalArgumentException.class,()->RiskExtractReady.descriptorForCut(legacy,root.resolve("receipt.json").toString()));
  assertNull(RiskExtractReady.descriptorForCut(legacy,""));
  var stamp=new RiskExtractCsv.Stamp(42,java.time.LocalDate.of(2025,6,2),1,RiskExtractCut.sha256(cut));
  var json=new org.json.JSONObject(RiskExtractReady.payload(stamp,43,"header\n","header\n","file:///tmp/seq-42.csv","file:///tmp/seq-42-contracts.csv",d));
  assertEquals("traderx.risk-extract.ready.v2",json.getString("receiptSchema"));
  assertEquals("file:///tmp/seq-42.cut",json.getString("cutUri"));
  assertEquals(d.hash(),json.getJSONObject("platformIdentity").getString("runDescriptorSha256"));
 }
 @Test void consensusExtractHashBindsDescriptorInsteadOfOnlyCallerMetadata() throws Exception {
  var d=descriptor("cut");var s=service(d);control(s,d,1);control(s,d,2);
  var buffer=new UnsafeBuffer(new byte[AeronReplicationCodec.RISK_EXTRACT_BYTES]);
  new AeronReplicationCodec().encodeRiskExtract(buffer,0,7,20241,1);
  s.onSessionMessage(null,1000,buffer,0,buffer.capacity(),null);
  String bare=RiskExtractCut.render(s.lastExtractCutSeq(),20241,1,List.of(),List.of(),new String[1024],id->1,List.of());
  int newline=bare.indexOf('\n');String bound=bare.substring(0,newline)+" runDescriptorHash="+d.hash()
      +" projectionScope="+d.projectionScope()+" eventIdScheme="+d.scheme()+bare.substring(newline);
  assertNotEquals(RiskExtractCut.sha256(bare),s.lastExtractCutSha());assertEquals(RiskExtractCut.sha256(bound),s.lastExtractCutSha());
 }

}
