package finos.traderx.tradeprocessor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.tradeprocessor.auth.JwtTokenMinter;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * YU05 (post-trade-compliance, ADR-022, FR-PTC04/05): scheduled forward reconciliation sweep
 * against order-matcher's replay-safe trade blotter (see order-matcher's {@code ReconController}).
 * Read-only against order-matcher (never mutates journal/BLP state, FR-PTC07); classification
 * result is in-memory only in slice 1 (no persisted recon-result table yet).
 *
 * <p>Known slice-1 limitation (documented in spec.md): because the blotter is a bounded, forward
 * window, this sweep can prove {@code MATCHED} / {@code MISSING_IN_PROJECTION} / {@code
 * FIELD_MISMATCH}, but cannot prove {@code ORPHAN_IN_PROJECTION} (a DB row with no corresponding
 * fill anywhere in a potentially much longer journal history) — deferred to FR-PTC10.</p>
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    // FR-PTC10: a full journal replay can take a long time on a large journal; the orphan sweep's
    // reindex-trigger call gets a much longer budget than the routine forward-sweep polls above.
    private static final Duration FULL_HISTORY_REINDEX_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_REPORTED_ORPHANS = 500;

    private final TradeRepository tradeRepository;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    // YU05 (post-trade-compliance, ADR-025): this service calls order-matcher's admin-only
    // /recon/* endpoints on its own behalf (not a human operator's), so it mints its own
    // long-lived, admin-scoped service-account JWT at construction time rather than holding a
    // human's token. Same shared secret as order-matcher, so the token it mints validates there.
    private final String serviceAuthorization;

    private volatile long cursor = 0L;
    private volatile Instant lastSweepAt;
    private final LongAdder matched = new LongAdder();
    private final LongAdder missingInProjection = new LongAdder();
    private final LongAdder fieldMismatch = new LongAdder();

    private volatile OrphanSweepResult lastOrphanSweep;

    public ReconciliationService(TradeRepository tradeRepository,
                                 @Value("${order-matcher.base-url:http://order-matcher:18110}") String baseUrl,
                                 @Value("${auth.jwt.secret:dev-jwt-shared-secret}") String jwtSecret,
                                 MeterRegistry meterRegistry) {
        this.tradeRepository = tradeRepository;
        this.baseUrl = baseUrl;
        String token = new JwtTokenMinter(jwtSecret)
            .mint("trade-processor-reconciliation-service", Set.of(), true, 0L);
        this.serviceAuthorization = "Bearer " + token;

        Gauge.builder("traderx_recon_matched_total", matched, LongAdder::sum).register(meterRegistry);
        Gauge.builder("traderx_recon_missing_in_projection_total", missingInProjection, LongAdder::sum)
            .register(meterRegistry);
        Gauge.builder("traderx_recon_field_mismatch_total", fieldMismatch, LongAdder::sum).register(meterRegistry);
        Gauge.builder("traderx_recon_cursor", this, s -> s.cursor).register(meterRegistry);
        Gauge.builder("traderx_recon_orphan_total", this,
            s -> s.lastOrphanSweep == null ? 0 : s.lastOrphanSweep.orphanCount()).register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${recon.poll.interval-ms:10000}")
    public void sweep() {
        // Fetch from one BELOW the cursor, not from the cursor: the blotter is strictly-greater-than
        // paginated, so this re-reads the single entry the cursor points at, and the engine still
        // holding it is the proof that we are counting against the epoch we counted against last
        // cycle. Costs one redundant entry per poll and no extra call. (Re-read, not re-classified —
        // see the skip below, or `matched` would climb by one every ten seconds.)
        long sinceSeq = cursor > 0 ? cursor - 1 : 0;
        List<BlotterEntry> page;
        try {
            page = fetchBlotterPage(sinceSeq);
        } catch (Exception ex) {
            // UNREACHABLE IS NOT ROLLED. "I cannot see the engine" and "the engine renumbered" are
            // different verdicts, and treating the first as the second lets one network blip erase a
            // real miss count. Nothing is reset here. Nor is it silently taken as "unchanged": the
            // cycle does not touch lastSweepAt, so a reader of /recon/status sees it stop advancing
            // and knows the counters are a stale reading rather than a current one.
            log.warn("Reconciliation sweep skipped this cycle (order-matcher unreachable): {}", ex.toString());
            return;
        }
        // The engine holds nothing at or above our cursor, so its trade counter has gone BACKWARDS.
        // That is the epoch roll -- the same condition scripts/yu15/run-proofs.sh already trusts when
        // it compares member 0's tradeCounter against the highest trade id in SQL.
        if (cursor > 0 && page.isEmpty()) {
            resetForNewEpoch();
            return;
        }
        for (BlotterEntry entry : page) {
            if (entry.tradeSeq() <= cursor) {
                continue; // the epoch-check entry; already classified on the cycle that advanced past it
            }
            classify(entry);
            cursor = entry.tradeSeq();
        }
        lastSweepAt = Instant.now();
        if (!page.isEmpty()) {
            log.info("Reconciliation sweep processed {} blotter entries; cursor now {}", page.size(), cursor);
        }
    }

    /**
     * A fresh-epoch roll wipes the log and restarts the engine's trade counter at 1, so the cursor
     * and the three tallies now describe a world that no longer exists. Trade ids are {@code
     * <tradeSeq>-<side>} and carry no epoch qualification, so an id the cursor has already advanced
     * past resolves to a DIFFERENT trade after the roll -- which is why re-checking misses in place
     * would not be enough on its own; the stale id can match spuriously. Start over instead.
     *
     * <p>A false positive here (an order-matcher answering mid-replay, say) costs one full
     * reclassification from seq 0 and says so in the log -- which is exactly the remedy
     * {@code scripts/proofs/yu05-recon.sh} performs by hand today, by restarting trade-processor
     * before it will believe a forward-sweep verdict.</p>
     *
     * <p>This does NOT make the incremental counters authoritative. The full-history orphan sweep
     * re-examines current state and remains the comparison to cite; these tallies stay once-per-entry
     * and forward-only, now merely scoped to the live epoch instead of spanning dead ones.</p>
     */
    private void resetForNewEpoch() {
        // A counter that silently returns to zero is its own kind of untrustworthy: an operator
        // watching missingInProjection drop needs to know whether that was a repair or a reset.
        log.warn("recon EPOCH_RESET order-matcher holds no trade at or above tradeSeq {} -- its trade "
                + "counter restarted, so the epoch these counters describe is gone. Discarding "
                + "cursor={} matched={} missingInProjection={} fieldMismatch={}; reclassifying from 0. "
                + "The full-history orphan sweep, not these counters, remains the authoritative "
                + "comparison.",
            cursor, cursor, matched.sum(), missingInProjection.sum(), fieldMismatch.sum());
        cursor = 0L;
        // LongAdder.reset() is only safe with no concurrent updates, and that holds here: sweep() is
        // the sole writer of all three (classify() is called from nowhere else) and Spring runs the
        // scheduled sweep on one thread, so this is on the writer thread with no sweep in flight.
        matched.reset();
        missingInProjection.reset();
        fieldMismatch.reset();
    }

    private void classify(BlotterEntry entry) {
        Optional<Trade> local = tradeRepository.findById(entry.id());
        if (local.isEmpty()) {
            missingInProjection.increment();
            log.warn("recon MISSING_IN_PROJECTION id={} account={} security={}",
                entry.id(), entry.accountId(), entry.security());
            return;
        }
        Trade trade = local.get();
        boolean matches = trade.getAccountId() != null && trade.getAccountId().equals(entry.accountId())
            && entry.security().equals(trade.getSecurity())
            && trade.getSide() != null && trade.getSide().name().equals(entry.side())
            && trade.getQuantity() != null && trade.getQuantity().equals(entry.quantity())
            && trade.getPrice() != null && trade.getPrice().compareTo(entry.price()) == 0;
        if (matches) {
            matched.increment();
        } else {
            fieldMismatch.increment();
            log.warn("recon FIELD_MISMATCH id={}", entry.id());
        }
    }

    private List<BlotterEntry> fetchBlotterPage(long sinceSeq) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/recon/trades/blotter?sinceSeq=" + sinceSeq);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(HTTP_TIMEOUT)
            .header("Authorization", serviceAuthorization)
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("recon blotter fetch failed: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), new TypeReference<List<BlotterEntry>>() { });
    }

    public StatusSnapshot status() {
        return new StatusSnapshot(cursor, matched.sum(), missingInProjection.sum(), fieldMismatch.sum(), lastSweepAt);
    }

    /**
     * FR-PTC10: full-history orphan detection. Triggers order-matcher's on-demand full journal
     * reindex, then walks every locally-known trade id and flags any not present anywhere in that
     * full-history index as {@code ORPHAN_IN_PROJECTION} — a MariaDB row with no corresponding
     * journal fill at all (as opposed to {@link #sweep()}'s forward-only window). Expensive and
     * synchronous by design (mirrors the reindex it depends on) — never scheduled, always
     * explicitly triggered via {@code POST /recon/orphan-sweep}.
     */
    public synchronized OrphanSweepResult runOrphanSweep() throws IOException, InterruptedException {
        triggerFullHistoryReindex();

        Set<String> fullHistoryIds = new HashSet<>();
        long sinceSeq = 0;
        while (true) {
            List<BlotterEntry> page = fetchFullHistoryPage(sinceSeq);
            if (page.isEmpty()) {
                break;
            }
            for (BlotterEntry entry : page) {
                fullHistoryIds.add(entry.id());
                if (entry.tradeSeq() > sinceSeq) {
                    sinceSeq = entry.tradeSeq();
                }
            }
        }

        List<String> localIds = tradeRepository.findAllIds();
        List<String> orphans = new ArrayList<>();
        for (String id : localIds) {
            if (!fullHistoryIds.contains(id)) {
                orphans.add(id);
                log.warn("recon ORPHAN_IN_PROJECTION id={}", id);
            }
        }

        OrphanSweepResult result = new OrphanSweepResult(
            Instant.now(), localIds.size(), fullHistoryIds.size(), orphans.size(),
            orphans.size() > MAX_REPORTED_ORPHANS ? orphans.subList(0, MAX_REPORTED_ORPHANS) : orphans);
        lastOrphanSweep = result;
        log.info("Orphan sweep complete: {} local trades, {} full-history trades, {} orphan(s)",
            localIds.size(), fullHistoryIds.size(), orphans.size());
        return result;
    }

    public OrphanSweepResult lastOrphanSweep() {
        return lastOrphanSweep;
    }

    private void triggerFullHistoryReindex() throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/recon/full-history/reindex");
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(FULL_HISTORY_REINDEX_TIMEOUT)
            .header("Authorization", serviceAuthorization)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("full-history reindex trigger failed: HTTP " + response.statusCode());
        }
    }

    private List<BlotterEntry> fetchFullHistoryPage(long sinceSeq) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/recon/full-history/trades?sinceSeq=" + sinceSeq);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(HTTP_TIMEOUT)
            .header("Authorization", serviceAuthorization)
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("full-history trades fetch failed: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), new TypeReference<List<BlotterEntry>>() { });
    }

    /** Mirrors order-matcher's {@code TradeBlotter.TradeRecord} JSON shape exactly. */
    public record BlotterEntry(String id, long tradeSeq, int accountId, String security, String side,
                                int quantity, BigDecimal price, long execTimeMillis) { }

    public record StatusSnapshot(long cursor, long matched, long missingInProjection, long fieldMismatch,
                                  Instant lastSweepAt) { }

    /** {@code orphanIds} is capped at {@link #MAX_REPORTED_ORPHANS} even when {@code orphanCount} is larger. */
    public record OrphanSweepResult(Instant sweptAt, int localTradeCount, int fullHistoryTradeCount,
                                     int orphanCount, List<String> orphanIds) { }
}
