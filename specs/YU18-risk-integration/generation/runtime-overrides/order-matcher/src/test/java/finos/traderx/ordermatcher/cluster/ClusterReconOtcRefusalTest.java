package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.SwapConventions;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.codecs.MessageHeaderEncoder;
import io.aeron.cluster.codecs.SessionMessageHeaderEncoder;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Local synthetic closed-log archive + real shadow apply. No retained cluster or financial model. */
@Timeout(90)
class ClusterReconOtcRefusalTest {
    private static final int ACCOUNT = 22214;
    private static final long TIME = 1_700_000_000_000L;
    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    @TempDir Path directory;

    @Test
    void closedArchiveReportAttributesRefusalsAndPreservesAcceptedRowsAndRanges() throws Exception {
        final List<InputEvent> inputs = new ArrayList<>();
        inputs.add(account(ACCOUNT, true, 1)); // seq 1
        inputs.add(booking(999123, false, 0, 11)); // seq 2 UNKNOWN_ACCOUNT
        inputs.add(account(ACCOUNT, false, 2)); // seq 3
        inputs.add(booking(ACCOUNT, true, 0, 12)); // seq 4 ACCOUNT_DISABLED
        inputs.add(account(ACCOUNT, true, 3)); // seq 5
        inputs.add(booking(ACCOUNT, false, nonUsdConvention(), 13)); // seq 6 PRICE_MISSING
        var fx = new InputEvent(); fx.type=InputEvent.TYPE_FX_RATE;
        fx.securityId=SwapConventions.currencyIndexOfConvention(nonUsdConvention()); fx.limitPx=Long.MAX_VALUE;
        inputs.add(fx); // seq 7 synthetic extreme FX for the existing overflow refusal
        InputEvent tooLarge = booking(ACCOUNT, false, nonUsdConvention(), 14);
        tooLarge.qty = Integer.MAX_VALUE;
        inputs.add(tooLarge); // seq 8 ORDER_NOTIONAL
        inputs.add(booking(ACCOUNT, false, 0, 15)); // seq 9 SWAP_BOOKED
        inputs.add(booking(ACCOUNT, true, 0, 16)); // seq 10 SWAPTION_BOOKED
        inputs.add(booking(ACCOUNT, false, 0, 15)); // seq 11 accepted retry, no extra contract
        inputs.add(booking(999123, false, 0, 11)); // seq 12 refusal retry, original decision
        try (ClosedArchive archive = new ClosedArchive(inputs)) {
            ClusterRecon recon = archive.recon(100);
            var rows = recon.regulatoryReport(0, 12);
            assertEquals(7, rows.size());
            assertEquals(List.of(2L, 4L, 6L, 8L, 9L, 10L, 12L), rows.stream().map(ClusterRecon.AuditRow::inputSeq).toList());
            assertEquals(List.of("UNKNOWN_ACCOUNT", "ACCOUNT_DISABLED", "PRICE_MISSING", "ORDER_NOTIONAL", "ACCEPTED", "ACCEPTED", "UNKNOWN_ACCOUNT"), rows.stream().map(ClusterRecon.AuditRow::riskReason).toList());
            assertEquals("SWAPTION_REJECTED", rows.get(1).kind());
            var first = rows.get(0);
            assertEquals(999123, first.accountId());
            assertEquals("USD-SOFR-1Y-ACT360", first.security());
            assertEquals(11L, first.clientOrderKey());
            assertEquals(1011L, first.requestId());
            assertEquals(TIME + 2, first.timestampMillis());
            assertNull(first.orderId()); assertNull(first.tradeId());
            assertNull(first.quantity()); assertNull(first.price());
            assertEquals("SWAP_BOOKED", rows.get(4).kind());
            assertEquals("SW-9", rows.get(4).orderId());
            assertEquals("SWPT-10", rows.get(5).orderId());
            assertEquals(1, rows.get(4).quantity());
            assertEquals("0.042000", rows.get(4).price().toPlainString());
            ObjectMapper mapper = new ObjectMapper();
            assertEquals(mapper.writeValueAsString(rows), mapper.writeValueAsString(recon.regulatoryReport(0, 12)), "same closed log must reproduce identical JSON");
            assertEquals(rows.subList(1, 5), recon.regulatoryReport(4, 9), "both endpoints inclusive");
            assertEquals(List.of(rows.get(3)), recon.regulatoryReport(8, 8));
            assertEquals(List.of(rows.get(6)), recon.regulatoryReport(12, 0), "zero upper bound retains legacy unbounded behavior");
            assertTrue(recon.regulatoryReport(13, 14).isEmpty());
            var accepted = mapper.valueToTree(rows.get(4));
            assertEquals(11, accepted.size(), "legacy accepted JSON has no optional correlation columns");
            assertFalse(accepted.has("requestId")); assertFalse(accepted.has("clientOrderKey"));
            assertTrue(mapper.valueToTree(first).path("price").isNull());
            var replay = recon.reindexFullHistory();
            assertEquals(inputs.size(), replay.replayedMessages());
            assertEquals(inputs.size(), replay.replayedAppliedSeq());
            assertEquals(0, replay.indexedTrades()); assertEquals(0, replay.shadowTradeCounter());
            assertTrue(recon.fullHistorySince(0, 100).isEmpty(), "OTC refusals never enter trade reconciliation");
            var failure = assertThrows(IllegalStateException.class, () -> archive.recon(6).regulatoryReport(0, 12));
            assertTrue(failure.getMessage().contains("REGULATORY_MAX_RECORDS"));
            assertEquals(7, archive.recon(7).regulatoryReport(0, 12).size(), "exact capacity allowed");
            assertEquals(1, archive.recon(1).regulatoryReport(8, 8).size(), "filtered rows do not spend capacity");
            Path missing = directory.resolve("missing");
            java.nio.file.Files.createDirectories(missing.resolve("cluster"));
            try (var log = new RecordingLog(missing.resolve("cluster").toFile(), true)) { }
            var absent = new ClusterRecon(missing.toFile(), archive.aeronDir, "test", 10, 10, 10);
            assertTrue(assertThrows(IllegalStateException.class, () -> absent.regulatoryReport(0, 0)).getMessage().contains("GENESIS_MISSING"));
        }
    }

