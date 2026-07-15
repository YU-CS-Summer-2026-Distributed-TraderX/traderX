package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.tradeprocessor.model.EodPrice;
import finos.traderx.tradeprocessor.model.EodQuality;
import finos.traderx.tradeprocessor.model.EodReport;
import finos.traderx.tradeprocessor.repository.EodPriceSnapshotRepository;
import finos.traderx.tradeprocessor.service.EodPriceService.PublishStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * YU06 (FR-EOD03/12/20/23): versioning, immutability, the publish fail-safe gate, override→new
 * version, and auto-publish. Uses an in-memory repository double (no H2 dependency needed) and a
 * disabled event publisher (no NATS socket).
 */
class EodPriceServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 8);
    private static final long CLOSE = 1_000_000_000_000L;

    /** In-memory stand-in for the JdbcTemplate-backed repo — models append + publish semantics. */
    private static final class InMemoryRepo extends EodPriceSnapshotRepository {
        private final Map<LocalDate, List<EodReport>> byDate = new HashMap<>();

        InMemoryRepo() {
            super(null);
        }

        @Override
        public Optional<Integer> latestVersion(LocalDate d) {
            List<EodReport> l = byDate.get(d);
            return (l == null || l.isEmpty()) ? Optional.empty() : Optional.of(l.size());
        }

        @Override
        public int nextVersion(LocalDate d) {
            return latestVersion(d).orElse(0) + 1;
        }

        @Override
        public Optional<EodReport> findLatest(LocalDate d) {
            return latestVersion(d).flatMap(v -> find(d, v));
        }

        @Override
        public Optional<EodReport> find(LocalDate d, int v) {
            List<EodReport> l = byDate.get(d);
            if (l == null || v < 1 || v > l.size()) {
                return Optional.empty();
            }
            return Optional.of(l.get(v - 1));
        }

        @Override
        public void write(EodReport r) {
            byDate.computeIfAbsent(r.sessionDate(), k -> new ArrayList<>()).add(r);
        }

        @Override
        public void markPublished(LocalDate d, int v, long ms) {
            List<EodReport> l = byDate.get(d);
            EodReport r = l.get(v - 1);
            l.set(v - 1, EodReport.of(d, v, EodReport.PUBLISHED, r.instruments()));
        }

        @Override
        public Optional<BigDecimal> priorPublishedClose(String security, LocalDate d) {
            return Optional.empty(); // spike logic covered in EodQualityCheckerTest
        }
    }

    private static final class CapturingPublisher extends EodEventPublisher {
        private final InMemoryRepo repo;
        private int calls;
        private LocalDate sessionDate;
        private int version;
        private String statusAtPublish;

        CapturingPublisher(InMemoryRepo repo) {
            super("nats://unused", "TRADERX_EOD", "eod.prices.ready", false);
            this.repo = repo;
        }

        @Override
        public void publishPricesReady(LocalDate date, int v, int instrumentCount, long publishedAtMillis) {
            calls++;
            sessionDate = date;
            version = v;
            statusAtPublish = repo.find(date, v).orElseThrow().status();
        }
    }

    private EodPriceService service(PriceHistoryStore history, List<String> universe) {
        return service(history, universe, new InMemoryRepo(),
            new EodEventPublisher("nats://localhost:4222", "TRADERX_EOD", "eod.prices.ready", false),
            new SimpleMeterRegistry(), true);
    }

    private EodPriceService service(PriceHistoryStore history, List<String> universe,
                                    InMemoryRepo repo, EodEventPublisher publisher,
                                    SimpleMeterRegistry registry, boolean autoPublish) {
        return new EodPriceService(history, new EodQualityChecker(300, new BigDecimal("20")),
            repo, publisher, registry, universe, autoPublish);
    }

    private PriceHistoryStore historyWith(Map<String, long[]> tickerToPriceAndAgeMs) {
        PriceHistoryStore h = new PriceHistoryStore(10000);
        tickerToPriceAndAgeMs.forEach((ticker, pa) ->
            h.record(ticker, BigDecimal.valueOf(pa[0]), CLOSE - pa[1]));
        return h;
    }

    @Test
    void cleanSessionAutoPublishesVersion1() {
        PriceHistoryStore h = historyWith(Map.of("AAA", new long[]{100, 1000}, "BBB", new long[]{200, 1000}));
        EodReport report = service(h, List.of()).produce(DATE, CLOSE);
        assertEquals(EodReport.PUBLISHED, report.status());
        assertEquals(1, report.version());
        assertEquals(2, report.instrumentCount());
        assertEquals(0, report.flaggedCount());
    }

    @Test
    void staleInstrumentBlocksAutoPublishAndStaysDraft() {
        PriceHistoryStore h = historyWith(Map.of("AAA", new long[]{100, 1000}, "CCC", new long[]{50, 400_000}));
        EodReport report = service(h, List.of()).produce(DATE, CLOSE);
        assertEquals(EodReport.DRAFT, report.status());
        assertEquals(1, report.flaggedCount());
        EodPrice ccc = report.instruments().stream().filter(p -> p.security().equals("CCC")).findFirst().orElseThrow();
        assertEquals(EodQuality.STALE, ccc.quality());
    }

    @Test
    void publishIsBlockedWhileFlagged() {
        PriceHistoryStore h = historyWith(Map.of("CCC", new long[]{50, 400_000}));
        EodPriceService svc = service(h, List.of());
        svc.produce(DATE, CLOSE); // draft v1, flagged
        assertEquals(PublishStatus.BLOCKED, svc.publish(DATE).status());
    }

    @Test
    void gateEventIsEmittedAfterCommitWithExactDateAndVersion() {
        PriceHistoryStore h = historyWith(Map.of("AAA", new long[]{100, 1000}));
        InMemoryRepo repo = new InMemoryRepo();
        CapturingPublisher publisher = new CapturingPublisher(repo);
        EodPriceService svc = service(h, List.of(), repo, publisher, new SimpleMeterRegistry(), false);

        EodReport draft = svc.produce(DATE, CLOSE);
        EodPriceService.PublishOutcome outcome = svc.publish(DATE);

        assertEquals(EodReport.DRAFT, draft.status());
        assertEquals(PublishStatus.PUBLISHED, outcome.status());
        assertEquals(1, publisher.calls);
        assertEquals(DATE, publisher.sessionDate);
        assertEquals(1, publisher.version);
        assertEquals(EodReport.PUBLISHED, publisher.statusAtPublish,
            "the durable gate is emitted only after the snapshot header is committed PUBLISHED");
    }

    @Test
    void blockedPublishLeavesDraftAndEmitsNoGateEvent() {
        PriceHistoryStore h = historyWith(Map.of("CCC", new long[]{50, 400_000}));
        InMemoryRepo repo = new InMemoryRepo();
        CapturingPublisher publisher = new CapturingPublisher(repo);
        EodPriceService svc = service(h, List.of(), repo, publisher, new SimpleMeterRegistry(), false);

        svc.produce(DATE, CLOSE);
        assertEquals(PublishStatus.BLOCKED, svc.publish(DATE).status());
        assertEquals(EodReport.DRAFT, repo.find(DATE, 1).orElseThrow().status());
        assertEquals(0, publisher.calls);
    }

    @Test
    void overrideCreatesNewVersionThenPublishes() {
        PriceHistoryStore h = historyWith(Map.of("AAA", new long[]{100, 1000}, "CCC", new long[]{50, 400_000}));
        EodPriceService svc = service(h, List.of());
        EodReport draft = svc.produce(DATE, CLOSE); // v1 draft, CCC stale
        assertEquals(1, draft.version());

        EodReport overridden = svc.override(DATE, "CCC", new BigDecimal("51.5"), "manual mark").orElseThrow();
        assertEquals(2, overridden.version());
        assertEquals(0, overridden.flaggedCount());
        EodPrice ccc = overridden.instruments().stream().filter(p -> p.security().equals("CCC")).findFirst().orElseThrow();
        assertEquals(EodQuality.OVERRIDDEN, ccc.quality());
        assertEquals(new BigDecimal("51.5"), ccc.closingPrice());

        EodPriceService.PublishOutcome out = svc.publish(DATE);
        assertEquals(PublishStatus.PUBLISHED, out.status());
        assertEquals(EodReport.PUBLISHED, out.report().status());

        // v1 is untouched (immutability): still draft with CCC stale.
        EodReport v1 = svc.find(DATE, 1).orElseThrow();
        assertEquals(EodReport.DRAFT, v1.status());
    }

    @Test
    void republishIsIdempotent() {
        PriceHistoryStore h = historyWith(Map.of("AAA", new long[]{100, 1000}));
        EodPriceService svc = service(h, List.of());
        svc.produce(DATE, CLOSE); // auto-published v1
        assertEquals(PublishStatus.ALREADY_PUBLISHED, svc.publish(DATE).status());
    }

    @Test
    void producingSameDateTwiceCreatesImmutablePublishedVersions() {
        PriceHistoryStore h = historyWith(Map.of("AAA", new long[]{100, 1000}));
        EodPriceService svc = service(h, List.of());

        EodReport v1 = svc.produce(DATE, CLOSE);
        h.record("AAA", new BigDecimal("101"), CLOSE + 1000);
        EodReport v2 = svc.produce(DATE, CLOSE + 1000);

        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        assertEquals(new BigDecimal("100"), svc.find(DATE, 1).orElseThrow()
            .instruments().get(0).closingPrice());
        assertEquals(new BigDecimal("101"), svc.find(DATE, 2).orElseThrow()
            .instruments().get(0).closingPrice());
        assertEquals(EodReport.PUBLISHED, svc.find(DATE, 1).orElseThrow().status());
    }

    @Test
    void metricsCountPublishedSessionsAndQualityFlagsWithoutTagCardinality() {
        PriceHistoryStore h = historyWith(Map.of("AAA", new long[]{100, 1000}, "CCC", new long[]{50, 400_000}));
        InMemoryRepo repo = new InMemoryRepo();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EodPriceService svc = service(h, List.of(), repo,
            new EodEventPublisher("nats://unused", "TRADERX_EOD", "eod.prices.ready", false),
            registry, false);

        svc.produce(DATE, CLOSE);
        assertEquals(1.0, registry.get("traderx_eod_quality_flagged").counter().count());
        assertEquals(0.0, registry.get("traderx_eod_sessions_published").counter().count());

        svc.override(DATE, "CCC", new BigDecimal("51"), "manual");
        svc.publish(DATE);
        assertEquals(1.0, registry.get("traderx_eod_sessions_published").counter().count());
        assertEquals(3, registry.getMeters().size(), "fixed aggregate metric cardinality");
        assertTrue(registry.getMeters().stream().allMatch(m -> m.getId().getTags().isEmpty()));
    }

    @Test
    void configuredUniverseDetectsMissingInstrument() {
        PriceHistoryStore h = historyWith(Map.of("AAA", new long[]{100, 1000}));
        EodReport report = service(h, List.of("AAA", "DDD")).produce(DATE, CLOSE);
        assertEquals(EodReport.DRAFT, report.status()); // blocked by the missing one
        EodPrice ddd = report.instruments().stream().filter(p -> p.security().equals("DDD")).findFirst().orElseThrow();
        assertEquals(EodQuality.MISSING, ddd.quality());
        assertTrue(report.instruments().stream().anyMatch(p -> p.security().equals("AAA") && p.quality() == EodQuality.OK));
        assertFalse(report.flaggedCount() == 0);
    }

    @Test
    void publishUnknownDateIsNotFound() {
        PriceHistoryStore h = historyWith(Map.of("AAA", new long[]{100, 1000}));
        assertEquals(PublishStatus.NOT_FOUND, service(h, List.of()).publish(LocalDate.of(2000, 1, 1)).status());
    }
}
