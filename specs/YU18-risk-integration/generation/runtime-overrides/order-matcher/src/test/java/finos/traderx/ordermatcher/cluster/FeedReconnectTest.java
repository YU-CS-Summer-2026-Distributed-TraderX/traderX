package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.ordermatcher.lmax.*;
import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.codecs.EventCode;
import io.aeron.driver.MediaDriver;
import io.nats.client.*;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual run/NATS handler/loop/recovery/codec/egress; deterministic IO seams, not a replacement lifecycle. */
@Timeout(20)
class FeedReconnectTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final FeedAdapterMain.Retry LOCAL=new FeedAdapterMain.Retry(10000,100,1000,1000);
    @Test void initialDnsFailureIsOneVisibleColdFailureWithCleanup()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.action=(ctx,call)->{throw new java.net.UnknownHostException("synthetic member DNS");};f.run();
            assertEquals(1,f.calls);assertEquals("FAILED_START",f.status().get("state").asText());assertEquals(1,f.status().get("failures").asLong());
            assertInstanceOf(java.net.UnknownHostException.class,f.failure.get());assertEquals(0,f.natsOpened);f.clean(0);
        }
    }
    @Test void initiallyClosedClientIsRefusedAndClosedOnce()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.action=(ctx,call)->{var peer=f.peer(ctx,7);peer.closed.set(true);return peer.client;};f.run();
            assertEquals("FAILED_START",f.status().get("state").asText());assertEquals(1,f.calls);f.clean(1);
        }
    }
    @Test void idleConnectedFeedStaysHealthyWithoutInventingPriceActivity()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.quote=false;f.clock.limitMs=2600;f.action=(ctx,call)->f.peer(ctx,7).client;f.run();
            assertInstanceOf(InterruptedException.class,f.failure.get());assertEquals(1,f.calls);
            assertEquals(0,f.status().get("received").asLong());assertEquals(0,f.status().get("publicationOffers").asLong());
            assertFalse(f.logs.stream().anyMatch(s->s.contains("RECONNECTING")));verify(f.peers.get(0).client,atLeast(3)).sendKeepAlive();f.clean(1);
        }
    }
    @Test void dnsAndTimeoutOutageRecoversLatestQuoteWithFreshSymbolIds()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.action=(ctx,call)->{
                if(call==1){var p=f.peer(ctx,7);p.onPoll=()->{if(p.polls==3)p.connected.set(false);};return p.client;}
                if(call==2){f.deliver("ABC",2.5);f.clock.advance(5);throw new java.net.UnknownHostException("synthetic DNS");}
                if(call==3){f.clock.advance(TimeUnit.NANOSECONDS.toMillis(ctx.messageTimeoutNs()));throw new IllegalStateException("synthetic handshake timeout");}
                var p=f.peer(ctx,42);var old=f.peers.get(0);p.onSymbol=(request)->{old.ack(old.requests.get(0),999,7);old.listener.onSessionEvent(0,7,0,0,EventCode.CLOSED,"old generation");};return p.client;
            };
            f.clock.stop=()->f.peers.size()>1&&!f.peers.get(1).ticks.isEmpty();f.run();
            assertEquals(4,f.calls);assertEquals(1,f.status().get("recoveries").asLong());assertEquals(2,f.status().get("failures").asLong());
            var tick=f.peers.get(1).ticks.get(0);assertEquals(42,tick.securityId);assertEquals(2_500_000L,tick.priceTicks);assertEquals(0,tick.eventTimeMillis);
            assertTrue(f.peers.get(1).requests.get(0)>f.peers.get(0).requests.get(0));f.clean(2);
        }
    }
    @Test void foreignSessionAckCannotCertifyOwnSymbolRegistration()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.action=(ctx,call)->{var p=f.peer(ctx,42);p.onSymbol=request->p.ack(request,999,99);return p.client;};
            f.clock.stop=()->!f.peers.isEmpty()&&!f.peers.get(0).ticks.isEmpty();f.run();
            assertEquals(42,f.peers.get(0).ticks.get(0).securityId);f.clean(1);
        }
    }
    @Test void repeatedReconnectReRegistersAndClosesEveryGeneration()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.action=(ctx,call)->{var p=f.peer(ctx,call*10);if(call<3)p.onPoll=()->{if(p.polls==3){f.deliver("ABC",call*1.25);p.connected.set(false);}};return p.client;};
            f.clock.stop=()->f.peers.size()==3&&!f.peers.get(2).ticks.isEmpty();f.run();
            assertEquals(3,f.calls);assertEquals(2,f.status().get("recoveries").asLong());assertEquals(30,f.peers.get(2).ticks.get(0).securityId);
            assertTrue(f.peers.get(2).requests.get(0)>f.peers.get(1).requests.get(0));f.clean(3);
        }
    }
    @Test void closedOfferIsRetriedOnFreshConnectionWithoutLosingUnacceptedTick()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.action=(ctx,call)->{var p=f.peer(ctx,call*10);if(call==1)p.symbolResult=Publication.CLOSED;return p.client;};
            f.clock.stop=()->f.peers.size()==2&&!f.peers.get(1).ticks.isEmpty();f.run();
            assertEquals(2,f.calls);assertEquals(20,f.peers.get(1).ticks.get(0).securityId);assertEquals(1_250_000L,f.peers.get(1).ticks.get(0).priceTicks);f.clean(2);
        }
    }
    @Test void latestArrivalDuringBackpressureIsNotRemovedWithOlderOffer()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.action=(ctx,call)->{var p=f.peer(ctx,7);p.onTick=()->{if(p.tickAttempts==1){f.deliver("ABC",2.5);return Publication.BACK_PRESSURED;}return 1L;};return p.client;};
            f.clock.stop=()->!f.peers.isEmpty()&&f.peers.get(0).ticks.size()==2;f.run();
            assertEquals(List.of(1_250_000L,2_500_000L),f.peers.get(0).ticks.stream().map(x->x.priceTicks).toList());f.clean(1);
        }
    }
    @Test void permanentFailureExhaustsBudgetAndClosesResources()throws Exception{
        try(var f=new Fixture(new FeedAdapterMain.Retry(1000,100,400,300))){
            f.action=(ctx,call)->{if(call==1){var p=f.peer(ctx,7);p.onPoll=()->p.connected.set(false);return p.client;}f.clock.advance(50);throw new java.net.UnknownHostException("persistent local fixture");};f.run();
            assertEquals("EXHAUSTED",f.status().get("state").asText());assertFalse(f.failure.get() instanceof InterruptedException);
            assertTrue(f.calls>2);assertEquals(1000,TimeUnit.NANOSECONDS.toMillis(f.clock.now));assertTrue(f.caps.subList(1,f.caps.size()).stream().allMatch(x->x<=TimeUnit.MILLISECONDS.toNanos(300)));f.clean(1);
        }
    }
    @Test void lateSuccessfulConnectIsClosedInsteadOfAcceptedOutsideBudget()throws Exception{
        try(var f=new Fixture(new FeedAdapterMain.Retry(100,10,20,80))){
            f.action=(ctx,call)->{var p=f.peer(ctx,7);if(call==1)p.onPoll=()->p.connected.set(false);else f.clock.advance(101);return p.client;};f.run();
            assertEquals("EXHAUSTED",f.status().get("state").asText());assertEquals(1,f.status().get("successfulConnections").asLong());f.clean(2);
        }
    }
    @Test void interruptionDuringBackoffStopsAndReapsResources()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.action=(ctx,call)->{if(call==1){var p=f.peer(ctx,7);p.onPoll=()->p.connected.set(false);return p.client;}throw new java.net.UnknownHostException("transient");};
            f.clock.stop=()->f.calls>1;f.run();assertInstanceOf(InterruptedException.class,f.failure.get());assertEquals("STOPPED",f.status().get("state").asText());f.clean(1);
        }
    }
    @Test void malformedPriceIsDroppedAndDoesNotCauseReconnect()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.quote=false;f.afterSubscribe=()->f.deliverRaw("ABC","{\"payload\":{\"price\":\"bad\"}}");f.clock.limitMs=200;f.action=(ctx,call)->f.peer(ctx,7).client;f.run();
            assertEquals(1,f.status().get("dropped").asLong());assertEquals(0,f.status().get("received").asLong());assertEquals(1,f.calls);f.clean(1);
        }
    }
    @Test void unsupportedTickerEncodingFailureIsTerminalNotConnectionRetry()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.quote=false;f.afterSubscribe=()->f.deliver("X".repeat(33),1.25);f.action=(ctx,call)->f.peer(ctx,7).client;f.run();
            assertInstanceOf(IllegalArgumentException.class,f.failure.get());assertEquals("FAILED",f.status().get("state").asText());assertEquals(1,f.calls);f.clean(1);
        }
    }
    @Test void failedOldClientCleanupIsTerminalNotAnotherConcurrentSession()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.action=(ctx,call)->{var p=f.peer(ctx,7);p.onPoll=()->p.connected.set(false);p.closeError=true;return p.client;};f.run();
            assertEquals("FAILED",f.status().get("state").asText());assertEquals(1,f.calls);f.clean(1);
        }
    }
    @Test void outageConflationRetainsLatestPerTickerRatherThanEveryQuote()throws Exception{
        try(var f=new Fixture(new FeedAdapterMain.Retry(1000,100,400,300))){
            f.quote=false;f.afterSubscribe=()->{for(int version=0;version<100;version++)for(int ticker=0;ticker<128;ticker++)f.deliver("SYN-"+ticker,1.0+version/100.0);};
            f.action=(ctx,call)->{if(call==1){var p=f.peer(ctx,7);p.onPoll=()->p.connected.set(false);return p.client;}f.clock.advance(50);throw new java.net.UnknownHostException("outage cardinality fixture");};f.run();
            assertEquals("EXHAUSTED",f.status().get("state").asText());assertEquals(12800,f.status().get("received").asLong());
            assertEquals(128,f.status().get("conflatedTickers").asInt());assertEquals(0,f.status().get("publicationOffers").asLong());f.clean(1);
        }
    }
    @Test void stopAfterConnectionButBeforeSubscribeClosesClientWithoutStartingSubscriber()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.action=(ctx,call)->{var p=f.peer(ctx,7);Thread.currentThread().interrupt();return p.client;};f.run();
            assertInstanceOf(InterruptedException.class,f.failure.get());assertEquals("STOPPED",f.status().get("state").asText());
            assertEquals(0,f.natsOpened);f.clean(1);
        }
    }
    @Test void ownSessionClosedEventRecoversEvenWithNoQuotes()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.quote=false;f.action=(ctx,call)->{var p=f.peer(ctx,7);if(call==1)p.onPoll=()->p.listener.onSessionEvent(0,7,0,0,EventCode.CLOSED,"fixture closed");return p.client;};
            f.clock.stop=()->f.peers.size()==2;f.run();assertEquals(1,f.status().get("recoveries").asLong());
            assertEquals(0,f.status().get("publicationOffers").asLong());f.clean(2);
        }
    }
    @Test void unknownBegunOfferCanBeRepeatedWithoutClaimingExactlyOnce()throws Exception{
        try(var f=new Fixture(LOCAL)){
            f.action=(ctx,call)->{var p=f.peer(ctx,call*10);if(call==1)p.throwAfterTick=true;return p.client;};
            f.clock.stop=()->f.peers.size()==2&&!f.peers.get(1).ticks.isEmpty();f.run();
            assertEquals(1,f.peers.get(0).ticks.size(),"transport fixture records possibly accepted frame before exception");
            assertEquals(1,f.peers.get(1).ticks.size(),"pending latest price may be offered again after unknown transport outcome");
            assertEquals(1,f.status().get("publicationOffers").asLong(),"only observed positive return counted, not exactly-once committed ticks");f.clean(2);
        }
    }
    @Test void partialNativeConnectCleanupFailureCannotBeRetriedSilently()throws Exception{
        var context=mock(AeronCluster.Context.class);var pending=mock(AeronCluster.AsyncConnect.class);
        when(pending.poll()).thenThrow(new io.aeron.exceptions.AeronException("synthetic native poll failure"));
        doThrow(new io.aeron.exceptions.AeronException("synthetic partial cleanup failure")).when(pending).close();
        try(var nativeCalls=mockStatic(AeronCluster.class)){
            nativeCalls.when(()->AeronCluster.asyncConnect(context)).thenReturn(pending);
            var method=FeedAdapterMain.class.getDeclaredMethod("nativeConnect",AeronCluster.Context.class);method.setAccessible(true);
            var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,context));
            assertEquals("CleanupFailure",failure.getCause().getClass().getSimpleName());verify(context,times(1)).close();verify(pending,times(1)).close();
        }
    }
    @Test void invalidBudgetsBackoffAndOverflowAreRefusedBeforeIo(){
        assertThrows(IllegalArgumentException.class,()->new FeedAdapterMain.Retry(0,1,2,1));
        assertThrows(IllegalArgumentException.class,()->new FeedAdapterMain.Retry(1,2,1,1));
        assertThrows(IllegalArgumentException.class,()->new FeedAdapterMain.Retry(1,-1,1,1));
        assertThrows(IllegalArgumentException.class,()->new FeedAdapterMain.Retry(Long.MAX_VALUE,1,2,1));
        assertThrows(IllegalArgumentException.class,()->new FeedAdapterMain.Settings("","","","",0,LOCAL));
    }
    @FunctionalInterface interface Action{AeronCluster connect(AeronCluster.Context context,int call)throws Exception;}
    static final class Clock implements FeedAdapterMain.Timing{
        long now,limitMs=30000;BooleanSupplier stop=()->false;List<Long>sleeps=new ArrayList<>();
        public long nanoTime(){return now;}public long currentTimeMillis(){return 1000+TimeUnit.NANOSECONDS.toMillis(now);}
        void advance(long ms){now+=TimeUnit.MILLISECONDS.toNanos(ms);}
        public void sleepNanos(long ns)throws InterruptedException{sleeps.add(ns);now+=ns;if(stop.getAsBoolean()||now>=TimeUnit.MILLISECONDS.toNanos(limitMs))throw new InterruptedException("owned deterministic fixture stop");}
    }
    static final class Peer{
        final AeronCluster client=mock(AeronCluster.class);final Publication publication=mock(Publication.class);
        final EgressListener listener;final AtomicBoolean connected=new AtomicBoolean(true),closed=new AtomicBoolean();
        final List<Long>requests=new ArrayList<>();final List<InputEvent>ticks=new ArrayList<>();int polls,tickAttempts;long symbolResult=1;boolean closeError,throwAfterTick;
        Runnable onPoll=()->{};LongConsumer onSymbol=x->{};LongSupplier onTick=()->1L;
        Peer(AeronCluster.Context context,int id)throws Exception{
            listener=context.egressListener();when(client.isClosed()).thenAnswer(x->closed.get());when(client.ingressPublication()).thenReturn(publication);when(publication.isConnected()).thenAnswer(x->connected.get());when(client.clusterSessionId()).thenReturn(7L);when(client.sendKeepAlive()).thenReturn(true);
            when(client.pollEgress()).thenAnswer(x->{polls++;onPoll.run();return 0;});
            doAnswer(x->{closed.set(true);connected.set(false);if(closeError)throw new IllegalStateException("synthetic close failure");return null;}).when(client).close();
            when(client.offer(any(DirectBuffer.class),anyInt(),anyInt())).thenAnswer(call->{
                DirectBuffer bytes=call.getArgument(0);int offset=call.getArgument(1),length=call.getArgument(2);var codec=new AeronReplicationCodec();
                if(codec.tryDecodeSymbolRegister(bytes,offset,length)==AeronReplicationCodec.OK){long request=codec.symbolRequestId();requests.add(request);onSymbol.accept(request);if(symbolResult>=0)ack(request,id,7);return symbolResult;}
                var input=new InputEvent();assertEquals(AeronReplicationCodec.OK,codec.tryDecodeInput(bytes,offset,length,input));assertEquals(InputEvent.TYPE_PRICE_TICK,input.type);tickAttempts++;if(throwAfterTick){ticks.add(input);throw new io.aeron.exceptions.AeronException("synthetic exception after possibly accepted frame");}long result=onTick.getAsLong();if(result>=0)ticks.add(input);return result;
            });
        }
        void ack(long request,int id,long nativeSession){var bytes=new UnsafeBuffer(new byte[21]);bytes.putInt(8,id);bytes.putByte(12,MatchingEngineClusteredService.KIND_SYMBOL_REGISTERED);bytes.putLong(13,request);listener.onMessage(nativeSession,0,bytes,0,21,null);}
    }
    static final class Fixture implements AutoCloseable{
        final Clock clock=new Clock();final MediaDriver driver=mock(MediaDriver.class);final Connection broker=mock(Connection.class);final Dispatcher dispatcher=mock(Dispatcher.class);
        final List<Peer>peers=new ArrayList<>();final List<String>logs=new CopyOnWriteArrayList<>();final List<Long>caps=new ArrayList<>();
        final AtomicReference<Throwable>failure=new AtomicReference<>();final AtomicReference<MessageHandler>handler=new AtomicReference<>();
        final AtomicReference<Connection.Status>natsState=new AtomicReference<>(Connection.Status.CONNECTED);
        final FeedAdapterMain adapter;int calls,natsOpened;boolean quote=true;Runnable afterSubscribe=()->{};Action action;Thread owner;
        Fixture(FeedAdapterMain.Retry retry)throws Exception{
            when(broker.getStatus()).thenAnswer(x->natsState.get());doAnswer(x->{natsState.set(Connection.Status.CLOSED);return null;}).when(broker).close();
            when(broker.createDispatcher(any(MessageHandler.class))).thenAnswer(call->{handler.set(call.getArgument(0));return dispatcher;});
            doAnswer(x->{if(quote)deliver("ABC",1.25);afterSubscribe.run();return dispatcher;}).when(dispatcher).subscribe("pricing.>");
            adapter=new FeedAdapterMain(new FeedAdapterMain.Settings("nats://fixture","0=fixture:123","/private/tmp/not-opened","aeron:udp?endpoint=localhost:0",50,retry),ctx->{if(++calls>200)throw new InterruptedException("bounded negative-control safety");caps.add(ctx.messageTimeoutNs());return action.connect(ctx,calls);},clock,new FeedAdapterMain.Resources(){public MediaDriver driver(FeedAdapterMain.Settings s){return driver;}public Connection nats(FeedAdapterMain.Settings s){natsOpened++;return broker;}},line->{logs.add(line);System.out.println(line);});
        }
        Peer peer(AeronCluster.Context ctx,int id)throws Exception{var peer=new Peer(ctx,id);peers.add(peer);return peer;}
        void deliver(String ticker,double px){deliverRaw(ticker,"{\"payload\":{\"price\":"+px+"}}");}
        void deliverRaw(String ticker,String json){var message=mock(Message.class);when(message.getSubject()).thenReturn("pricing."+ticker);when(message.getData()).thenReturn(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));try{handler.get().onMessage(message);}catch(Exception error){throw new IllegalStateException(error);}}
        void run()throws Exception{owner=new Thread(()->{try{adapter.run();}catch(Throwable error){failure.set(error);}},"owned-feed-fixture");owner.start();owner.join(5000);assertFalse(owner.isAlive(),"bounded actual adapter loop ends and reaps its resources");}
        JsonNode status()throws Exception{return JSON.readTree(adapter.recoveryJson());}
        void clean(int expectedClients)throws Exception{assertEquals(expectedClients,peers.size());verify(driver,times(1)).close();verify(broker,times(natsOpened)).close();verify(broker,times(natsOpened)).createDispatcher(any(MessageHandler.class));verify(dispatcher,times(natsOpened)).subscribe("pricing.>");for(var peer:peers)verify(peer.client,times(1)).close();}
        public void close()throws Exception{if(owner!=null&&owner.isAlive()){owner.interrupt();owner.join(3000);assertFalse(owner.isAlive());}}
    }
}
