package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.*;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Gateway public typed submissions, real offer and committed egress from three disposable members. */
@Timeout(90)
class GatewayConsensusSubmissionTest {
    @TempDir Path root;
    private final List<ClusteredMediaDriver> drivers=new ArrayList<>();
    private final List<ClusteredServiceContainer> containers=new ArrayList<>();
    private final List<MatchingEngineClusteredService> services=new ArrayList<>();
    private MediaDriver clientDriver;
    private AeronCluster client;
    private final ClusterGatewayMain gateway=new ClusterGatewayMain();
    private final ExecutorService submitter=Executors.newSingleThreadExecutor();
    private final AeronReplicationCodec codec=new AeronReplicationCodec();
    private final UnsafeBuffer buffer=new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final List<Long> committedSequences=new ArrayList<>();
    private Queue<FutureTask<?>> ownerTasks;

    @AfterEach void close() {
        submitter.shutdownNow();CloseHelper.quietCloseAll(client,clientDriver);
        for(var container:containers) CloseHelper.quietClose(container);
        for(var driver:drivers) CloseHelper.quietClose(driver);
    }

    @SuppressWarnings("unchecked")
    @Test void typedNewAndReplaceReachConsensusAndChangeTheRestingOrder() throws Exception {
        var hosts=List.of("localhost","localhost","localhost");int port=23800;
        for(int id=0;id<3;id++) {
            var service=new MatchingEngineClusteredService();services.add(service);
            var contexts=ClusterNodeConfig.contexts(id,hosts,port,root.resolve("aeron-"+id).toString(),
                root.resolve("node-"+id).toFile(),service,true);
            drivers.add(ClusteredMediaDriver.launch(contexts.mediaDriver(),contexts.archive(),contexts.consensusModule()));
            containers.add(ClusteredServiceContainer.launch(contexts.container()));
        }
        await(()->services.stream().anyMatch(s->s.role()==Cluster.Role.LEADER));
        clientDriver=MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(root.resolve("client").toString())
            .threadingMode(ThreadingMode.SHARED).termBufferSparseFile(true).dirDeleteOnStart(true));
        Method onEgress=ClusterGatewayMain.class.getDeclaredMethod("onEgress",long.class,long.class,DirectBuffer.class,int.class,int.class,io.aeron.logbuffer.Header.class);
        onEgress.setAccessible(true);
        client=AeronCluster.connect(new AeronCluster.Context().aeronDirectoryName(clientDriver.aeronDirectoryName())
            .ingressChannel("aeron:udp?term-length=64k").ingressEndpoints(ClusterNodeConfig.ingressEndpoints(hosts,port))
            .egressChannel("aeron:udp?endpoint=localhost:0")
            .egressListener((session,time,data,offset,length,header)->{
                if(data.getByte(offset+12)==OutputEvent.KIND_ORDER_ACCEPTED) committedSequences.add(data.getLong(offset));
                try {onEgress.invoke(gateway,session,time,data,offset,length,header);}
                catch(Exception ex) {throw new IllegalStateException(ex);}
            }));
        field("client").set(gateway,client);
        ((Map<String,Integer>)field("idByTicker").get(gateway)).put("IBM",1);
        ownerTasks=(Queue<FutureTask<?>>)field("tasks").get(gateway);
        InputEvent seed=new InputEvent();seed.type=InputEvent.TYPE_ACCOUNT_CONTROL;seed.accountId=11;
        seed.setControlEnabled(true);seed.setControlVersion(1);offer(seed);
        seed=new InputEvent();seed.type=InputEvent.TYPE_SECURITY_CONTROL;seed.securityId=1;
        seed.setControlEnabled(true);seed.setControlVersion(1);offer(seed);
        seed=new InputEvent();seed.type=InputEvent.TYPE_PRICE_TICK;seed.securityId=1;seed.priceTicks=150_000_000L;offer(seed);
        var original=new OrderSubmitter.TypedOrder(OrderTypes.LIMIT,OrderTypes.GTC,100_000_000L,0,0,(byte)0,0,(byte)0,0);
        var accepted=submit(()->gateway.submitTypedOrder("typed-live-new",11,"IBM",'B',10,original));
        assertTrue(accepted.accepted());assertEquals(1,accepted.orderRef());
        var replacement=new OrderSubmitter.TypedOrder(OrderTypes.LIMIT,OrderTypes.GTC,101_000_000L,0,0,(byte)0,0,(byte)0,0);
        var replaced=submit(()->gateway.submitTypedReplace(accepted.orderRef(),"typed-live-replace",25,replacement));
        assertTrue(replaced.accepted());assertEquals(accepted.orderRef(),replaced.orderRef());
        await(()->services.stream().allMatch(s->{
            var orders=s.engine().openOrderTuples();return orders.size()==1 && orders.get(0)[2]==25 && orders.get(0)[3]==101_000_000L;
        }));
        assertEquals(2,committedSequences.size());assertTrue(committedSequences.get(1)>committedSequences.get(0));
        System.out.println("GATEWAY_TYPED_COMMITTED newSeq="+committedSequences.get(0)+" replaceSeq="+committedSequences.get(1)
            +" orderRef=1 remaining=25 limitPx=101000000 members=3");
    }
    private OrderSubmitter.ExecResult submit(Callable<OrderSubmitter.ExecResult> action) throws Exception {
        var future=submitter.submit(action);
        await(()->{
            FutureTask<?> task;while((task=ownerTasks.poll())!=null) task.run();
            client.pollEgress();client.sendKeepAlive();return future.isDone();
        });
        var result=future.get(1,TimeUnit.SECONDS);
        assertNotNull(result,"public gateway submission must receive a committed result");return result;
    }
    private void offer(InputEvent event) {
        codec.encodeInput(buffer,0,event,0,0,0);
        await(()->{client.pollEgress();return client.offer(buffer,0,buffer.capacity())>0;});
    }
    private void await(BooleanSupplier condition) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>deadline) fail("condition did not complete within 30 seconds");
            Thread.yield();
        }
    }
    private static Field field(String name) throws Exception {
        Field f=ClusterGatewayMain.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
}
