package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            // Honours sinceSeq exactly as order-matcher's TradeBlotter.since() does — STRICTLY
            // greater than. It has to: the epoch check reads whether the engine still holds the
            // entry the cursor points at, so a stub that ignored the query would answer every
            // probe the same way and could not tell an idle poll from a renumbered engine.
            byte[] body = blotterSince(nextResponseBody.get(), sinceSeqOf(exchange.getRequestURI().getQuery()))
                .getBytes(StandardCharsets.UTF_8);
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

    private static long sinceSeqOf(String query) {
        if (query == null) {
            return 0L;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("sinceSeq=")) {
                return Long.parseLong(part.substring("sinceSeq=".length()));
            }
        }
        return 0L;
    }

    /** The stub blotter's page: the entries of {@code wholeBlotter} with tradeSeq &gt; sinceSeq. */
    private static String blotterSince(String wholeBlotter, long sinceSeq) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> all =
            mapper.readValue(wholeBlotter, new TypeReference<List<Map<String, Object>>>() { });
        List<Map<String, Object>> page = new ArrayList<>();
        for (Map<String, Object> entry : all) {
            if (((Number) entry.get("tradeSeq")).longValue() > sinceSeq) {
                page.add(entry);
            }
        }
        return mapper.writeValueAsString(page);
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

        // The whole blotter, not just the new entry: the sweep re-reads from one below its cursor,
        // so trd-09b-1 comes back in this page and must not be counted a second time.
        nextResponseBody.set("""
            [
              {"id":"trd-09b-1","tradeSeq":1,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000000},
              {"id":"trd-09b-2","tradeSeq":2,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000001}
            ]
            """);
        reconciliationService.sweep();
        assertEquals(2, reconciliationService.status().cursor());
        assertEquals(2, reconciliationService.status().missingInProjection());
    }

    /**
     * The epoch roll: order-matcher's trade counter restarts at 1 and renumbers everything, so the
     * cursor and the tallies describe a world that is gone. Detected the way
     * {@code scripts/yu15/run-proofs.sh} detects it — the engine's counter is behind the high-water
     * mark we recorded — and the whole incremental state starts over.
     */
    @Test
    void epochRollResetsTheCountersAndTheCursorAndReclassifiesTheNewEpoch() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        when(tradeRepository.findById("old-1-B")).thenReturn(Optional.empty());
        when(tradeRepository.findById("old-2-S")).thenReturn(Optional.empty());
        when(tradeRepository.findById("old-3-B")).thenReturn(Optional.empty());

        nextResponseBody.set("""
            [
              {"id":"old-1-B","tradeSeq":1,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000000},
              {"id":"old-2-S","tradeSeq":2,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000001},
              {"id":"old-3-B","tradeSeq":3,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000002}
            ]
            """);

        ReconciliationService reconciliationService =
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control", new SimpleMeterRegistry());
        reconciliationService.sweep();
        assertEquals(3, reconciliationService.status().cursor());
        assertEquals(3, reconciliationService.status().missingInProjection());

        // THE ROLL. Fresh epoch: the log is wiped, the counter restarts at 1, and the ids are reused
        // for entirely different trades (ids are <tradeSeq>-<side> and carry no epoch qualification).
        // The engine's highest tradeSeq is now 2, i.e. BEHIND the cursor of 3.
        Trade newEpochTrade = new Trade();
        newEpochTrade.setId("old-1-B");
        newEpochTrade.setAccountId(30001);
        newEpochTrade.setSecurity("MSFT");
        newEpochTrade.setSide(TradeSide.Buy);
        newEpochTrade.setQuantity(7);
        newEpochTrade.setPrice(new BigDecimal("410.000"));
        when(tradeRepository.findById("old-1-B")).thenReturn(Optional.of(newEpochTrade));
        nextResponseBody.set("""
            [
              {"id":"old-1-B","tradeSeq":1,"accountId":30001,"security":"MSFT","side":"Buy","quantity":7,"price":410.000,"execTimeMillis":1700000900000},
              {"id":"old-2-S","tradeSeq":2,"accountId":30001,"security":"MSFT","side":"Sell","quantity":7,"price":410.000,"execTimeMillis":1700000900001}
            ]
            """);

        reconciliationService.sweep();
        ReconciliationService.StatusSnapshot afterReset = reconciliationService.status();
        assertEquals(0, afterReset.cursor(), "cursor must restart: seq 3 now names a different trade");
        assertEquals(0, afterReset.missingInProjection(), "a miss counted against a dead epoch is not a miss now");
        assertEquals(0, afterReset.matched());
        assertEquals(0, afterReset.fieldMismatch());

        // And the reset re-enables classification rather than merely zeroing: the next sweep reads
        // the new epoch from the top and judges it on its own terms.
        reconciliationService.sweep();
        ReconciliationService.StatusSnapshot fresh = reconciliationService.status();
        assertEquals(2, fresh.cursor());
        assertEquals(1, fresh.matched());            // old-1-B, now a real MSFT row
        assertEquals(1, fresh.missingInProjection()); // old-2-S, genuinely absent this epoch
    }

    /**
     * The negative control for the reset. An idle poll — the engine still holds the entry the
     * cursor points at, and nothing new has traded — must not look like a roll. This is what
     * distinguishes probing one below the cursor from probing at it: at the cursor the engine
     * answers with an empty page every quiet cycle, and a reset on that would fire perpetually.
     */
    @Test
    void idlePollWithNoNewTradesKeepsTheCountersAndTheCursor() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        when(tradeRepository.findById("trd-09b-1")).thenReturn(Optional.empty());
        when(tradeRepository.findById("trd-09b-2")).thenReturn(Optional.empty());

        nextResponseBody.set("""
            [
              {"id":"trd-09b-1","tradeSeq":1,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000000},
              {"id":"trd-09b-2","tradeSeq":2,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000001}
            ]
            """);

        ReconciliationService reconciliationService =
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control", new SimpleMeterRegistry());
        reconciliationService.sweep();
        assertEquals(2, reconciliationService.status().cursor());
        assertEquals(2, reconciliationService.status().missingInProjection());

        // Three quiet cycles against an unchanged blotter.
        reconciliationService.sweep();
        reconciliationService.sweep();
        reconciliationService.sweep();

        ReconciliationService.StatusSnapshot status = reconciliationService.status();
        assertEquals(2, status.cursor(), "an idle poll is not an epoch roll");
        assertEquals(2, status.missingInProjection(), "and the re-read entry must not be counted twice");
    }

    /**
     * "Cannot determine the epoch" is not "the epoch changed". A network blip must never be the
     * reason a real miss count is erased — resetting on an unreachable order-matcher would make the
     * counters trivially clean exactly when the least is known about them.
     */
    @Test
    void unreachableOrderMatcherDoesNotCountAsAnEpochChange() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        when(tradeRepository.findById("trd-09b-1")).thenReturn(Optional.empty());
        when(tradeRepository.findById("trd-09b-2")).thenReturn(Optional.empty());

        nextResponseBody.set("""
            [
              {"id":"trd-09b-1","tradeSeq":1,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000000},
              {"id":"trd-09b-2","tradeSeq":2,"accountId":22214,"security":"IBM","side":"Buy","quantity":100,"price":136.250,"execTimeMillis":1700000000001}
            ]
            """);

        ReconciliationService reconciliationService =
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control", new SimpleMeterRegistry());
        reconciliationService.sweep();
        assertEquals(2, reconciliationService.status().missingInProjection());

        server.stop(0);
        reconciliationService.sweep();

        ReconciliationService.StatusSnapshot status = reconciliationService.status();
        assertEquals(2, status.cursor(), "no answer is not an answer of 'renumbered'");
        assertEquals(2, status.missingInProjection());
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