    @Test
    void mixedOrderAndOtcLogPreservesLegacyOrderRowsAndProjectionEnvelopes() throws Exception {
        var security = new InputEvent();security.type=InputEvent.TYPE_SECURITY_CONTROL;
        security.securityId=0;security.setControlEnabled(true);security.setControlVersion(1);
        var price = new InputEvent();price.type=InputEvent.TYPE_PRICE_TICK;price.securityId=0;price.priceTicks=100_000_000L;
        var order = new InputEvent();order.type=InputEvent.TYPE_ORDER_NEW;order.accountId=ACCOUNT;
        order.securityId=0;order.side=InputEvent.SIDE_BUY;order.qty=1;order.limitPx=100_000_000L;order.setClientOrderKey(55);
        var cancel = new InputEvent();cancel.type=InputEvent.TYPE_ORDER_CANCEL;cancel.accountId=ACCOUNT;cancel.orderRef=1;
        var inputs=List.of(account(ACCOUNT,true,1),security,price,order,booking(999123,false,0,56),cancel);
        try(ClosedArchive archive=new ClosedArchive(inputs,true)) {
            var recon=archive.recon(100);var rows=recon.regulatoryReport(0,7);
            assertEquals(List.of("ORDER_ACCEPTED","SWAP_REJECTED","ORDER_CANCELED"),rows.stream().map(ClusterRecon.AuditRow::kind).toList());
            assertEquals(List.of(5L,6L,7L),rows.stream().map(ClusterRecon.AuditRow::inputSeq).toList());
            assertEquals("test-1",rows.get(0).orderId());assertEquals(1,rows.get(0).quantity());
            assertEquals(new java.math.BigDecimal("100.000"),rows.get(0).price());
            var mapper=new ObjectMapper();assertEquals(11,mapper.valueToTree(rows.get(0)).size());
            assertFalse(mapper.valueToTree(rows.get(0)).has("requestId"));
            Path descriptorFile=directory.resolve("legacy.json");
            java.nio.file.Files.writeString(descriptorFile,"{\"schema\":\"traderx.run.v1\",\"epoch\":\"test\",\"eventIdScheme\":\"legacy-v0\",\"storageLineage\":\"audit-storage\",\"projectionScope\":\"legacy-unknown\",\"adoptionEvidenceSha256\":\""+"0".repeat(64)+"\"}");
            recon.runDescriptor(RunDescriptor.read(descriptorFile));
            var projection=recon.projectionEvents(true);var events=mapper.valueToTree(projection).path("events");
            assertEquals(7,((Number)projection.get("replayedMessages")).intValue());
            assertEquals(7,((Number)projection.get("replayedAppliedSeq")).intValue());
            assertEquals(2,events.size(),"OTC refusal must not enter order projection protocol");
            assertEquals("OrderUpdate",events.get(0).path("type").asText());
            assertEquals("OrderUpdate",events.get(1).path("type").asText());
            assertEquals("test-1",events.get(0).path("payload").path("id").asText());
        }
    }

