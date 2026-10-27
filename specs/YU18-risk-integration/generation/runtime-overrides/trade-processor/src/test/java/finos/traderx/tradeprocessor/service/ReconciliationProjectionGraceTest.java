package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Row projection: a sweep that runs between an engine fill and that fill's SQL insert must not record
 * a permanent MISSING. The side is held until the projection grace has passed since its fill; a late
 * insert inside the grace ends MATCHED, and a side that never arrives still ends MISSING.
 */
class ReconciliationProjectionGraceTest {
    private static final long FILL = 1_800_000_000_000L;
    private static final long GRACE = 120_000L;
    private HttpServer server;
    private final TradeRepository repo = mock(TradeRepository.class);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/recon/trades/blotter", ex -> {
            long since = Long.parseLong(ex.getRequestURI().getQuery().substring("sinceSeq=".length()));
            String page = List.of(1, 2, 3, 4).stream().filter(s -> s > since)
                .map(s -> "{\"id\":\"t-" + s + "\",\"tradeSeq\":" + s + ",\"accountId\":7,\"security\":\"IBM\",\"side\":\"Buy\","
                    + "\"quantity\":10,\"price\":100.0,\"execTimeMillis\":" + (FILL + s) + "}")
                .collect(Collectors.joining(",", "[", "]"));
            byte[] body = page.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        for (int s = 1; s <= 4; s++) { when(repo.findById("t-" + s)).thenReturn(Optional.empty()); }
    }

    @AfterEach
    void stop() { server.stop(0); }

    private ReconciliationService recon(long nowMillis) {
        ReconciliationService r = new ReconciliationService(repo, "http://localhost:" + server.getAddress().getPort(),
            "dev-recon-control", new SimpleMeterRegistry());
        r.projectionGraceMs(GRACE);
        at(r, nowMillis);
        return r;
    }

    private static void at(ReconciliationService r, long nowMillis) {
        r.clock(Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC));
    }

    private void insert(int seq) {
        Trade t = new Trade();
        t.setId("t-" + seq);
        t.setAccountId(7);
        t.setSecurity("IBM");
        t.setSide(TradeSide.Buy);
        t.setQuantity(10);
        t.setPrice(new BigDecimal("100.0"));
        when(repo.findById("t-" + seq)).thenReturn(Optional.of(t));
    }

    @Test
    void aSweepBeforeTheInsertEndsMatchedOnceTheRowLands() {
        insert(1);
        ReconciliationService r = recon(FILL + 5_000);
        r.sweep(); // t-2 filled but not yet in SQL
        assertEquals(1, r.status().matched());
        assertEquals(0, r.status().missingInProjection(), "inside the grace an absent side is not MISSING");
        assertEquals(1, r.status().cursor(), "the cursor holds below the unresolved side");

        insert(2);
        insert(3);
        insert(4);
        at(r, FILL + 15_000);
        r.sweep();
        var s = r.status();
        assertEquals(4, s.matched(), "the late insert resolves to MATCHED");
        assertEquals(0, s.missingInProjection());
        assertEquals(4, s.cursor());
    }

    @Test
    void aSideThatNeverArrivesStillEndsMissing() {
        insert(1);
        insert(4);
        ReconciliationService r = recon(FILL + 5_000);
        r.sweep();
        assertEquals(1, r.status().cursor());
        assertEquals(0, r.status().missingInProjection());

        at(r, FILL + GRACE + 10);
        r.sweep(); // t-2 and t-3 never arrive: both decided in ONE sweep, not one grace each
        var s = r.status();
        assertEquals(2, s.missingInProjection());
        assertEquals(2, s.matched());
        assertEquals(4, s.cursor());

        insert(2); // a row that lands after the verdict does not un-count it: the cursor has moved on
        r.sweep();
        assertEquals(2, r.status().missingInProjection());
    }

    @Test
    void onTheDurableFeedASideIsHeldPastTheGraceUntilTheReplayHasCaughtUp() {
        // Rows over the jetstream feed, back from an outage: t-2 and t-3 are still in the stream behind the replay.
        insert(1);
        insert(4);
        var feed = mock(finos.traderx.tradeprocessor.TradeFeedHandler.class);
        when(feed.jetstreamRows()).thenReturn(true);
        when(feed.caughtUpAt()).thenReturn(0L);   // not caught up since the restart
        ReconciliationService r = recon(FILL + GRACE + 60_000);
        r.subscribers(List.of(feed));
        r.sweep();
        assertEquals(0, r.status().missingInProjection(), "past the grace but the replay has not caught up: held");
        assertEquals(1, r.status().cursor());

        insert(2);   // the replay delivers t-2; t-3 was never in the stream
        when(feed.caughtUpAt()).thenReturn(FILL + GRACE + 65_000);
        at(r, FILL + GRACE + 70_000);
        r.sweep();
        var s = r.status();
        assertEquals(3, s.matched(), "the replayed side ends MATCHED");
        assertEquals(1, s.missingInProjection(), "the side the caught-up feed never delivered ends MISSING");
        assertEquals(4, s.cursor());
    }
}
