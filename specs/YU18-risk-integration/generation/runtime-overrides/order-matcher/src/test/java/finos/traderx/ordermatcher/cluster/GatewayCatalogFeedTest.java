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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FR-ERH-F14, on three real members: the catalog feed (reference-data's outbox) registers a symbol and enables it only
 * when that registration created it. Every gateway replays the whole feed at start and the engine applies controls
 * last-write-wins, so before this a gateway restart re-enabled what an operator had disabled (production epoch e1101,
 * 2026-10-31: the matured UST-BILL-20260910 read enabled). Fails against the previous gateway.
 */
@Timeout(120)
class GatewayCatalogFeedTest {
    @TempDir Path root;
    private final List<ClusteredMediaDriver> drivers=new ArrayList<>();
    private final List<ClusteredServiceContainer> containers=new ArrayList<>();
    private final List<MatchingEngineClusteredService> services=new ArrayList<>();
    private final ExecutorService caller=Executors.newSingleThreadExecutor();
    private final AtomicReference<ClusterGatewayMain> current=new AtomicReference<>();
    private MediaDriver clientDriver;
    private AeronCluster client;

    @AfterEach void close() {
        caller.shutdownNow();CloseHelper.quietCloseAll(client,clientDriver);
        for(var container:containers) CloseHelper.quietClose(container);
        for(var driver:drivers) CloseHelper.quietClose(driver);
    }

    @Test void aReplayedFeedNeverReEnablesWhatAnOperatorDisabled() throws Exception {
        var hosts=List.of("localhost","localhost","localhost");int port=TestPorts.freeBase(3);
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
                try {onEgress.invoke(current.get(),session,time,data,offset,length,header);}
                catch(Exception ex) {throw new IllegalStateException(ex);}
            }));
        String bill="UST-BILL-20260910";
        var first=gateway();
        feed(first,bill,1);                                         // first registration: new, so enabled
        int id=registeredEverywhere(bill);
        await(()->enabledEverywhere(id,true));
        operatorControl(id,false);                                  // the initializer disables the matured bill
        await(()->enabledEverywhere(id,false));
        feed(first,bill,1);                                         // the same gateway replays the feed
        var restarted=gateway();                                    // a restarted gateway: no cache, replays it all
        feed(restarted,bill,1);
        var marker=gateway();feed(marker,"MSFT",2);                 // a later control, so the replays have applied
        await(()->enabledEverywhere(symbolId("MSFT"),true));
        assertTrue(services.stream().noneMatch(s->s.engine().riskState().securityEnabled(id)),
            "a replayed catalog delta re-enabled the operator-disabled "+bill);
    }

    private ClusterGatewayMain gateway() throws Exception {
        var gateway=new ClusterGatewayMain();field("client").set(gateway,client);current.set(gateway);return gateway;
    }
    @SuppressWarnings("unchecked")
    private void feed(ClusterGatewayMain gateway,String ticker,long version) throws Exception {
        current.set(gateway);
        Method apply=ClusterGatewayMain.class.getDeclaredMethod("applyControlDelta",String.class);apply.setAccessible(true);
        var tasks=(Queue<FutureTask<?>>)field("tasks").get(gateway);
        var future=caller.submit(()->{apply.invoke(gateway,"{\"ticker\":\""+ticker+"\",\"version\":"+version+"}");return null;});
        await(()->{FutureTask<?> task;while((task=tasks.poll())!=null) task.run();client.pollEgress();client.sendKeepAlive();return future.isDone();});
        future.get(1,TimeUnit.SECONDS);
    }
    private void operatorControl(int securityId,boolean enabled) {
        var event=new InputEvent();event.type=InputEvent.TYPE_SECURITY_CONTROL;event.securityId=securityId;
        event.setControlEnabled(enabled);event.setControlVersion(System.currentTimeMillis());
        var buffer=new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
        new AeronReplicationCodec().encodeInput(buffer,0,event,0,0,0);
        await(()->{client.pollEgress();return client.offer(buffer,0,buffer.capacity())>0;});
    }
    /** The registration's id once every member has applied it. The gateway's ack comes from the leader; a follower
     *  applies the same entry a moment later, so reading member 0 at once was -1 whenever it was a follower that had
     *  not yet, and nothing then waited for that id to become enabled (1 run in 10 locally, 2 in 3 on a loaded box). */
    private int registeredEverywhere(String ticker) {
        await(()->{client.pollEgress();return services.stream().allMatch(s->s.symbolIdFor(ticker)>=0);});
        return symbolId(ticker);
    }
    private int symbolId(String ticker) {
        for(int id=0;;id++) {String t=services.get(0).tickerFor(id);if(t==null) return -1;if(t.equals(ticker)) return id;}
    }
    private boolean enabledEverywhere(int id,boolean enabled) {
        client.pollEgress();return id>=0 && services.stream().allMatch(s->s.engine().riskState().securityEnabled(id)==enabled);
    }
    private void await(BooleanSupplier condition) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>deadline) fail("condition did not complete within 60 seconds");
            Thread.yield();
        }
    }
    private static Field field(String name) throws Exception {
        Field f=ClusterGatewayMain.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
}