    @Test
    void actualContractCapacityRefusalSharesTheReportBudget() throws Exception {
        List<InputEvent> inputs = new ArrayList<>();
        inputs.add(account(ACCOUNT, true, 1));
        // Key zero bypasses the idempotency table; qty one keeps accumulated credit small.
        for (int i = 0; i < MatchingEngineClusteredService.MAX_CONTRACTS + 1; i++) inputs.add(booking(ACCOUNT, false, 0, 0));
        try (ClosedArchive archive = new ClosedArchive(inputs)) {
            long last = inputs.size();
            var rows = archive.recon(1).regulatoryReport(last, last);
            assertEquals(1, rows.size());
            assertEquals("CAPACITY", rows.get(0).riskReason());
            assertEquals(last, rows.get(0).inputSeq());
            assertNull(rows.get(0).clientOrderKey()); assertNull(rows.get(0).requestId());
            assertNull(rows.get(0).orderId()); assertNull(rows.get(0).price());
            assertEquals(last, archive.recon(5000).reindexFullHistory().replayedAppliedSeq());
        }
    }

    @Test
    void installedAndUnsetTapHaveIdenticalSnapshotsRiskCountersOutputsAndEgress() throws Exception {
        var tapped = service(); var plain = service();
        List<MatchingEngineClusteredService.OtcRefusal> decisions = new ArrayList<>();
        tapped.otcRefusalSink(decisions::add);
        List<String> offersA = new ArrayList<>(), offersB = new ArrayList<>();
        var sessionA = captureSession(tapped, offersA); var sessionB = captureSession(plain, offersB);
        List<String> outputsA = new ArrayList<>(), outputsB = new ArrayList<>();
        tapped.outputSink(out -> outputsA.add(out.kind + ":" + out.inputSeq + ":" + out.orderRef + ":" + out.riskReason));
        plain.outputSink(out -> outputsB.add(out.kind + ":" + out.inputSeq + ":" + out.orderRef + ":" + out.riskReason));
        List<InputEvent> inputs = List.of(account(ACCOUNT,true,1), booking(999123,false,0,31),
            booking(ACCOUNT,false,0,32), booking(ACCOUNT,true,0,33), booking(ACCOUNT,false,0,32),
            booking(ACCOUNT,false,nonUsdConvention(),34), account(ACCOUNT,false,2), booking(ACCOUNT,true,0,35));
        for (int i = 0; i < inputs.size(); i++) {
            byte[] wire = wire(inputs.get(i));
            tapped.onSessionMessage(sessionA,TIME+i,new UnsafeBuffer(wire),0,wire.length,null);
            plain.onSessionMessage(sessionB,TIME+i,new UnsafeBuffer(wire),0,wire.length,null);
            assertSnapshotsEqual(plain,tapped);
            assertArrayEquals(ack(plain),ack(tapped));
            assertEquals(plain.appliedSeq(),tapped.appliedSeq());
            assertEquals(plain.nextOrderRef(),tapped.nextOrderRef());
            assertEquals(plain.engine().tradeCounter(),tapped.engine().tradeCounter());
            assertEquals(plain.risk().executedNotional(ACCOUNT),tapped.risk().executedNotional(ACCOUNT));
        }
        assertEquals(3,decisions.size()); assertEquals(2,tapped.contractCount());
        assertEquals(6,offersA.size(), "each actual booking attempts one successful direct egress");
        assertEquals(offersA,offersB, "unchanged delivered ack bytes and offer counts");
        assertEquals(outputsA,outputsB); assertTrue(outputsA.isEmpty(), "no OTC output-ring events introduced");
        assertEquals(List.of("UNKNOWN_ACCOUNT","PRICE_MISSING","ACCOUNT_DISABLED"),decisions.stream().map(d -> ClusterRecon.reasonName(d.reason())).toList());
        // Observation callback failures must surface to replay, after the unchanged decision and ack.
        tapped.otcRefusalSink(d -> {throw new IllegalStateException("budget");});
        InputEvent refused = booking(999999,false,0,99);byte[] wire=wire(refused);
        assertThrows(IllegalStateException.class,()->tapped.onSessionMessage(null,TIME+99,new UnsafeBuffer(wire),0,wire.length,null));
        plain.onSessionMessage(null,TIME+99,new UnsafeBuffer(wire),0,wire.length,null);
        assertSnapshotsEqual(plain,tapped);assertArrayEquals(ack(plain),ack(tapped));
    }

