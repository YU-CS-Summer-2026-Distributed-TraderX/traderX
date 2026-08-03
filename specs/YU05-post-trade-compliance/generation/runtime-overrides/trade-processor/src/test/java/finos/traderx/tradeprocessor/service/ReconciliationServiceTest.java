package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.model.TradeState;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * YU05 (post-trade-compliance, ADR-022, FR-PTC04/05): exercises the real HTTP + JSON path against
 * a stub order-matcher blotter endpoint (JDK {@link HttpServer}, no new test dependency), proving
 * MATCHED / MISSING_IN_PROJECTION / FIELD_MISMATCH classification and forward cursor advancement.
 */
class ReconciliationServiceTest {
    private HttpServer server;
    private final AtomicReference<String> nextResponseBody = new AtomicReference<>("[]");
    private final AtomicReference<String> fullHistoryResponseBody = new AtomicReference<>("[]");
    private final AtomicInteger reindexCallCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/recon/trades/blotter", exchange -> {
            byte[] body = nextResponseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/recon/full-history/reindex", exchange -> {
            reindexCallCount.incrementAndGet();
            byte[] body = "{\"indexedTrades\":0,\"evictions\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/recon/full-history/trades", exchange -> {
            // Slice-1 fixture: a single unpaginated page (real pagination proven by the forward
            // sweep's cursor-advancement test above the same mechanism uses).
            String query = exchange.getRequestURI().getQuery();
            boolean firstCall = query == null || query.equals("sinceSeq=0");
            byte[] body = (firstCall ? fullHistoryResponseBody.get() : "[]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void classifiesMatchedMissingAndMismatchedEntries() {
        TradeRepository tradeRepository = mock(TradeRepository.class);

        Trade matchedTrade = new Trade();
        matchedTrade.setId("trd-09b-1");
        matchedTrade.setAccountId(22214);
        matchedTrade.setSecurity("IBM");
        matchedTrade.setSide(TradeSide.Buy);
        matchedTrade.setQuantity(100);
        matchedTrade.setPrice(new BigDecimal("136.250"));
        when(tradeRepository.findById("trd-09b-1")).thenReturn(Optional.of(matchedTrade));

        Trade mismatchedTrade = new Trade();
        mismatchedTrade.setId("trd-09b-2");
        mismatchedTrade.setAccountId(22214);
        mismatchedTrade.setSecurity("IBM");
        mismatchedTrade.setSide(TradeSide.Buy);
        mismatchedTrade.setQuantity(50); // blotter says 100 -> FIELD_MISMATCH
        mismatchedTrade.setPrice(new BigDecimal("136.250"));
        when(tradeRepository.findById("trd-09b-2")).thenReturn(Optional.of(mismatchedTrade));

        when(tradeRepository.findById("trd-09b-3")).thenReturn(Optional.empty()); // MISSING_IN_PROJECTION

        nextResponseBody.set("""
            [
              {"id":"trd-09b-1","tradeSeq":1,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000000},
              {"id":"trd-09b-2","tradeSeq":2,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000001},
              {"id":"trd-09b-3","tradeSeq":3,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000002}
            ]
            """);

        ReconciliationService reconciliationService =
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control", new SimpleMeterRegistry());
        reconciliationService.sweep();

        ReconciliationService.StatusSnapshot status = reconciliationService.status();
        assertEquals(1, status.matched());
        assertEquals(1, status.fieldMismatch());
        assertEquals(1, status.missingInProjection());
        assertEquals(3, status.cursor());
    }

    @Test
    void cursorAdvancesAcrossSweepsAndDoesNotReclassifyThePreviousPage() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        when(tradeRepository.findById("trd-09b-1")).thenReturn(Optional.empty());
        when(tradeRepository.findById("trd-09b-2")).thenReturn(Optional.empty());

        ReconciliationService reconciliationService =
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control", new SimpleMeterRegistry());

        nextResponseBody.set("""
            [{"id":"trd-09b-1","tradeSeq":1,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000000}]
            """);
        reconciliationService.sweep();
        assertEquals(1, reconciliationService.status().cursor());
        assertEquals(1, reconciliationService.status().missingInProjection());

        nextResponseBody.set("""
            [{"id":"trd-09b-2","tradeSeq":2,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000001}]
            """);
        reconciliationService.sweep();
        assertEquals(2, reconciliationService.status().cursor());
        assertEquals(2, reconciliationService.status().missingInProjection());
    }

