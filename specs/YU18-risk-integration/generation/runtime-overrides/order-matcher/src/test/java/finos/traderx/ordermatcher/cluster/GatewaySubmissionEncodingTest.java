package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.*;
import io.aeron.cluster.client.AeronCluster;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public submission -> owner queue -> actual encoder -> offer -> correlated egress.
 * The transport/ack are controlled here; the separate consensus proof establishes committed delivery. */
class GatewaySubmissionEncodingTest {
    @Test void typedNewReachesOfferAndCompletesCorrelatedAcknowledgment() throws Exception {exercise(true,false);}
    @Test void typedReplaceReachesOfferAndCompletesCorrelatedAcknowledgment() throws Exception {exercise(true,true);}
    @Test void legacyNewKeepsItsOriginalWireLengthAndTemplate() throws Exception {exercise(false,false);}

    @SuppressWarnings("unchecked")
    private void exercise(boolean typed,boolean replace) throws Exception {
        var gateway=new ClusterGatewayMain();var client=mock(AeronCluster.class);
        field("client").set(gateway,client);
        ((Map<String,Integer>)field("idByTicker").get(gateway)).put("IBM",1);
        Queue<FutureTask<?>> tasks=(Queue<FutureTask<?>>)field("tasks").get(gateway);
        var offered=new AtomicReference<UnsafeBuffer>();
        when(client.offer(any(DirectBuffer.class),anyInt(),anyInt())).thenAnswer(call->{
            DirectBuffer source=call.getArgument(0);int offset=call.getArgument(1),length=call.getArgument(2);
            byte[] copy=new byte[length];source.getBytes(offset,copy);offered.set(new UnsafeBuffer(copy));return 1L;
        });
        var instruction=new OrderSubmitter.TypedOrder(OrderTypes.STOP_LIMIT,OrderTypes.GTC,
            100_000_000L,110_000_000L,0,(byte)0,0,(byte)0,0);
        ExecutorService submitter=Executors.newSingleThreadExecutor();
        try {
            Future<OrderSubmitter.ExecResult> result=submitter.submit(()->typed
                ? replace ? gateway.submitTypedReplace(7,"replace-key",23,instruction)
                          : gateway.submitTypedOrder("new-key",11,"IBM",'B',23,instruction)
                : gateway.submitOrder("legacy-key",11,"IBM",'B',23,100_000_000L));
            FutureTask<?> task=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(task==null && System.nanoTime()<deadline) {task=tasks.poll();if(task==null) Thread.sleep(1);}
            assertNotNull(task,"public submission must enqueue the real owner task");task.run();
            UnsafeBuffer wire=offered.get();
            assertNotNull(wire,"encoding must reach client.offer; a too-small scratch buffer returns an ambiguous null result before any offer");
            assertEquals(typed?AeronReplicationCodec.ORDER_INSTRUCTION_BYTES:AeronReplicationCodec.INPUT_BYTES,wire.capacity());
            var codec=new AeronReplicationCodec();var decoded=new InputEvent();
            assertEquals(AeronReplicationCodec.OK,typed?codec.tryDecodeOrderInstruction(wire,0,wire.capacity(),decoded)
                :codec.tryDecodeInput(wire,0,wire.capacity(),decoded));
            assertEquals(replace?InputEvent.TYPE_ORDER_REPLACE:InputEvent.TYPE_ORDER_NEW,decoded.type);
            assertEquals(23,decoded.qty);assertEquals(100_000_000L,decoded.limitPx);
            assertTrue(decoded.seq>0,"offered message carries the gateway correlation id");
            if(typed) {assertEquals(OrderTypes.STOP_LIMIT,decoded.orderType);assertEquals(OrderTypes.GTC,decoded.tif);assertEquals(110_000_000L,decoded.stopPx);}
            var ack=new UnsafeBuffer(new byte[MatchingEngineClusteredService.EGRESS_ACK_LENGTH]);
            ack.putLong(0,42);ack.putInt(8,7);ack.putByte(12,OutputEvent.KIND_ORDER_ACCEPTED);ack.putLong(24,decoded.seq);
            Method egress=ClusterGatewayMain.class.getDeclaredMethod("onEgress",long.class,long.class,DirectBuffer.class,int.class,int.class,io.aeron.logbuffer.Header.class);
            egress.setAccessible(true);egress.invoke(gateway,1L,0L,ack,0,ack.capacity(),null);
            var completed=result.get(3,TimeUnit.SECONDS);assertNotNull(completed);assertTrue(completed.accepted());assertEquals(7,completed.orderRef());
            verify(client,times(1)).offer(any(DirectBuffer.class),eq(0),eq(wire.capacity()));
        } finally {submitter.shutdownNow();assertTrue(submitter.awaitTermination(3,TimeUnit.SECONDS));}
    }
    private static Field field(String name) throws Exception {
        Field f=ClusterGatewayMain.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
}
