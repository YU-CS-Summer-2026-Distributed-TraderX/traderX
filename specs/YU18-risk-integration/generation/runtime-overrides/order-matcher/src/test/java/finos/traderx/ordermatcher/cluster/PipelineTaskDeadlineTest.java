package finos.traderx.ordermatcher.cluster;
import finos.traderx.ordermatcher.lmax.*;
import io.aeron.cluster.client.AeronCluster;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.lang.reflect.*;
import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual submitPipelined0/acquire/add/future/offer/encode/egress/reap; mock transport, no cluster. */
@Timeout(25)
class PipelineTaskDeadlineTest {
    @Test void queuedTimeoutCannotOfferAndReturnsItsOwnedPermit() throws Exception { queued(false, false); }
    @Test void queuedInterruptedWaiterCannotOfferAndReturnsItsOwnedPermit() throws Exception { queued(true, false); }
    @Test void dequeuedUnclaimedInterruptedTaskStaysInert() throws Exception { queued(true, true); }
    private void queued(boolean interrupt, boolean dequeue) throws Exception {
        try (var f = new Fixture(true)) {
            f.call(interrupt ? 10_000 : 0, false); f.enqueued();
            assertEquals(1, f.inflight.depth()); assertEquals(1, f.available());
            FutureTask<?> held = dequeue ? f.queue.poll() : f.queue.captured.get(); assertNotNull(held);
            if (interrupt) f.caller.interrupt();
            f.queue.release.countDown(); f.returned();
            int atReturn = f.inflight.depth();
            assertEquals(0, f.inflight.sweepOverdue(Long.MAX_VALUE), "queued work never registered with reaper");
            f.drain(); held.run(); held.run();
            System.out.println("PIPELINE_QUEUED depthAtReturn="+atReturn+" lateOffers="+f.offers.get()+" requestId="+f.p.requestId);
            if (f.offers.get() > 0) f.wireWitness(f.p); // positive baseline witness before refusal assertion
            assertEquals(0, f.offers.get(), "terminated unstarted request must never reach actual client.offer");
            assertEquals(0, f.p.requestId, "no owner encoding/correlation assignment");
            byte[] actual = new byte[f.scratch.capacity()]; f.scratch.getBytes(0, actual); assertArrayEquals(f.sentinel, actual);
            assertEquals(0, atReturn); assertEquals(2, f.available()); assertEquals(0, f.queue.size());
            assertTrue(f.p.future.isDone()); assertNull(f.p.future.get());
            assertEquals(0, f.inflight.sweepOverdue(Long.MAX_VALUE)); assertEquals(2, f.available());
            f.assertHealthyAfterRetirement();
        }
    }
    @Test void startedTimeoutWithOfferedFalseKeepsPermitUntilRealAck() throws Exception { started(false, false); }
    @Test void startedInterruptedWaiterKeepsPermitUntilRealAck() throws Exception { started(true, false); }
    @Test void startedTimeoutReaperOwnsReleaseAndLateAckCannotReleaseTwice() throws Exception { started(false, true); }
    private void started(boolean interrupt, boolean reap) throws Exception {
        try (var f = new Fixture(true)) {
            f.holdOffer = true; f.call(interrupt ? 10_000 : 0, false); f.enqueued();
            var task = f.queue.poll(); assertNotNull(task); var owner = f.owner(task);
            assertTrue(f.enteredOffer.await(3, TimeUnit.SECONDS)); assertFalse(f.p.offered, "offer not cleared, but start already won");
            f.queue.release.countDown(); if (interrupt) f.caller.interrupt(); f.returned();
            assertNull(f.result.get()); assertFalse(task.isCancelled()); assertFalse(f.p.future.isDone());
            assertEquals(1, f.inflight.depth()); assertEquals(1, f.available());
            f.releaseOffer.countDown(); owner.join(3000); assertFalse(owner.isAlive());
            assertTrue(f.p.offered); assertEquals(1, f.offers.get()); assertEquals(1, f.inflight.depth());
            if (reap) { assertEquals(1, f.inflight.sweepOverdue(f.p.reapAtMillis)); assertNull(f.p.future.get()); }
            else { f.ack(f.p); assertTrue(f.p.future.get().accepted()); }
            f.ack(f.p); assertEquals(0, f.inflight.sweepOverdue(Long.MAX_VALUE)); task.run();
            assertEquals(2, f.available()); assertEquals(0, f.inflight.depth()); assertEquals(1, f.offers.get());
            assertFalse(owner.isInterrupted());
        }
    }
    @Test void ackBeforeWaiterResumesReturnsCommittedValueWithoutExtraRelease() throws Exception {
        try (var f = new Fixture(true)) {
            f.call(0, false); f.enqueued(); f.drain(); f.ack(f.p); f.queue.release.countDown(); f.returned();
            assertNotNull(f.result.get()); assertTrue(f.result.get().accepted()); assertEquals(2, f.available());
            f.ack(f.p); assertEquals(0, f.inflight.sweepOverdue(Long.MAX_VALUE)); assertEquals(2, f.available());
        }
    }
    @Test void enqueueExceptionRetiresOnlyTheAcquiredPermit() throws Exception {
        try (var f = new Fixture(false)) {
            f.queue.fail = true; f.call(0, false); f.returned(); assertNotNull(f.queue.captured.get());
            f.queue.captured.get().run(); assertEquals(0, f.offers.get()); assertEquals(2, f.available());
            assertTrue(f.p.future.isDone()); assertNull(f.p.future.get());
        }
    }
    @Test void failureAfterAcquireBeforeTaskCreationReleasesItsKnownPermit() throws Exception {
        try (var f = new Fixture(false)) {
            Field latency = field("latency");
            Object faulty = mock(latency.getType(), invocation -> { throw new IllegalStateException("fixture sample failure"); });
            latency.set(f.gateway, faulty); f.call(0, false); f.returned();
            assertNull(f.queue.captured.get()); assertEquals(0, f.offers.get()); assertEquals(2, f.available());
            assertTrue(f.p.future.isDone()); assertNull(f.p.future.get());
        }
    }
    @Test void interruptedAdmissionDoesNotReleaseAnotherRequestsPermit() throws Exception {
        try (var f = new Fixture(false)) {
            assertTrue(f.inflight.acquire(0)); assertTrue(f.inflight.acquire(0));
            try {
                f.call(0, true); f.returned(); assertNull(f.queue.captured.get());
                assertEquals(0, f.offers.get()); assertEquals(0, f.available()); assertEquals(2, f.inflight.depth());
            } finally { f.inflight.release(); f.inflight.release(); }
        }
    }
    private static final class Capture extends LinkedBlockingQueue<FutureTask<?>> {
        final CountDownLatch added=new CountDownLatch(1), release=new CountDownLatch(1);
        final AtomicReference<FutureTask<?>> captured=new AtomicReference<>(); final boolean pause; boolean fail;
        Capture(boolean pause){this.pause=pause;}
        @Override public boolean add(FutureTask<?> task) {
            captured.set(task); if(fail) throw new RejectedExecutionException("fixture enqueue failure");
            boolean result=super.add(task);added.countDown();
            if(pause) {boolean interrupted=false;for(;;)try{assertTrue(release.await(20,TimeUnit.SECONDS));break;}catch(InterruptedException ex){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
            return result;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final ClusterGatewayMain gateway=new ClusterGatewayMain();
        final ClusterGatewayMain.Inflight inflight=new ClusterGatewayMain.Inflight(2);
        final ClusterGatewayMain.PendingOrder p=order(10);
        final AeronCluster client=mock(AeronCluster.class); final Capture queue;
        final List<byte[]> wires=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger offers=new AtomicInteger(); final AtomicReference<OrderSubmitter.ExecResult> result=new AtomicReference<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>(); final CountDownLatch done=new CountDownLatch(1);
        final CountDownLatch enteredOffer=new CountDownLatch(1),releaseOffer=new CountDownLatch(1);
        final UnsafeBuffer scratch; final byte[] sentinel; final List<Thread> owners=new ArrayList<>();
        boolean holdOffer; Thread caller;
        Fixture(boolean pause)throws Exception {
            queue=new Capture(pause);field("tasks").set(gateway,queue);field("inflight").set(gateway,inflight);field("client").set(gateway,client);
            when(client.clusterSessionId()).thenReturn(7L);
            scratch=(UnsafeBuffer)field("orderBuffer").get(gateway);sentinel=new byte[scratch.capacity()];Arrays.fill(sentinel,(byte)90);scratch.putBytes(0,sentinel);
            when(client.offer(any(DirectBuffer.class),anyInt(),anyInt())).thenAnswer(call->{
                DirectBuffer buffer=call.getArgument(0); int offset=call.getArgument(1),length=call.getArgument(2);
                byte[] wire=new byte[length];buffer.getBytes(offset,wire);wires.add(wire);
                offers.incrementAndGet(); enteredOffer.countDown();
                if(holdOffer) assertTrue(releaseOffer.await(20,TimeUnit.SECONDS));
                return 1L;
            });
        }
        void call(long budget,boolean preInterrupted)throws Exception {
            Method submit;boolean parameter;
            try {submit=ClusterGatewayMain.class.getDeclaredMethod("submitPipelined0",ClusterGatewayMain.PendingOrder.class,long.class);parameter=true;}
            catch(NoSuchMethodException old){submit=ClusterGatewayMain.class.getDeclaredMethod("submitPipelined0",ClusterGatewayMain.PendingOrder.class);parameter=false;}
            submit.setAccessible(true);final Method method=submit;final boolean explicit=parameter;
            caller=new Thread(()->{if(preInterrupted)Thread.currentThread().interrupt();try{result.set((OrderSubmitter.ExecResult)(explicit?method.invoke(gateway,p,budget):method.invoke(gateway,p)));}catch(InvocationTargetException ex){failure.set(ex.getCause());}catch(Throwable ex){failure.set(ex);}finally{done.countDown();}},"pipeline-waiter-fixture");caller.start();
        }
        void enqueued()throws Exception{assertTrue(queue.added.await(3,TimeUnit.SECONDS));assertNotNull(queue.captured.get());}
        void returned()throws Exception{assertTrue(done.await(15,TimeUnit.SECONDS),"real original budget or explicit test budget expires");caller.join(3000);assertFalse(caller.isAlive());assertNull(failure.get());}
        void drain(){FutureTask<?> task;while((task=queue.poll())!=null)task.run();}
        Thread owner(FutureTask<?> task){var t=new Thread(task,"pipeline-owner-fixture");owners.add(t);t.start();return t;}
        int available()throws Exception{var permits=ClusterGatewayMain.Inflight.class.getDeclaredField("permits");permits.setAccessible(true);return((Semaphore)permits.get(inflight)).availablePermits();}
        void ack(ClusterGatewayMain.PendingOrder pending)throws Exception{
            var buffer=new UnsafeBuffer(new byte[MatchingEngineClusteredService.EGRESS_ACK_LENGTH]);buffer.putLong(0,99);buffer.putInt(8,17);buffer.putByte(12,OutputEvent.KIND_ORDER_ACCEPTED);buffer.putLong(24,pending.requestId);
            Method egress=ClusterGatewayMain.class.getDeclaredMethod("onEgress",long.class,long.class,DirectBuffer.class,int.class,int.class,io.aeron.logbuffer.Header.class);egress.setAccessible(true);egress.invoke(gateway,7L,0L,buffer,0,buffer.capacity(),null);
        }
        void wireWitness(ClusterGatewayMain.PendingOrder pending) {
            byte[] bytes=wires.get(wires.size()-1);var decoded=new InputEvent();
            assertEquals(AeronReplicationCodec.INPUT_BYTES,bytes.length);
            assertEquals(AeronReplicationCodec.OK,new AeronReplicationCodec().tryDecodeInput(new UnsafeBuffer(bytes),0,bytes.length,decoded));
            assertEquals(InputEvent.TYPE_ORDER_NEW,decoded.type);assertEquals(3,decoded.qty);assertEquals(1,decoded.securityId);
            assertEquals(pending.requestId,decoded.seq);assertTrue(decoded.seq>0);
            System.out.println("ENCODED_OFFER bytes="+bytes.length+" qty="+decoded.qty+" security="+decoded.securityId+" correlation="+decoded.seq);
        }
        void assertHealthyAfterRetirement()throws Exception {
            assertTrue(inflight.acquire(0));var next=order(11);
            Method offer=ClusterGatewayMain.class.getDeclaredMethod("offerPipelined",ClusterGatewayMain.PendingOrder.class);offer.setAccessible(true);offer.invoke(gateway,next);
            assertEquals(1,offers.get());assertTrue(next.requestId>0);wireWitness(next);ack(next);assertTrue(next.future.get().accepted());
            assertEquals(2,available());assertEquals(0,inflight.depth());
        }
        @Override public void close()throws Exception{
            queue.release.countDown();releaseOffer.countDown();if(caller!=null){caller.interrupt();caller.join(3000);assertFalse(caller.isAlive());}
            for(Thread owner:owners){owner.join(3000);assertFalse(owner.isAlive());}inflight.drain();
            ((HttpClient)field("readModelClient").get(gateway)).close();
        }
    }
    private static ClusterGatewayMain.PendingOrder order(long key){return new ClusterGatewayMain.PendingOrder(InputEvent.TYPE_ORDER_NEW,11,null,'B',3,100_000_000L,key,0,1);}
    private static Field field(String name)throws Exception{Field f=ClusterGatewayMain.class.getDeclaredField(name);f.setAccessible(true);return f;}
}