    @Test
    void parserFailuresAndNonSequencedAdmissionBarriersDoNotInventDecisions() throws Exception {
        var service=service();List<MatchingEngineClusteredService.OtcRefusal> decisions=new ArrayList<>();service.otcRefusalSink(decisions::add);
        service.onSessionMessage(null,TIME,new UnsafeBuffer(new byte[8]),0,8,null);
        assertEquals(0,service.appliedSeq());assertTrue(decisions.isEmpty());
        var descriptorFile=directory.resolve("descriptor.json");
        java.nio.file.Files.writeString(descriptorFile,"{\"schema\":\"traderx.run.v1\",\"epoch\":\"audit\",\"eventIdScheme\":\"epoch-v1\",\"storageLineage\":\"audit-storage\",\"projectionScope\":\"audit-scope\",\"adoptionEvidenceSha256\":null}");
        service=new MatchingEngineClusteredService();service.runDescriptor(RunDescriptor.read(descriptorFile));service.initEngine();service.otcRefusalSink(decisions::add);
        byte[] wire=wire(booking(999123,false,0,88));service.onSessionMessage(null,TIME,new UnsafeBuffer(wire),0,wire.length,null);
        assertEquals(0,service.appliedSeq(), "managed admission refusal advances no service sequence");
        assertEquals((byte)finos.traderx.ordermatcher.risk.RiskReason.MARKET_CLOSED.ordinal(),ack(service)[22]);
        assertTrue(decisions.isEmpty(), "do not attribute an unsequenced barrier to an OTC decision");
    }

    @Test
    void unknownConventionAndUncorrelatedRequestsRemainHonest() {
        var service=service();List<MatchingEngineClusteredService.OtcRefusal> decisions=new ArrayList<>();service.otcRefusalSink(decisions::add);
        byte[] wire=wire(booking(ACCOUNT,true,SwapConventions.count()+5,0));
        service.onSessionMessage(null,TIME,new UnsafeBuffer(wire),0,wire.length,null);
        var row=ClusterRecon.otcRefusalRow(decisions.get(0));
        assertEquals("CONVENTION_"+(SwapConventions.count()+5),row.security());
        assertEquals("PRICE_MISSING",row.riskReason());assertEquals("SWAPTION_REJECTED",row.kind());
        assertNull(row.clientOrderKey());assertNull(row.requestId());assertNull(row.quantity());
    }

