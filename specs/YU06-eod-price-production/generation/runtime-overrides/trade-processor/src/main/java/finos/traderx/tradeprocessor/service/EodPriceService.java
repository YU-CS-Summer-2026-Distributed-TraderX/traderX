package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.model.EodPrice;
import finos.traderx.tradeprocessor.model.EodQuality;
import finos.traderx.tradeprocessor.model.EodReport;
import finos.traderx.tradeprocessor.repository.EodPriceSnapshotRepository;
import finos.traderx.tradeprocessor.service.PriceHistoryStore.PriceSample;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * YU06 (eod-price-production, ADR-026/028): produces versioned closing-price snapshots, applies
 * operator overrides as new versions, and gates publication behind the fail-safe (FR-EOD23) before
 * emitting {@code EOD_PRICES_READY}. Reads closing prices from YU05's existing
 * {@link PriceHistoryStore} (last-trade prints on {@code pricing.*}); never touches the BLP.
 */
@Service
public class EodPriceService {
    private static final Logger log = LoggerFactory.getLogger(EodPriceService.class);

    public enum PublishStatus { PUBLISHED, ALREADY_PUBLISHED, BLOCKED, NOT_FOUND }

    public record PublishOutcome(PublishStatus status, EodReport report) { }

    private final PriceHistoryStore priceHistory;
    private final EodQualityChecker qualityChecker;
    private final EodPriceSnapshotRepository repo;
    private final EodEventPublisher eventPublisher;
    private final List<String> configuredUniverse;
    private final boolean autoPublish;

    private final Counter sessionsPublished;
    private final Counter qualityFlagged;
    private final AtomicLong lastPublishMillis = new AtomicLong(0);

    public EodPriceService(PriceHistoryStore priceHistory, EodQualityChecker qualityChecker,
                           EodPriceSnapshotRepository repo, EodEventPublisher eventPublisher,
                           MeterRegistry registry,
                           @Value("${eod.universe:}") List<String> configuredUniverse,
                           @Value("${eod.session.auto-publish:true}") boolean autoPublish) {
        this.priceHistory = priceHistory;
        this.qualityChecker = qualityChecker;
        this.repo = repo;
        this.eventPublisher = eventPublisher;
        this.configuredUniverse = configuredUniverse.stream().filter(s -> !s.isBlank()).toList();
        this.autoPublish = autoPublish;
        this.sessionsPublished = Counter.builder("traderx_eod_sessions_published")
            .description("EOD price snapshot versions published (gate event emitted)").register(registry);
        this.qualityFlagged = Counter.builder("traderx_eod_quality_flagged")
            .description("Instruments flagged STALE/SPIKE/MISSING at production").register(registry);
        Gauge.builder("traderx_eod_last_publish_millis", lastPublishMillis, AtomicLong::get)
            .description("Epoch millis of the last EOD_PRICES_READY publish").register(registry);
    }

    /** Produce the next DRAFT version for a date, using now as the session-close instant. */
    public EodReport produce(LocalDate sessionDate) {
        return produce(sessionDate, System.currentTimeMillis());
    }

    /**
     * Produce the next DRAFT version as of {@code closeMillis} (injectable for tests). Classifies
     * every instrument in the universe, writes the version, and — when {@code eod.session.auto-publish}
     * is set and nothing is flagged — publishes it immediately (FR-EOD03/23).
     */
    public EodReport produce(LocalDate sessionDate, long closeMillis) {
        List<String> universe = resolveUniverse();
        List<EodPrice> prices = new ArrayList<>(universe.size());
        for (String security : universe) {
            Optional<PriceSample> sample = priceHistory.priceAtOrBefore(security, closeMillis);
            Optional<BigDecimal> prior = repo.priorPublishedClose(security, sessionDate);
            EodPrice priced = qualityChecker.classify(security, sample, prior, closeMillis);
            if (priced.isFlagged()) {
                qualityFlagged.increment();
            }
            prices.add(priced);
        }
        int version = repo.nextVersion(sessionDate);
        EodReport draft = EodReport.of(sessionDate, version, EodReport.DRAFT, prices);
        repo.write(draft);
        log.info("eod produce sessionDate={} version={} instruments={} flagged={}",
            sessionDate, version, draft.instrumentCount(), draft.flaggedCount());
        if (autoPublish && draft.flaggedCount() == 0) {
            return publish(sessionDate).report();
        }
        return draft;
    }

