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
import java.util.Map;
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
    private RunRegistry runRegistry;
    private String projectionScope;
    private String descriptorHash;
    @org.springframework.beans.factory.annotation.Autowired
    public void runRegistry(RunRegistry registry) {this.runRegistry=registry;}

    // YU18 trade store (downstream-at-400k.md §7.6): with TRADE_PROJECTION_MODE=snapshot the SQL
    // `trades` table is a per-account window, so the ledger the engine's blotter is reconciled against
    // is the capture stream, and "in the projection" means a committed snapshot cursor covers it.
    private finos.traderx.tradeprocessor.TradeFeedHandler ledgerFeed;
    private TradeSnapshotStore snapshotStore;
    private LedgerReader ledger;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void subscribers(List<finos.traderx.messaging.Subscriber<?>> subscribers) {
        for (var s : subscribers) {
            if (s instanceof finos.traderx.tradeprocessor.TradeFeedHandler t) { ledgerFeed = t; }
        }
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void snapshotStore(TradeSnapshotStore store) { this.snapshotStore = store; }
    private boolean onLedger() { return ledgerFeed != null && ledgerFeed.ledgerMode() && snapshotStore != null; }

    private void bindScope() throws IOException,InterruptedException {
        if(runRegistry==null) return; // standalone legacy test seam
        String selected=runRegistry.activeScope();
        var run=runRegistry.scope(selected);
        String hash=(String)run.get("descriptor_hash");
        if(!java.util.Objects.equals(selected,projectionScope)) {
            long[] saved=runRegistry.recon(selected);
            cursor=saved[0];matched.reset();matched.add(saved[1]);
            missingInProjection.reset();missingInProjection.add(saved[2]);
            fieldMismatch.reset();fieldMismatch.add(saved[3]);
            projectionScope=selected;
        }
        descriptorHash=hash;
        verifyScope();
    }
    private void verifyScope() throws IOException,InterruptedException {
        if(runRegistry==null || descriptorHash==null) return; // explicitly unknown legacy, no epoch inference
        var request=HttpRequest.newBuilder(URI.create(baseUrl+"/run/status"))
            .timeout(HTTP_TIMEOUT).header("Authorization",serviceAuthorization).GET().build();
        var response=httpClient.send(request,HttpResponse.BodyHandlers.ofString());
        var status=mapper.readTree(response.body());
        if(response.statusCode()!=200 || !descriptorHash.equals(status.path("descriptorHash").asText())
            || !projectionScope.equals(status.path("projectionScope").asText())
            || (status.path("runPhase").asInt()!=2 && status.path("runPhase").asInt()!=3)) {
            throw new IOException("RUN_RECON_DESCRIPTOR_MISMATCH");
        }
    }
    private void validatePage(List<BlotterEntry> page) throws IOException {
        if(runRegistry==null || descriptorHash==null) return;
        String prefix="e1-"+runRegistry.scope(projectionScope).get("cluster_epoch")+"-";
        for(var e:page) if(!e.id().equals(prefix+e.tradeSeq()+("Buy".equals(e.side())?"-B":"-S"))) {
            throw new IOException("RUN_RECON_TRADE_ID_MISMATCH");
        }
    }
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
    public synchronized void sweep() {
        // Fetch from one BELOW the cursor, not from the cursor: the blotter is strictly-greater-than
        // paginated, so this re-reads the single entry the cursor points at, and the engine still
        // holding it is the proof that we are counting against the epoch we counted against last
        // cycle. Costs one redundant entry per poll and no extra call. (Re-read, not re-classified —
        // see the skip below, or `matched` would climb by one every ten seconds.)
        try {bindScope();} catch(Exception ex) {log.warn("Reconciliation descriptor refused: {}",ex.toString());return;}
        long sinceSeq = cursor > 0 ? cursor - 1 : 0;
        List<BlotterEntry> page;
        try {
            page = fetchBlotterPage(sinceSeq);
            verifyScope();validatePage(page);
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
        if (runRegistry==null && cursor > 0 && page.isEmpty()) {
            resetForNewEpoch();
            return;
        }
        if (onLedger()) {
            try { sweepLedger(page); }
            catch (Exception ex) { log.warn("Reconciliation ledger read failed this cycle: {}", ex.toString()); ledger = null; return; }
        } else for (BlotterEntry entry : page) {
            if (entry.tradeSeq() <= cursor) {
                continue; // the epoch-check entry; already classified on the cycle that advanced past it
            }
            classify(entry);
            cursor = entry.tradeSeq();
        }
        if(runRegistry!=null) runRegistry.saveRecon(projectionScope,cursor,matched.sum(),missingInProjection.sum(),fieldMismatch.sum());
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

    /** Classify the page against the stream; stop (and keep the cursor) at the first side not yet decidable. */
    private void sweepLedger(List<BlotterEntry> page) throws Exception {
        List<BlotterEntry> fresh = page.stream().filter(e -> e.tradeSeq() > cursor).toList();
        if (fresh.isEmpty()) return;
        if (ledger == null) {
            ledger = new LedgerReader(ledgerFeed.natsConnection(), ledgerFeed.streamName(), ledgerFeed.partitionCount(),
                fresh.get(0).execTimeMillis() - LEDGER_START_SLACK.toMillis());
        }
        ledger.readThrough(fresh.get(fresh.size() - 1).tradeSeq(), cursor);
        Map<Integer, Long> committed = snapshotStore.cursors(scopeForLedger());
        for (BlotterEntry e : fresh) {
            LedgerSide side = ledger.pending.get(e.id());
            long partitionCursor = committed.getOrDefault(Math.floorMod(e.accountId(), ledgerFeed.partitionCount()), 0L);
            Verdict v = classifyOnLedger(e, side, ledger.maxTradeSeq, partitionCursor, runRegistry == null ? null : projectionScope);
            if (v == Verdict.WAIT) break;
            switch (v) {
                case MATCHED -> matched.increment();
                case FIELD_MISMATCH -> { fieldMismatch.increment(); log.warn("recon FIELD_MISMATCH (ledger) id={}", e.id()); }
                case MISSING -> { missingInProjection.increment();
                    log.warn("recon MISSING_IN_PROJECTION (ledger) id={} account={} security={}", e.id(), e.accountId(), e.security()); }
                default -> { }
            }
            cursor = e.tradeSeq();
        }
        ledger.prune(cursor);
    }

    private String scopeForLedger() {
        return runRegistry == null ? RunRegistry.UNKNOWN : projectionScope;
    }

    static final java.time.Duration LEDGER_START_SLACK = java.time.Duration.ofSeconds(60);

    enum Verdict { MATCHED, FIELD_MISMATCH, MISSING, WAIT }

    /** One trade side as the capture stream holds it, with its stream sequence. */
    record LedgerSide(String id, int accountId, String security, String side, int quantity, BigDecimal price,
                      String projectionScope, long streamSeq) { }

    /**
     * A side is MATCHED only when the stream holds it with the engine's fields AND the partition's
     * committed snapshot cursor has passed it (it has reached SQL positions); MISSING only when the
     * stream has moved past its tradeSeq without it. Anything else is WAIT: lag is not a verdict.
     */
    static Verdict classifyOnLedger(BlotterEntry e, LedgerSide side, long ledgerMaxTradeSeq, long committedCursor, String scope) {
        if (side == null) return e.tradeSeq() < ledgerMaxTradeSeq ? Verdict.MISSING : Verdict.WAIT;
        if (side.streamSeq() > committedCursor) return Verdict.WAIT;
        boolean same = (scope == null || scope.equals(side.projectionScope()))
            && side.accountId() == e.accountId() && e.security().equals(side.security()) && e.side().equals(side.side())
            && side.quantity() == e.quantity() && side.price() != null && side.price().compareTo(e.price()) == 0;
        return same ? Verdict.MATCHED : Verdict.FIELD_MISMATCH;
    }

    static long tradeSeqOf(String id) {
        int end = id.lastIndexOf('-');
        int start = id.lastIndexOf('-', end - 1);
        return Long.parseLong(id.substring(start + 1, end));
    }

    /**
     * Reads the capture stream forward (all trade partitions) only as far as the recon has reached in the
     * engine's blotter, keeping the sides it has not classified yet. ponytail: in-memory, rebuilt from the
     * first unclassified side's exec time after a restart or a read failure.
     */
    static final class LedgerReader {
        final Map<String, LedgerSide> pending = new java.util.HashMap<>();
        long maxTradeSeq;
        private final io.nats.client.JetStreamSubscription sub;
        private final ObjectMapper json = new ObjectMapper();

        LedgerReader(io.nats.client.Connection nc, String stream, int partitions, long startMillis) throws Exception {
            String filter = partitions <= 1 ? "/trades" : "/trades.*";
            var config = io.nats.client.api.ConsumerConfiguration.builder().filterSubject(filter)
                .ackPolicy(io.nats.client.api.AckPolicy.None)
                .deliverPolicy(io.nats.client.api.DeliverPolicy.ByStartTime)
                .startTime(java.time.ZonedDateTime.ofInstant(Instant.ofEpochMilli(Math.max(0, startMillis)), java.time.ZoneOffset.UTC))
                .inactiveThreshold(java.time.Duration.ofMinutes(5)).build();
            sub = nc.jetStream().subscribe(filter, io.nats.client.PullSubscribeOptions.builder().stream(stream).configuration(config).build());
        }

        void readThrough(long targetTradeSeq, long classifiedThrough) throws Exception {
            while (maxTradeSeq < targetTradeSeq) {
                List<io.nats.client.Message> msgs = sub.fetch(1000, java.time.Duration.ofMillis(500));
                if (msgs.isEmpty()) return;
                for (var m : msgs) {
                    var p = json.readTree(m.getData()).path("payload");
                    String id = p.path("id").asText();
                    long ts = tradeSeqOf(id);
                    maxTradeSeq = Math.max(maxTradeSeq, ts);
                    if (ts <= classifiedThrough) continue;
                    pending.put(id, new LedgerSide(id, p.path("accountId").asInt(), p.path("security").asText(), p.path("side").asText(),
                        p.path("quantity").asInt(), p.path("price").decimalValue(), p.path("projectionScope").asText(RunRegistry.UNKNOWN),   // absent = legacy, as TradeOrder defaults it
                        m.metaData().streamSequence()));
                }
            }
        }

        void prune(long classifiedThrough) {
            pending.values().removeIf(s -> tradeSeqOf(s.id()) <= classifiedThrough);
        }
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
        boolean matches = (runRegistry==null || projectionScope.equals(trade.getProjectionScope()))
            && trade.getAccountId() != null && trade.getAccountId().equals(entry.accountId())
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
        bindScope();
        triggerFullHistoryReindex();

        Set<String> fullHistoryIds = new HashSet<>();
        long sinceSeq = 0;
        while (true) {
            List<BlotterEntry> page = fetchFullHistoryPage(sinceSeq);
            validatePage(page);
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

        verifyScope();
        List<String> localIds = runRegistry==null ? tradeRepository.findAllIds()
            : tradeRepository.findByProjectionScope(projectionScope).stream().map(Trade::getId).toList();
        List<String> orphans = new ArrayList<>();
        for (String id : localIds) {
            if (!fullHistoryIds.contains(id)) {
                orphans.add(id);
                log.warn("recon ORPHAN_IN_PROJECTION id={}", id);
            }
        }

        OrphanSweepResult result = new OrphanSweepResult(
            Instant.now(), localIds.size(), fullHistoryIds.size(), orphans.size(),
            orphans.size() > MAX_REPORTED_ORPHANS ? new ArrayList<>(orphans.subList(0, MAX_REPORTED_ORPHANS)) : orphans);
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