    private static MatchingEngineClusteredService service() {var s=new MatchingEngineClusteredService();s.initEngine();return s;}
    private static void assertSnapshotsEqual(MatchingEngineClusteredService a,MatchingEngineClusteredService b) {
        List<byte[]> left=snapshot(a),right=snapshot(b);assertFalse(left.isEmpty());assertEquals(left.size(),right.size());
        for(int i=0;i<left.size();i++)assertArrayEquals(left.get(i),right.get(i),"snapshot record "+i);
    }
    private static List<byte[]> snapshot(MatchingEngineClusteredService service) {
        List<byte[]> records=new ArrayList<>();service.writeSnapshot((buffer,offset,length)->{byte[] copy=new byte[length];buffer.getBytes(offset,copy);records.add(copy);});return records;
    }
    private static io.aeron.cluster.service.ClientSession captureSession(MatchingEngineClusteredService service,List<String> offers) throws Exception {
        var idle=MatchingEngineClusteredService.class.getDeclaredField("idle");idle.setAccessible(true);idle.set(service,new org.agrona.concurrent.YieldingIdleStrategy());
        var session=org.mockito.Mockito.mock(io.aeron.cluster.service.ClientSession.class);
        org.mockito.Mockito.when(session.offer(org.mockito.ArgumentMatchers.any(org.agrona.DirectBuffer.class),org.mockito.ArgumentMatchers.eq(0),org.mockito.ArgumentMatchers.eq(MatchingEngineClusteredService.EGRESS_ACK_LENGTH))).thenAnswer(call->{
            byte[] bytes=new byte[MatchingEngineClusteredService.EGRESS_ACK_LENGTH];((org.agrona.DirectBuffer)call.getArgument(0)).getBytes(0,bytes);offers.add(java.util.HexFormat.of().formatHex(bytes));return 1L;
        });return session;
    }
    private static byte[] ack(MatchingEngineClusteredService service) throws Exception {
        var field=MatchingEngineClusteredService.class.getDeclaredField("ackBuffer");field.setAccessible(true);
        byte[] bytes=new byte[MatchingEngineClusteredService.EGRESS_ACK_LENGTH];((UnsafeBuffer)field.get(service)).getBytes(0,bytes);return bytes;
    }
    private byte[] wire(InputEvent input) {var b=new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);codec.encodeInput(b,0,input,input.seq,0,0);byte[] result=new byte[b.capacity()];b.getBytes(0,result);return result;}
    private static InputEvent account(int id,boolean enabled,long version) {var e=new InputEvent();e.type=InputEvent.TYPE_ACCOUNT_CONTROL;e.accountId=id;e.setControlEnabled(enabled);e.setControlVersion(version);return e;}
    private static InputEvent booking(int id,boolean option,int convention,long key) {
        var e=new InputEvent();e.type=option?InputEvent.TYPE_SWAPTION_BOOK:InputEvent.TYPE_SWAP_BOOK;e.accountId=id;e.securityId=convention;e.side=InputEvent.SWAP_RECEIVE_FIXED;e.qty=1;e.limitPx=42000;e.seq=key==0?0:1000+key;e.setClientOrderKey(key);e.setSwapDates(20000,21000);if(option)e.setSwaptionTerms(convention,0,20000);return e;
    }
    private static int nonUsdConvention() {for(int i=0;i<SwapConventions.count();i++)if(SwapConventions.currencyIndexOfConvention(i)!=SwapConventions.currencyIndexOfConvention(0))return i;throw new AssertionError("fixture needs non-USD convention");}
    private static void await(BooleanSupplier ready) {long end=System.nanoTime()+15_000_000_000L;while(!ready.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("owned archive fixture timeout");Thread.yield();}}

    private final class ClosedArchive implements AutoCloseable {
        final String aeronDir=directory.resolve("aeron").toString();
        MediaDriver driver;Archive archive;Aeron aeron;AeronArchive client;
        ClosedArchive(List<InputEvent> inputs) throws Exception {this(inputs,false);}
        ClosedArchive(List<InputEvent> inputs,boolean registerSymbol) throws Exception {
            try {
            driver=MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(aeronDir).dirDeleteOnStart(true).dirDeleteOnShutdown(true).threadingMode(ThreadingMode.SHARED));
            archive=Archive.launch(new Archive.Context().aeronDirectoryName(aeronDir).archiveDir(directory.resolve("archive").toFile())
                .controlChannel("aeron:udp?endpoint=localhost:0").replicationChannel("aeron:udp?endpoint=localhost:0")
                .localControlChannel("aeron:ipc?term-length=64k").recordingEventsEnabled(false).threadingMode(ArchiveThreadingMode.SHARED));
            aeron=Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
            client=AeronArchive.connect(new AeronArchive.Context().aeron(aeron).ownsAeronClient(false)
                .controlRequestChannel("aeron:ipc?term-length=64k").controlRequestStreamId(AeronArchive.Configuration.localControlStreamId())
                .controlResponseChannel("aeron:ipc").controlResponseStreamId(20999));
            final String channel="aeron:ipc?term-length=64k";final int stream=20998;
            client.startRecording(channel,stream,SourceLocation.LOCAL);
            long stop;
            try(Publication publication=aeron.addPublication(channel,stream)) {
                await(publication::isConnected);
                final int prefix=MessageHeaderEncoder.ENCODED_LENGTH+SessionMessageHeaderEncoder.BLOCK_LENGTH;
                List<byte[]> payloads=new ArrayList<>();
                if(registerSymbol) {
                    var symbol=new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
                    codec.encodeSymbolRegister(symbol,0,0,"IBM");byte[] bytes=new byte[symbol.capacity()];symbol.getBytes(0,bytes);payloads.add(bytes);
                }
                for(InputEvent input:inputs)payloads.add(wire(input));
                for(int i=0;i<payloads.size();i++) {
                    byte[] payload=payloads.get(i);var framed=new UnsafeBuffer(new byte[prefix+payload.length]);
                    new SessionMessageHeaderEncoder().wrapAndApplyHeader(framed,0,new MessageHeaderEncoder()).clusterSessionId(1).timestamp(TIME+i+1).leadershipTermId(0);
                    framed.putBytes(prefix,payload);await(()->publication.offer(framed,0,framed.capacity())>0);
                }
                stop=publication.position();
                final long target=stop;final long[] recording={-1};
                await(()->{client.listRecordings(0,100,(cs,co,rid,st,et,sp,ep,it,sfl,tbl,mtu,sid,stid,sc,oc,si)->{if(stid==stream)recording[0]=rid;});return recording[0]>=0;});
                await(()->client.getRecordingPosition(recording[0])>=target);
                client.stopRecording(channel,stream);
            }
            final long end=stop;final long[] id={-1};
            await(()->{client.listRecordings(0,100,(cs,co,rid,st,et,sp,ep,it,sfl,tbl,mtu,sid,stid,sc,oc,si)->{if(stid==stream&&ep==end)id[0]=rid;});return id[0]>=0;});
            java.nio.file.Files.createDirectories(directory.resolve("cluster"));
            try(var log=new RecordingLog(directory.resolve("cluster").toFile(),true)) {log.appendTerm(id[0],0,0,TIME);log.commitLogPosition(0,end);}
            } catch(Exception | AssertionError failure) {close();throw failure;}
        }
        ClusterRecon recon(int max){return new ClusterRecon(directory.toFile(),aeronDir,"test",10,10,max);}
        public void close(){org.agrona.CloseHelper.quietCloseAll(client,aeron,archive,driver);}
    }
}
