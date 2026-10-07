package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.*;
import io.aeron.DirectBufferVector;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic transport, real public submit/offer/encode/service apply/egress/completion paths. */
@Timeout(30)
class GatewayAckMetricsTest {
    static final long SESSION=7, PRICE=150_000_000L;
    static final int A=42422, B=22214;
    final ClusterGatewayMain gateway=new ClusterGatewayMain();
    final MatchingEngineClusteredService service=new MatchingEngineClusteredService();
    final AeronReplicationCodec codec=new AeronReplicationCodec();
    final UnsafeBuffer ingress=new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    final Queue<byte[]> records=new ArrayDeque<>();
    final List<byte[]> observed=new ArrayList<>();
    final ExecutorService submitter=Executors.newSingleThreadExecutor();
    Queue<FutureTask<?>> tasks;
    Method egress;
    long timestamp=1_000_000_000_000L;
    long keys;

    @SuppressWarnings("unchecked")
    @BeforeEach void setup() throws Exception {
        service.initEngine();
        egress=ClusterGatewayMain.class.getDeclaredMethod("onEgress",long.class,long.class,DirectBuffer.class,int.class,int.class,io.aeron.logbuffer.Header.class);
        egress.setAccessible(true);
        tasks=(Queue<FutureTask<?>>)field("tasks").get(gateway);
        ((Map<String,Integer>)field("idByTicker").get(gateway)).put("IBM",0);
        AeronCluster client=mock(AeronCluster.class);
        when(client.clusterSessionId()).thenReturn(SESSION);
        when(client.offer(any(DirectBuffer.class),anyInt(),anyInt())).thenAnswer(call->{
            service.onSessionMessage(new Capture(),++timestamp,call.getArgument(0),call.getArgument(1),call.getArgument(2),null);
            return 1L;
        });
        when(client.pollEgress()).thenAnswer(call->{poll();return 1;});
        field("client").set(gateway,client);
        for(int account:new int[]{A,B}) {
            InputEvent seed=new InputEvent();seed.type=InputEvent.TYPE_ACCOUNT_CONTROL;seed.accountId=account;
            seed.setControlEnabled(true);seed.setControlVersion(1);apply(seed);
        }
        InputEvent seed=new InputEvent();seed.type=InputEvent.TYPE_SECURITY_CONTROL;seed.securityId=0;
        seed.setControlEnabled(true);seed.setControlVersion(1);apply(seed);
        seed=new InputEvent();seed.type=InputEvent.TYPE_PRICE_TICK;seed.securityId=0;seed.priceTicks=PRICE;apply(seed);
        poll();observed.clear();
    }
    @AfterEach void close() throws Exception {
        submitter.shutdownNow();
        assertTrue(submitter.awaitTermination(3,TimeUnit.SECONDS), "synthetic submitter must stop");
        assertEquals(0,inflight().depth(), "every test must leave all permits returned");
    }