    /**
     * Apply an operator override for one instrument as a NEW version copying the latest (ADR-026 —
     * the snapshot is immutable, so a correction never edits a published row). The overridden
     * instrument becomes {@link EodQuality#OVERRIDDEN}; publication remains a separate explicit step.
     * Returns empty if no version exists for the date, or the security is not in the latest version.
     */
    public Optional<EodReport> override(LocalDate sessionDate, String security, BigDecimal price, String reason) {
        Optional<EodReport> latestOpt = repo.findLatest(sessionDate);
        if (latestOpt.isEmpty()) {
            return Optional.empty();
        }
        EodReport latest = latestOpt.get();
        boolean known = latest.instruments().stream().anyMatch(p -> p.security().equals(security));
        if (!known) {
            return Optional.empty();
        }
        List<EodPrice> newPrices = latest.instruments().stream()
            .map(p -> p.security().equals(security)
                ? new EodPrice(security, price, EodQuality.OVERRIDDEN, p.sourceTickMillis(), reason)
                : p)
            .toList();
        int version = repo.nextVersion(sessionDate);
        EodReport newVersion = EodReport.of(sessionDate, version, EodReport.DRAFT, newPrices);
        repo.write(newVersion);
        log.info("eod override sessionDate={} version={} security={} reason={}", sessionDate, version, security, reason);
        return Optional.of(newVersion);
    }

    /**
     * Gate + publish the latest version (FR-EOD22/23). {@code BLOCKED} (no event) if any instrument
     * is still flagged; {@code ALREADY_PUBLISHED} re-emits the (deduped) event so a run after a
     * missed emit recovers; otherwise marks published and emits {@code EOD_PRICES_READY}.
     */
    public PublishOutcome publish(LocalDate sessionDate) {
        Optional<EodReport> latestOpt = repo.findLatest(sessionDate);
        if (latestOpt.isEmpty()) {
            return new PublishOutcome(PublishStatus.NOT_FOUND, null);
        }
        EodReport latest = latestOpt.get();
        boolean already = EodReport.PUBLISHED.equals(latest.status());
        if (!already && latest.flaggedCount() > 0) {
            log.warn("eod publish BLOCKED sessionDate={} version={} flagged={}",
                sessionDate, latest.version(), latest.flaggedCount());
            return new PublishOutcome(PublishStatus.BLOCKED, latest);
        }
        long now = System.currentTimeMillis();
        if (!already) {
            repo.markPublished(sessionDate, latest.version(), now);
        }
        try {
            eventPublisher.publishPricesReady(sessionDate, latest.version(), latest.instrumentCount(), now);
        } catch (Exception ex) {
            // Version is committed as PUBLISHED; the event failed to emit. Re-running /publish is an
            // idempotent no-op that re-emits (deduped). Surface as a runtime error to the caller.
            throw new IllegalStateException("EOD_PRICES_READY publish failed for " + sessionDate
                + " v" + latest.version() + " (re-run /publish to retry)", ex);
        }
        if (!already) {
            sessionsPublished.increment();
        }
        lastPublishMillis.set(now);
        EodReport published = EodReport.of(sessionDate, latest.version(), EodReport.PUBLISHED, latest.instruments());
        return new PublishOutcome(already ? PublishStatus.ALREADY_PUBLISHED : PublishStatus.PUBLISHED, published);
    }

    public Optional<EodReport> latest(LocalDate sessionDate) {
        return repo.findLatest(sessionDate);
    }

    public Optional<EodReport> find(LocalDate sessionDate, int version) {
        return repo.find(sessionDate, version);
    }

    /**
     * The instrument universe to price: the configured {@code eod.universe} if set (so a listed
     * instrument with no tick is detected as MISSING — FR-EOD06), otherwise every ticker the price
     * feed has actually seen this session.
     */
    private List<String> resolveUniverse() {
        if (!configuredUniverse.isEmpty()) {
            return configuredUniverse;
        }
        return new ArrayList<>(new TreeSet<>(priceHistory.tickers()));
    }
}
