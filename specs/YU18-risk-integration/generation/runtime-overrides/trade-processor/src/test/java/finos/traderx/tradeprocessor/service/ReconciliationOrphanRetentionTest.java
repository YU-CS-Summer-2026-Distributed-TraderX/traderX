package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Actual uninstrumented service + owned HTTP/read-only repository; no database. */
class ReconciliationOrphanRetentionTest {
    private HttpServer server;
    private ReconciliationService service;
    private SimpleMeterRegistry meters;
    private List<String> local = List.of();
    @BeforeEach void open() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/recon/full-history",exchange->{
            byte[] body=(exchange.getRequestURI().getPath().endsWith("reindex")?"{}":"[]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}exchange.close();
        });server.start();
        TradeRepository repository=(TradeRepository)Proxy.newProxyInstance(TradeRepository.class.getClassLoader(),new Class<?>[]{TradeRepository.class},(proxy,method,args)->{
            if(method.getName().equals("findAllIds"))return local;
            throw new IllegalStateException("unexpected repository call "+method.getName());
        });
        meters=new SimpleMeterRegistry();service=new ReconciliationService(repository,"http://127.0.0.1:"+server.getAddress().getPort(),"owned-retention-test-key",meters);
    }
    @AfterEach void close() throws Exception {
        try { if(service!=null){Field f=ReconciliationService.class.getDeclaredField("httpClient");f.setAccessible(true);((HttpClient)f.get(service)).close();} }
        finally {if(server!=null)server.stop(0);if(meters!=null)meters.close();}
    }
    private void ids(int count,boolean repeated) {
        local=new ArrayList<>();for(int i=0;i<count;i++)local.add("orphan-"+(repeated?i/2:i));
    }
    private static int capacity(List<String> list) throws Exception {
        assertEquals(ArrayList.class,list.getClass(),"capped result must not retain an ArrayList.SubList parent");
        Field f=ArrayList.class.getDeclaredField("elementData");f.setAccessible(true);return ((Object[])f.get(list)).length;
    }
    @Test void aboveCapKeepsCountsOrderedPrefixAndOnlyBoundedStorage() throws Exception {
        ids(700,false);var result=service.runOrphanSweep();
        assertSame(result,service.lastOrphanSweep());assertEquals(700,result.localTradeCount());assertEquals(0,result.fullHistoryTradeCount());assertEquals(700,result.orphanCount());
        assertEquals(local.subList(0,500),result.orphanIds());assertEquals(500,capacity(result.orphanIds()));
        result.orphanIds().set(0,"mutable");assertEquals("mutable",service.lastOrphanSweep().orphanIds().get(0));
    }
    @Test void repeatedIdsKeepTheirPositionsAndFullCount() throws Exception {
        ids(1200,true);var result=service.runOrphanSweep();assertEquals(1200,result.orphanCount());
        assertEquals(local.subList(0,500),result.orphanIds());assertEquals(250,result.orphanIds().stream().distinct().count());assertEquals(500,capacity(result.orphanIds()));
    }
    @Test void atCapBelowCapAndEmptyKeepExistingListSemantics() throws Exception {
        for(int n:new int[]{0,20,500}){ids(n,false);var result=service.runOrphanSweep();assertEquals(n,result.orphanCount());assertEquals(local,result.orphanIds());
            assertEquals(ArrayList.class,result.orphanIds().getClass());if(n>0){result.orphanIds().set(0,"mutable");assertEquals("mutable",result.orphanIds().get(0));}}
    }
    @Test void subsequentSweepReplacesTheSavedResult() throws Exception {
        ids(700,false);var old=service.runOrphanSweep();ids(1,false);var latest=service.runOrphanSweep();
        assertNotSame(old,latest);assertSame(latest,service.lastOrphanSweep());assertEquals(1,latest.orphanCount());assertEquals(1,latest.orphanIds().size());assertEquals(500,old.orphanIds().size());
    }
}
