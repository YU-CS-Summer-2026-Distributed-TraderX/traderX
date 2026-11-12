package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FR-DRS-56: a sweep reads the engine's blotter to its head, holds what it cannot classify yet while the engine's
 * bounded window rolls on, and counts what the window dropped unread. The old sweep read one page an interval and let
 * the window pass over the rest with no count (run 6: 58,022 of ~125M sides classified, "0 missing").
 */
class ReconciliationSweepPagingTest {
    private static final long FILL = 1_800_000_000_000L;
    private static final int ENGINE_PAGE = 1_000;   // a member that ignores `limit`: the worst the recon meets
    private HttpServer server;
    private final TradeRepository repo = mock(TradeRepository.class);
    private final Set<String> projected = new HashSet<>();
    private volatile long oldest = 1, newest = 0;   // the engine's live window
    private ReconciliationService recon;
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/recon/trades/blotter", ex -> {
            requests.incrementAndGet();
            String q = ex.getRequestURI().getQuery();
            long since = Long.parseLong(q.substring("sinceSeq=".length(), q.indexOf('&')));
            byte[] body = LongStream.rangeClosed(Math.max(since + 1, oldest), newest).limit(ENGINE_PAGE)
                .mapToObj(s -> "{\"id\":\"t-" + s + "\",\"tradeSeq\":" + s + ",\"accountId\":7,\"security\":\"IBM\",\"side\":\"Buy\","
                    + "\"quantity\":10,\"price\":100.0,\"execTimeMillis\":" + FILL + "}")
                .collect(Collectors.joining(",", "[", "]")).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        when(repo.findById(anyString())).thenAnswer(call -> {
            String id = call.getArgument(0);
            if (!projected.contains(id) && !projected.contains("*")) return Optional.empty();
            Trade t = new Trade();
            t.setId(id); t.setAccountId(7); t.setSecurity("IBM"); t.setSide(TradeSide.Buy); t.setQuantity(10);
            t.setPrice(new BigDecimal("100.0"));
            return Optional.of(t);
        });
        recon = new ReconciliationService(repo, "http://localhost:" + server.getAddress().getPort(),
            "dev-recon-control", new SimpleMeterRegistry());
        recon.projectionGraceMs(120_000);
        recon.clock(Clock.fixed(Instant.ofEpochMilli(FILL + 1_000), ZoneOffset.UTC));   // every side is inside the grace
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void oneSweepReadsToTheEnginesHead() {
        projected.add("*");
        newest = 10_000;
        recon.sweep();
        var s = recon.status();
        assertEquals(10_000, s.matched(), "ten engine pages in one sweep; the old sweep classified 1,000");
        assertEquals(10_000, s.cursor());
        assertEquals(0, s.unverified());
    }

    @Test
    void sidesTheWindowDroppedUnreadAreCountedNotPassedOver() {
        projected.add("*");
        newest = 1_000;
        recon.sweep();
        oldest = 5_001; newest = 6_000;   // the window rolled past 1,001..5,000 before the next sweep
        recon.sweep();
        var s = recon.status();
        assertEquals(2_000, s.matched());
        assertEquals(6_000, s.cursor());
        assertEquals(4_000, s.unverified(), "tradeSeq 1,001..5,000 were never classified, and the status says so");
    }

    @Test
    void aSideHeldForItsProjectionIsStillClassifiedAfterTheWindowRollsPastIt() {
        newest = 1_000;   // filled on the engine, not in the projection yet
        recon.sweep();
        assertEquals(0, recon.status().cursor(), "held: inside the grace");
        assertEquals(1_000, recon.status().held());
        oldest = 2_001; newest = 3_000;   // the engine dropped the held sides, and 1,001..2,000 unread
        projected.add("*");               // the projection caught up
        recon.sweep();
        var s = recon.status();
        assertEquals(2_000, s.matched(), "the 1,000 held sides and the 1,000 the engine still has");
        assertEquals(0, s.missingInProjection());
        assertEquals(1_000, s.unverified());
        assertEquals(3_000, s.cursor());
    }

    /** FR-DRS-59: behind the engine's head, a step asks four ranges at once and joins them; the old step asked one. */
    @Test
    void behindTheHeadAStepReadsFourRangesAtOnce() {
        projected.add("*");
        newest = 100_000;
        recon.sweepBudgetMs(0);   // one step a sweep
        recon.sweep();            // the first step: one page, and it came back whole
        assertEquals(1_000, recon.status().matched());
        int before = requests.get();
        recon.sweep();            // behind: 1,000..1,999 (its first side re-read), then three ranges of 1,000
        assertEquals(4, requests.get() - before);
        assertEquals(4_999, recon.status().matched(), "four ranges in one step; one page a step read to 1,999");
        assertEquals(4_999, recon.status().cursor());
        assertEquals(0, recon.status().unverified());
    }

    /** A range that does not follow the one before it (the window rolled under the requests) is dropped, not joined. */
    @Test
    void aRangeTheWindowRolledPastIsNotJoinedAcrossTheHole() {
        projected.add("*");
        newest = 1_000;
        recon.sweepBudgetMs(0);
        recon.sweep();
        oldest = 50_001; newest = 100_000;   // every range of the next step lands below the window
        recon.sweep();
        var s = recon.status();
        assertEquals(49_000, s.unverified(), "1,001..50,000 dropped unread: counted once, and no range joined twice");
        assertEquals(2_000, s.matched());
        assertEquals(51_000, s.cursor());
    }

    @Test
    void aSweepStopsAtItsBudgetAndTheNextOneGoesOn() {
        projected.add("*");
        newest = 10_000;
        recon.sweepBudgetMs(0);   // one page a sweep
        recon.sweep();
        assertEquals(1_000, recon.status().matched());
        recon.sweepBudgetMs(5_000);
        recon.sweep();
        assertEquals(10_000, recon.status().matched());
        assertEquals(0, recon.status().unverified());
    }
}