    @Test void sequentialTwoAccountCrossSeparatesKnownContinuationAndKeepsLegacyTotal() throws Exception {
        assertTrue(submit(A,'B',10,PRICE).accepted());
        assertTrue(submit(B,'S',10,PRICE).accepted());
        assertEquals(2,metric("pipelineAcksCompleted"));
        assertEquals(1,metric("pipelineAcksUnmatched"));
        assertEquals(2,service.engine().tradeCounter());
        assertEquals(1,reason("continuation"));assertEquals(0,reason("unknown"));
        assertEquals(0,inflight().depth());
        System.out.println("RI28_AFTER two_accounts completed=2 legacy_unmatched=1 continuation=1 trade_legs=2");
    }
    @Test void sameAccountStpControlProducesNoContinuation() throws Exception {
        assertTrue(submit(A,'B',10,PRICE).accepted());
        submit(A,'S',10,PRICE);
        assertEquals(2,metric("pipelineAcksCompleted"));assertEquals(0,metric("pipelineAcksUnmatched"));
        assertEquals(0,service.engine().tradeCounter());assertEquals(0,inflight().depth());
        assertTrue(observed.stream().anyMatch(r->r[12]==OutputEvent.KIND_ORDER_CANCELED && r[21]!=0
            && r[22]==finos.traderx.ordermatcher.risk.RiskReason.SELF_TRADE_PREVENTED.ordinal()),
            "same-account control must exercise actual STP, not another refusal");
    }
    @Test void nonCrossingTwoAccountControlProducesNoContinuation() throws Exception {
        assertTrue(submit(A,'B',10,PRICE).accepted());
        assertTrue(submit(B,'S',10,PRICE+1_000_000L).accepted());
        assertEquals(2,metric("pipelineAcksCompleted"));assertEquals(0,metric("pipelineAcksUnmatched"));
        assertEquals(0,service.engine().tradeCounter());assertEquals(0,inflight().depth());
    }
    @Test void multipleMatchStepsKeepIndistinguishablePartialRecordsUnknown() throws Exception {
        submit(A,'S',2,PRICE);submit(A,'S',2,PRICE);submit(A,'S',2,PRICE);
        submit(B,'B',6,PRICE);
        assertEquals(4,metric("pipelineAcksCompleted"));assertEquals(3,metric("pipelineAcksUnmatched"));
        assertEquals(2,reason("continuation"));assertEquals(1,reason("unknown"));assertEquals(0,reason("duplicate"));
        assertEquals(6,service.engine().tradeCounter());assertEquals(0,inflight().depth());
        var fills=observed.stream().filter(b->new UnsafeBuffer(b).getByte(12)==OutputEvent.KIND_ORDER_PARTIALLY_FILLED
            &&new UnsafeBuffer(b).getByte(21)==0).toList();
        assertEquals(2,fills.size());assertArrayEquals(fills.get(0),fills.get(1),"wire has no match-step identity");
    }
    @Test void directFilledFirstStillCompletesAndDoesNotBecomeContinuation() throws Exception {
        var p=pending(100,Long.MAX_VALUE);register(p);
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_FILLED));
        assertTrue(p.future.get().accepted());assertEquals(20,p.future.get().orderRef());
        assertEquals(1,metric("pipelineAcksCompleted"));assertEquals(0,metric("pipelineAcksUnmatched"));
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_FILLED));
        assertEquals(1,reason("duplicate"));assertEquals(0,reason("continuation"));assertEquals(0,inflight().depth());
    }
    @Test void exactAcceptedAndTerminalReplaysAreDuplicates() throws Exception {
        submit(A,'B',10,PRICE);submit(B,'S',10,PRICE);
        byte[] accepted=directRecord(OutputEvent.KIND_ORDER_ACCEPTED,1);
        byte[] filled=directRecord(OutputEvent.KIND_ORDER_FILLED,2);
        deliver(SESSION,accepted);deliver(SESSION,filled);
        assertEquals(2,reason("duplicate"));assertEquals(1,reason("continuation"));
        assertEquals(2,metric("pipelineAcksCompleted"));assertEquals(0,inflight().depth());
    }
    @Test void repeatedPartialCouldBeDuplicateOrAnotherFill() throws Exception {
        submit(A,'S',2,PRICE);submit(B,'B',5,PRICE);
        byte[] partial=directRecord(OutputEvent.KIND_ORDER_PARTIALLY_FILLED,2);
        deliver(SESSION,partial);
        assertEquals(1,reason("continuation"));assertEquals(1,reason("unknown"));assertEquals(0,reason("duplicate"));
        assertEquals(2,metric("pipelineAcksCompleted"));assertEquals(0,inflight().depth());
    }
    @Test void foreignUnknownFillNeverCompletesAnotherPending() throws Exception {
        var p=pending(100,Long.MAX_VALUE);register(p);
        deliver(SESSION,ack(999,10,20,OutputEvent.KIND_ORDER_FILLED));
        assertFalse(p.future.isDone());assertEquals(1,inflight().depth());
        assertEquals(1,reason("unknown"));assertEquals(0,reason("continuation"));
        deliver(SESSION,ack(100,11,21,OutputEvent.KIND_ORDER_ACCEPTED));
        assertEquals(21,p.future.get().orderRef());assertEquals(0,inflight().depth());
    }
    @Test void zeroRequestAndRestingRecordsNeverEnterMissingPendingMetrics() throws Exception {
        var p=pending(100,Long.MAX_VALUE);register(p);
        deliver(SESSION,ack(0,10,20,OutputEvent.KIND_ORDER_FILLED));
        byte[] resting=ack(100,10,20,OutputEvent.KIND_ORDER_FILLED);resting[21]=1;deliver(SESSION,resting);
        assertFalse(p.future.isDone());assertEquals(0,metric("pipelineAcksUnmatched"));assertEquals(0,reason("continuation"));
        inflight().drain();assertEquals(0,inflight().depth());
    }
    @Test void knownReapedLateAckRemainsExceptionalAndReleasesNoSecondPermit() throws Exception {
        var p=pending(100,0);register(p);
        assertEquals(1,inflight().sweepOverdue(1));assertNull(p.future.get());
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_FILLED));
        assertEquals(1,reason("late_reaped"));assertEquals(0,reason("continuation"));
        assertEquals(0,metric("pipelineAcksCompleted"));assertEquals(0,inflight().depth());
        assertEquals(0,inflight().sweepOverdue(Long.MAX_VALUE));
    }
    @Test void sameSessionDrainRetainsLateDrainedEvidence() throws Exception {
        var p=pending(100,Long.MAX_VALUE);register(p);inflight().drain();assertNull(p.future.get());
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_ACCEPTED));
        assertEquals(1,reason("late_drained"));assertEquals(0,reason("continuation"));assertEquals(0,inflight().depth());
    }
    @Test void reconnectFencesOldSessionEvenWithReusedRequestId() throws Exception {
        var old=pending(100,Long.MAX_VALUE);register(old);
        Method retire=ClusterGatewayMain.class.getDeclaredMethod("retireAckSession");retire.setAccessible(true);retire.invoke(gateway);
        assertNull(old.future.get());
        when(((AeronCluster)field("client").get(gateway)).clusterSessionId()).thenReturn(8L);
        var fresh=pending(100,Long.MAX_VALUE);register(fresh);
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_ACCEPTED));
        assertFalse(fresh.future.isDone());assertEquals(1,reason("other_session"));assertEquals(1,inflight().depth());
        deliver(8,ack(100,11,21,OutputEvent.KIND_ORDER_ACCEPTED));
        assertEquals(21,fresh.future.get().orderRef());assertEquals(1,metric("pipelineAcksCompleted"));assertEquals(0,inflight().depth());
        assertEquals(1,exported("traderx_gateway_ack_history_resets_total"));
    }
    @Test void sessionResetCannotLaunderOldCompletionAsCurrentContinuation() throws Exception {
        submit(A,'B',10,PRICE);
        byte[] accepted=directRecord(OutputEvent.KIND_ORDER_ACCEPTED,1);
        Method retire=ClusterGatewayMain.class.getDeclaredMethod("retireAckSession");retire.setAccessible(true);retire.invoke(gateway);
        when(((AeronCluster)field("client").get(gateway)).clusterSessionId()).thenReturn(8L);
        accepted[12]=OutputEvent.KIND_ORDER_FILLED;
        deliver(8,accepted);
        assertEquals(1,reason("unknown"));assertEquals(0,reason("continuation"));
    }
    @Test void requestReuseInvalidatesPreviousCompletionEvidence() throws Exception {
        var old=pending(100,0);register(old);deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_ACCEPTED));
        assertEquals(0,inflight().sweepOverdue(1)); // remove the already-completed queue entry
        var fresh=pending(100,0);register(fresh);assertEquals(1,inflight().sweepOverdue(1));
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_FILLED));
        assertEquals(1,reason("late_reaped"));assertEquals(0,reason("continuation"));assertEquals(0,inflight().depth());
    }
    @Test void completedTupleMismatchDoesNotSuppressAnomaly() throws Exception {
        submit(A,'B',10,PRICE);
        byte[] original=directRecord(OutputEvent.KIND_ORDER_ACCEPTED,1);
        byte[] wrongSeq=original.clone();UnsafeBuffer b=new UnsafeBuffer(wrongSeq);b.putLong(0,b.getLong(0)+1);b.putByte(12,OutputEvent.KIND_ORDER_FILLED);deliver(SESSION,wrongSeq);
        byte[] wrongRef=original.clone();b=new UnsafeBuffer(wrongRef);b.putInt(8,999);b.putByte(12,OutputEvent.KIND_ORDER_FILLED);deliver(SESSION,wrongRef);
        byte[] wrongReason=original.clone();wrongReason[12]=OutputEvent.KIND_ORDER_FILLED;wrongReason[22]=99;deliver(SESSION,wrongReason);
        assertEquals(3,reason("unknown"));assertEquals(0,reason("continuation"));assertEquals(1,metric("pipelineAcksCompleted"));
    }
    @Test void uncorrelatedCanceledContinuationIsUnknownAndTerminalCannotFillAgain() throws Exception {
        var p=pending(100,Long.MAX_VALUE);register(p);deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_CANCELED));
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_FILLED));
        assertEquals(1,reason("unknown"));assertEquals(0,reason("continuation"));assertEquals(0,inflight().depth());
    }
    @Test void historyCollisionFallsBackToUnknownWithVisibleEviction() throws Exception {
        field("inflight").set(gateway,new ClusterGatewayMain.Inflight(2));
        var a=pending(100,Long.MAX_VALUE);register(a);deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_ACCEPTED));
        var b=pending(102,Long.MAX_VALUE);register(b);deliver(SESSION,ack(102,11,21,OutputEvent.KIND_ORDER_ACCEPTED));
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_FILLED));
        assertEquals(1,reason("unknown"));assertEquals(0,reason("continuation"));
        assertEquals(2,exported("traderx_gateway_ack_history_capacity"));assertEquals(1,exported("traderx_gateway_ack_history_evictions_total"));
        assertEquals(0,inflight().depth());
    }
    @Test void unknownActiveSessionCannotProveContinuation() throws Exception {
        submit(A,'B',10,PRICE);byte[] accepted=directRecord(OutputEvent.KIND_ORDER_ACCEPTED,1);
        field("client").set(gateway,null);accepted[12]=OutputEvent.KIND_ORDER_FILLED;deliver(SESSION,accepted);
        assertEquals(1,reason("unknown"));assertEquals(0,reason("continuation"));
    }
    @Test void wrongLengthIsRefusedBeforeReadingRequestOrCompleting() throws Exception {
        var p=pending(100,Long.MAX_VALUE);register(p);deliver(SESSION,new byte[24]);
        assertEquals(1,metric("egressLengthRefused"));assertEquals(0,metric("pipelineAcksUnmatched"));
        assertFalse(p.future.isDone());inflight().drain();assertEquals(0,inflight().depth());
    }
    @Test void classificationsPartitionTheLegacyAggregateAtRest() throws Exception {
        submit(A,'B',10,PRICE);submit(B,'S',10,PRICE);
        deliver(SESSION,directRecord(OutputEvent.KIND_ORDER_FILLED,2));
        deliver(SESSION,ack(999,100,99,OutputEvent.KIND_ORDER_FILLED));
        deliver(8,ack(999,100,99,OutputEvent.KIND_ORDER_FILLED));
        long sum=0;for(String reason:new String[]{"continuation","duplicate","late_reaped","late_drained","other_session","unknown"}) sum+=reason(reason);
        assertEquals(4,sum);assertEquals(metric("pipelineAcksUnmatched"),sum);assertEquals(0,inflight().depth());
    }
    @Test void unknownTerminalTransitionCannotLicenseALaterFill() throws Exception {
        var p=pending(100,Long.MAX_VALUE);register(p);deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_ACCEPTED));
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_CANCELED));
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_FILLED));
        assertEquals(2,reason("unknown"));assertEquals(0,reason("continuation"));
    }
    @Test void latencyAndReadinessSignalsOnlyObserveFirstCompletions() throws Exception {
        var latency=new GatewayLatencyDecomposition(0);field("latency").set(gateway,latency);
        submit(A,'B',10,PRICE);submit(B,'S',10,PRICE);
        deliver(SESSION,directRecord(OutputEvent.KIND_ORDER_FILLED,2));
        deliver(SESSION,ack(999,10,99,OutputEvent.KIND_ORDER_FILLED));
        assertTrue(latency.dump().contains("traderx_gateway_latency_count{segment=\"cluster\"} 2"),latency.dump());
        assertTrue(latency.dump().contains("traderx_gateway_latency_count{segment=\"queue\"} 2"),latency.dump());
        assertEquals(0,((java.util.concurrent.atomic.AtomicInteger)field("noAckStreak").get(gateway)).get());
        assertEquals(0,((java.util.concurrent.atomic.AtomicInteger)field("offeredUnackedStreak").get(gateway)).get());
        assertEquals(2,metric("pipelineAcksCompleted"));assertEquals(0,inflight().depth());
    }
    @Test void publicCancelAndNotFoundKeepTheirOwnReferencesAndPermits() throws Exception {
        var order=submit(A,'B',10,PRICE);
        var canceled=submit(()->gateway.submitCancel(order.orderRef()));
        assertTrue(canceled.accepted());assertEquals(order.orderRef(),canceled.orderRef());
        var missing=submit(()->gateway.submitCancel(9999));
        assertFalse(missing.accepted());assertEquals(9999,missing.orderRef());assertEquals(OutputEvent.KIND_ORDER_NOT_FOUND,missing.kind());
        assertEquals(3,metric("pipelineAcksCompleted"));assertEquals(1,metric("canceledOrders"));
        assertEquals(0,metric("pipelineAcksUnmatched"));assertEquals(0,inflight().depth());
    }
    @Test void batchAccountingRemainsSeparateFromPipelinedClassification() throws Exception {
        field("batchActive").setBoolean(gateway,true);field("batchOutstanding").setInt(gateway,1);
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_ACCEPTED));
        deliver(SESSION,ack(100,10,20,OutputEvent.KIND_ORDER_FILLED));
        assertEquals(0,field("batchOutstanding").getInt(gateway));assertEquals(1,field("batchAccepted").getInt(gateway));
        assertEquals(0,metric("pipelineAcksCompleted"));assertEquals(0,metric("pipelineAcksUnmatched"));
        assertEquals(0,reason("continuation"));assertEquals(0,inflight().depth());
    }
    byte[] directRecord(byte kind,long request) {
        return observed.stream().filter(r->{var b=new UnsafeBuffer(r);return b.getByte(12)==kind&&b.getLong(24)==request&&b.getByte(21)==0;})
            .findFirst().orElseThrow(()->new AssertionError("no real egress record kind="+kind+" request="+request)).clone();
    }
    static byte[] ack(long request,long seq,int ref,byte kind) {
        byte[] bytes=new byte[MatchingEngineClusteredService.EGRESS_ACK_LENGTH];var b=new UnsafeBuffer(bytes);
        b.putLong(0,seq);b.putInt(8,ref);b.putByte(12,kind);b.putLong(24,request);return bytes;
    }
    static ClusterGatewayMain.PendingOrder pending(long id,long deadline) {
        var p=new ClusterGatewayMain.PendingOrder(InputEvent.TYPE_ORDER_NEW,A,"IBM",'B',10,PRICE,0,0);
        p.requestId=id;p.reapAtMillis=deadline;return p;
    }
    void register(ClusterGatewayMain.PendingOrder p) throws Exception {assertTrue(inflight().acquire(100));inflight().register(p);}
    long reason(String reason) throws Exception {return exported("traderx_gateway_ack_unmatched_total{reason=\""+reason+"\"}");}
    long exported(String metric) throws Exception {
        var exchange=mock(com.sun.net.httpserver.HttpExchange.class);var output=new java.io.ByteArrayOutputStream();
        when(exchange.getResponseHeaders()).thenReturn(new com.sun.net.httpserver.Headers());when(exchange.getResponseBody()).thenReturn(output);
        Method method=ClusterGatewayMain.class.getDeclaredMethod("handleMetrics",com.sun.net.httpserver.HttpExchange.class);
        method.setAccessible(true);method.invoke(gateway,exchange);
        String prefix=metric+" ";return output.toString(java.nio.charset.StandardCharsets.UTF_8).lines()
            .filter(line->line.startsWith(prefix)).mapToLong(line->Long.parseLong(line.substring(prefix.length()))).findFirst()
            .orElseThrow(()->new AssertionError("endpoint did not export "+metric));
    }
    OrderSubmitter.ExecResult submit(int account,char side,int qty,long price) throws Exception {
        return submit(()->gateway.submitOrder("ack-test-"+(++keys),account,"IBM",side,qty,price));
    }
    OrderSubmitter.ExecResult submit(Callable<OrderSubmitter.ExecResult> action) throws Exception {
        var f=submitter.submit(action);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!f.isDone()) {
            FutureTask<?> task;while((task=tasks.poll())!=null) task.run();poll();
            if(System.nanoTime()>deadline) fail("real submit failed to complete");Thread.yield();
        }
        poll();var result=f.get();assertNotNull(result);return result;
    }
    void apply(InputEvent event) {
        codec.encodeInput(ingress,0,event,0,0,0);
        service.onSessionMessage(new Capture(),++timestamp,ingress,0,ingress.capacity(),null);
    }
    void poll() throws Exception {byte[] record;while((record=records.poll())!=null) deliver(SESSION,record);}
    void deliver(long session,byte[] record) throws Exception {
        observed.add(record);egress.invoke(gateway,session,++timestamp,new UnsafeBuffer(record),0,record.length,null);
    }
    long metric(String name) throws Exception {return field(name).getLong(gateway);}
    ClusterGatewayMain.Inflight inflight() throws Exception {return (ClusterGatewayMain.Inflight)field("inflight").get(gateway);}
    static Field field(String name) throws Exception {Field f=ClusterGatewayMain.class.getDeclaredField(name);f.setAccessible(true);return f;}
    final class Capture implements ClientSession {
        public long id(){return SESSION;}public int responseStreamId(){return 0;}public String responseChannel(){return "synthetic";}
        public byte[] encodedPrincipal(){return new byte[0];}public void close(){}public boolean isClosing(){return false;}
        public long offer(DirectBuffer b,int offset,int length){byte[] copy=new byte[length];b.getBytes(offset,copy);records.add(copy);return 1;}
        public long offer(DirectBufferVector[] vectors){throw new UnsupportedOperationException();}
        public long tryClaim(int length,BufferClaim claim){throw new UnsupportedOperationException();}
    }
}