    @Test
    void unreachableOrderMatcherSkipsTheCycleWithoutMovingTheCursor() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        server.stop(0); // simulate order-matcher being unreachable

        ReconciliationService reconciliationService =
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control", new SimpleMeterRegistry());
        reconciliationService.sweep();

        assertEquals(0, reconciliationService.status().cursor());
        assertEquals(0, reconciliationService.status().matched());
    }

    @Test
    void orphanSweepTriggersReindexAndFlagsLocalTradesMissingFromFullHistory() throws Exception {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        // Local DB has three trades; full-history index only knows about two of them.
        when(tradeRepository.findAllIds()).thenReturn(List.of("trd-09b-1", "trd-09b-2", "trd-09b-3"));
        fullHistoryResponseBody.set("""
            [
              {"id":"trd-09b-1","tradeSeq":1,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000000},
              {"id":"trd-09b-2","tradeSeq":2,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000001}
            ]
            """);

        ReconciliationService reconciliationService =
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control", new SimpleMeterRegistry());
        ReconciliationService.OrphanSweepResult result = reconciliationService.runOrphanSweep();

        assertEquals(1, reindexCallCount.get());
        assertEquals(3, result.localTradeCount());
        assertEquals(2, result.fullHistoryTradeCount());
        assertEquals(1, result.orphanCount());
        assertTrue(result.orphanIds().contains("trd-09b-3"));
        assertEquals(result, reconciliationService.lastOrphanSweep());
    }

    @Test
    void orphanSweepReportsNoOrphansWhenEveryLocalTradeIsInFullHistory() throws Exception {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        when(tradeRepository.findAllIds()).thenReturn(List.of("trd-09b-1"));
        fullHistoryResponseBody.set("""
            [{"id":"trd-09b-1","tradeSeq":1,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000000}]
            """);

        ReconciliationService reconciliationService =
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control", new SimpleMeterRegistry());
        ReconciliationService.OrphanSweepResult result = reconciliationService.runOrphanSweep();

        assertEquals(0, result.orphanCount());
        assertTrue(result.orphanIds().isEmpty());
    }

    @Test
    void frPtc10OneRunAccountsForMatchedMissingAndOrphanProjectionRows() throws Exception {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        Trade matched = new Trade();
        matched.setId("trd-09b-1");
        matched.setAccountId(22214);
        matched.setSecurity("IBM");
        matched.setSide(TradeSide.Buy);
        matched.setQuantity(100);
        matched.setPrice(new BigDecimal("136.250"));
        when(tradeRepository.findById("trd-09b-1")).thenReturn(Optional.of(matched));
        when(tradeRepository.findById("trd-09b-2")).thenReturn(Optional.empty());
        when(tradeRepository.findAllIds()).thenReturn(List.of("trd-09b-1", "trd-local-orphan"));

        nextResponseBody.set("""
            [
              {"id":"trd-09b-1","tradeSeq":1,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000000},
              {"id":"trd-09b-2","tradeSeq":2,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000001}
            ]
            """);
        fullHistoryResponseBody.set(nextResponseBody.get());

        ReconciliationService reconciliationService =
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control", new SimpleMeterRegistry());
        reconciliationService.sweep();
        ReconciliationService.OrphanSweepResult orphan = reconciliationService.runOrphanSweep();

        assertEquals(1, reconciliationService.status().matched());
        assertEquals(1, reconciliationService.status().missingInProjection());
        assertEquals(1, orphan.orphanCount());
        assertEquals(List.of("trd-local-orphan"), orphan.orphanIds());
        assertEquals(2, orphan.fullHistoryTradeCount());
    }
}
