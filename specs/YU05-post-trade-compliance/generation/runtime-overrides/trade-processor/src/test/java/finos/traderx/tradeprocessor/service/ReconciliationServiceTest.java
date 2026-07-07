package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.model.TradeState;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
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
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control");
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
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control");

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
            new ReconciliationService(tradeRepository, baseUrl(), "dev-recon-control");
        reconciliationService.sweep();

        assertEquals(0, reconciliationService.status().cursor());
        assertEquals(0, reconciliationService.status().matched());
    }
}
