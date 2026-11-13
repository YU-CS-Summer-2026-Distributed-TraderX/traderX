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
    // FR-DRS-45: the sweep reads the engine's whole blotter against the shared stream and SQL cursors and keeps one
    // cursor per scope, so with N replicas exactly one runs it: the owner of partition 0.
    private PartitionOwnership ownership;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void ownership(PartitionOwnership ownership) { this.ownership = ownership; }
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
            fetched.clear(); fetchedThrough=cursor; gaps.clear(); suspects.clear(); ledger=null;
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
    // Row projection: how long after its fill a side may still be on its way into SQL (see classify).
    private long projectionGraceMs = 120_000L;
    private java.time.Clock clock = java.time.Clock.systemUTC();
    @Value("${recon.projection-grace-ms:120000}")
    void projectionGraceMs(long ms) { this.projectionGraceMs = ms; }
    void clock(java.time.Clock clock) { this.clock = clock; }
    private volatile Instant lastSweepAt;

    // FR-DRS-56: the sweep reads on until it has the engine's head, and holds what it has read but cannot classify yet.
    // The engine's live window is bounded (RECON_BLOTTER_CAPACITY, 500,000 sides: 3.2 s of run 6's peak), and a side
    // waits on its partition's snapshot (every 5 s), so a recon that fetched only what it could classify at once would
    // watch the window roll past the rest. `fetched` is read and not classified; `cursor` stays the classified position.
    private final java.util.ArrayDeque<BlotterEntry> fetched = new java.util.ArrayDeque<>();
    private long fetchedThrough = 0L;
    private volatile int held;   // fetched.size(), for the gauge and /recon/status
    /** tradeSeqs the engine no longer held when the recon came for them: never classified, and said so. */
    private final LongAdder unverified = new LongAdder();
    /** Open (after, before) tradeSeq ranges the engine dropped unread; the stream's sides in them are not kept. */
    private final List<long[]> gaps = new java.util.concurrent.CopyOnWriteArrayList<>();   // read by the stream readers
    private static final int MAX_GAPS = 64;
    /**
     * FR-DRS-61: sides absent from the stream when their partition's head passed them, by tradeSeq. The ledger rule
     * read that as MISSING at once, which holds only while a partition's stream is in tradeSeq order. A bridge
     * catch-up breaks that order: at GKE run 2 (2026-11-12, nats-0's writes throttled) the leader's lanes 0 and 5
     * reconnected, and in the next stage a new leader republished them, both while the server was still storing what
     * the earlier connection had sent. The recon reported 23,936 and 11,456 sides MISSING; both stores held every one.
     * A suspect is MATCHED when it arrives, and MISSING only once it is still absent a projection grace later.
     */
    private final java.util.concurrent.ConcurrentSkipListMap<Long, Suspect> suspects = new java.util.concurrent.ConcurrentSkipListMap<>();
    private record Suspect(BlotterEntry entry, long since) { }
    /** Suspects that arrived: sides the stream stored after a later side of their partition. */
    private final LongAdder lateArrivals = new LongAdder();
    private int pageLimit = 10_000;
    private long sweepBudgetMs = 30_000L;
    private int bufferMaxSides = 0;
    private int maxSidesPerSecond = 2_000_000;
    private long nextFetchNanos = 0L;
    private long fetchNanos, streamNanos, verdictNanos;   // this sweep's time in the engine fetch, the stream read, the verdicts
    private int fetchParallel = 4;
    @Value("${recon.fetch.parallel:4}")
    void fetchParallel(int ranges) { this.fetchParallel = Math.max(1, ranges); }
    private boolean behind;         // the last step's page was whole: the engine holds more
    private int lastRangeSize;      // sides in the last range a step kept
    private int enginePage = 0;   // the largest page the engine has served: a member before FR-DRS-56 serves its own size
    private boolean sizingLogged;
    @Value("${recon.page-limit:10000}")
    void pageLimit(int sides) { this.pageLimit = Math.max(1, sides); }
    @Value("${recon.sweep.budget-ms:30000}")
    void sweepBudgetMs(long ms) { this.sweepBudgetMs = Math.max(0, ms); }
    @Value("${recon.buffer.max-sides:0}")
    void bufferMaxSides(int sides) { this.bufferMaxSides = sides; }
    @Value("${recon.read.max-sides-per-second:2000000}")
    void maxSidesPerSecond(int rate) { this.maxSidesPerSecond = rate; }
    /** Held sides cost about 600 B each (the entry, plus its stream side at 283 B, FR-DRS-51): an eighth of the heap. */
    private int bufferMax() {
        return bufferMaxSides > 0 ? bufferMaxSides
            : (int) Math.max(pageLimit, Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 8 / 600));
    }
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
        Gauge.builder("traderx_recon_unverified_total", unverified, LongAdder::sum).register(meterRegistry);
        Gauge.builder("traderx_recon_held", this, s -> s.held).register(meterRegistry);
        Gauge.builder("traderx_recon_late_total", lateArrivals, LongAdder::sum).register(meterRegistry);
        Gauge.builder("traderx_recon_suspects", suspects, Map::size).register(meterRegistry);
        Gauge.builder("traderx_recon_orphan_total", this,
            s -> s.lastOrphanSweep == null ? 0 : s.lastOrphanSweep.orphanCount()).register(meterRegistry);
    }

    /**
     * FR-DRS-56: one sweep reads pages until it has the engine's head (a short page), or its time budget is spent. It
     * read ONE page (RECON_PAGE_SIZE, 1,000) every interval whatever was traded, so at run 6's 158K sides/s it
     * classified 58,022 of ~125M sides and the engine's window dropped the rest unread, with nothing counting them.
     */
    @Scheduled(fixedDelayString = "${recon.poll.interval-ms:1000}")
    public synchronized void sweep() {
        if (ownership != null && !ownership.owns(0)) return;
        try {bindScope();} catch(Exception ex) {log.warn("Reconciliation descriptor refused: {}",ex.toString());return;}
        if (!sizingLogged) {
            sizingLogged = true;
            log.info("recon sizing: {} sides a page, {} pages at once when behind, at most {} sides/s read from the engine, {} sides held unclassified"
                + " (about {} MB), {} ms a sweep", pageLimit, fetchParallel, maxSidesPerSecond, bufferMax(), bufferMax() * 600L >> 20, sweepBudgetMs);
        }
        long deadline = System.nanoTime() + sweepBudgetMs * 1_000_000L;
        long from = cursor, pages = 1, start = System.nanoTime();
        fetchNanos = streamNanos = verdictNanos = 0;
        long classified = matched.sum() + missingInProjection.sum() + fieldMismatch.sum();
        while (step() && System.nanoTime() < deadline) { pages++; }
        if (cursor > from) {
            log.info("Reconciliation sweep classified {} sides in {} page(s) and {} ms ({} ms engine fetch with {} ms stream read beside it, {} ms verdicts);"
                + " cursor now {}, {} held, {} unverified",
                matched.sum() + missingInProjection.sum() + fieldMismatch.sum() - classified, pages, (System.nanoTime() - start) / 1_000_000,
                fetchNanos / 1_000_000, streamNanos / 1_000_000, verdictNanos / 1_000_000, cursor, fetched.size(), unverified.sum());
        }
    }

    /** One page fetched and everything decidable classified. True while the engine or the held sides have more now. */
    private boolean step() {
        boolean fullPage = false, bufferFull = fetched.size() >= bufferMax();
        // The stream is read while the engine answers, not after: up to where a full page would end. A side the read
        // did not reach is WAIT, as ever, and the next step's read goes on from there.
        // FR-DRS-59: behind the engine's head, several pages are asked at once (below); at the head, one.
        int ranges = behind ? Math.max(1, fetchParallel) : 1;
        // A range is as long as the pages this engine serves (a member before FR-DRS-56 serves its own size).
        int span = Math.max(1, Math.min(pageLimit, enginePage));
        long readTo = bufferFull ? fetchedThrough : fetchedThrough + (ranges == 1 ? pageLimit : (long) span * ranges);
        if (bufferFull) {
            if (!readStream(readTo)) return false;
        } else {
            // Fetch from one BELOW the last side read, not from it: the blotter is strictly-greater-than
            // paginated, so this re-reads the single entry we stand on, and the engine still
            // holding it is the proof that we are counting against the epoch we counted against last
            // cycle. Costs one redundant entry per fetch and no extra call. (Re-read, not re-classified.)
            long sinceSeq = fetchedThrough > 0 ? fetchedThrough - 1 : 0;
            List<BlotterEntry> page;
            long t0;
            try {
                pace();
                t0 = System.nanoTime();
                // The engine numbers sides densely, so range i is the page after range i-1's: asked together, parsed
                // on their own threads, joined in order. One request's parse (about 1.7 us a side) was the reader's limit.
                List<java.util.concurrent.CompletableFuture<List<BlotterEntry>>> fetching = new ArrayList<>(ranges);
                for (int i = 0; i < ranges; i++) {
                    final long since = sinceSeq + (long) i * span;
                    final boolean first = i == 0;
                    final int limit = ranges == 1 ? pageLimit : span;
                    fetching.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try { List<BlotterEntry> p = fetchBlotterPage(since, limit); if (first) verifyScope(); validatePage(p); return p; }
                        catch (Exception ex) { throw new java.util.concurrent.CompletionException(ex); }
                    }));
                }
                boolean read = readStream(readTo);
                Throwable failed = null;   // every range joined: nothing of this step outlives it
                List<List<BlotterEntry>> pages = new ArrayList<>(ranges);
                for (var f : fetching) {
                    try { pages.add(f.join()); } catch (java.util.concurrent.CompletionException ex) { failed = ex.getCause(); }
                }
                if (failed != null) throw failed instanceof Exception e ? e : new IOException(failed);
                page = joinRanges(pages, span);
                fetchNanos += System.nanoTime() - t0;
                if (!read) return false;
            } catch (Exception ex) {
                // UNREACHABLE IS NOT ROLLED. "I cannot see the engine" and "the engine renumbered" are
                // different verdicts, and treating the first as the second lets one network blip erase a
                // real miss count. Nothing is reset here. Nor is it silently taken as "unchanged": the
                // cycle does not touch lastSweepAt, so a reader of /recon/status sees it stop advancing
                // and knows the counters are a stale reading rather than a current one.
                log.warn("Reconciliation sweep skipped this cycle (order-matcher unreachable): {}", ex.toString());
                return false;
            }
            // The engine holds nothing at or above where we stand, so its trade counter has gone BACKWARDS.
            // That is the epoch roll -- the same condition scripts/yu15/run-proofs.sh already trusts when
            // it compares member 0's tradeCounter against the highest trade id in SQL.
            if (runRegistry==null && fetchedThrough > 0 && page.isEmpty()) {
                resetForNewEpoch();
                return false;
            }
            // A page as large as the engine serves means it holds more; a shorter one is its head.
            fullPage = page.size() > 1 && lastRangeSize >= enginePage;
            enginePage = Math.max(enginePage, lastRangeSize);
            behind = fullPage;
            // The next fetch is due one page's worth of the cap after THIS one started, not after it and its verdicts ended.
            if (maxSidesPerSecond > 0) { nextFetchNanos = Math.max(nextFetchNanos, t0) + page.size() * 1_000_000_000L / maxSidesPerSecond; }
            accept(page);
        }
        long before = cursor, t1 = System.nanoTime();
        if (onLedger()) {
            classifyHeld();
        } else for (BlotterEntry entry; (entry = fetched.peekFirst()) != null; fetched.pollFirst()) {
            if (!classify(entry)) break; // held: the cursor stays below the oldest side SQL may still insert
            cursor = entry.tradeSeq();
        }
        verdictNanos += System.nanoTime() - t1;
        // Past a gap: drop its record, and any of its sides the stream reader took before the gap was known.
        if (gaps.removeIf(g -> g[1] <= cursor) && ledger != null) { ledger.prune(cursor, suspects::containsKey); }
        if(runRegistry!=null) runRegistry.saveRecon(projectionScope,cursor,matched.sum(),missingInProjection.sum(),fieldMismatch.sum());
        lastSweepAt = Instant.now();
        held = fetched.size();
        // More now: the engine had a full page; or the buffer was full and classifying made room.
        return fullPage || (bufferFull && cursor > before);
    }

    /**
     * The ranges of one step as one page, in order. A range is kept only if the one before it came back whole
     * ({@code span} sides) and it starts at the next tradeSeq: a short range is the engine's head, and a
     * range that overlaps or skips (a member that serves smaller pages, or a window that rolled past the request) is
     * dropped and asked again next step. So the joined page has no hole the engine did not have.
     */
    private List<BlotterEntry> joinRanges(List<List<BlotterEntry>> pages, int span) {
        List<BlotterEntry> page = pages.get(0);
        lastRangeSize = page.size();
        for (int i = 1; i < pages.size(); i++) {
            List<BlotterEntry> next = pages.get(i);
            if (lastRangeSize != span || next.isEmpty()
                    || next.get(0).tradeSeq() != page.get(page.size() - 1).tradeSeq() + 1) break;
            if (page == pages.get(0)) { page = new ArrayList<>(page); }
            page.addAll(next);
            lastRangeSize = next.size();
        }
        return page;
    }

    /**
     * Hold the page's new sides. If the entry we stood on is gone and the engine's oldest is further on, the window
     * rolled past sides nobody read: they are counted {@code unverified} and logged, never passed over in silence.
     * (Counted in tradeSeqs. The engine numbers every booked side, so this is the sides, or an upper bound of them.)
     */
    private void accept(List<BlotterEntry> page) {
        long lost = 0, from = fetchedThrough;
        for (BlotterEntry e : page) {
            if (e.tradeSeq() <= fetchedThrough) continue;   // the entry we stood on
            // Anywhere in the page, not only at its start: the window evicts while the member walks it.
            if (e.tradeSeq() > fetchedThrough + 1) {
                lost += e.tradeSeq() - fetchedThrough - 1;
                gaps.add(new long[] {fetchedThrough, e.tradeSeq()});
            }
            fetched.addLast(e);
            fetchedThrough = e.tradeSeq();
        }
        if (lost > 0) {
            // Many holes means the recon is reading along the window's tail and losing to it. Stop tracking each hole:
            // write off what is held (counted, like the holes) and read on from here.
            if (gaps.size() > MAX_GAPS) {
                lost += fetched.size();
                fetched.clear(); gaps.clear(); cursor = fetchedThrough;
                if (ledger != null) { ledger.prune(cursor, suspects::containsKey); }
            }
            unverified.add(lost);
            log.warn("recon UNVERIFIED the engine no longer held {} sides between tradeSeq {} and {} when the recon came for"
                + " them: its live window (RECON_BLOTTER_CAPACITY) rolled past. They were not classified; {} in all. The"
                + " full-history orphan sweep covers them.", lost, from, fetchedThrough, unverified.sum());
        }
    }

    /** Bound what the recon asks of the engine: at most {@code recon.read.max-sides-per-second}, by the last page's size. */
    private void pace() throws InterruptedException {
        long wait = nextFetchNanos - System.nanoTime();
        // parkNanos, and nothing under a millisecond: Thread.sleep overslept by tens of milliseconds a page (FR-DRS-59:
        // capped at 1M sides/s the reader ran at 621K, uncapped at 943K).
        if (maxSidesPerSecond > 0 && wait > 1_000_000L) { java.util.concurrent.locks.LockSupport.parkNanos(wait); }
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
        fetched.clear(); fetchedThrough = 0L; gaps.clear(); suspects.clear(); ledger = null;
        // LongAdder.reset() is only safe with no concurrent updates, and that holds here: sweep() is
        // the sole writer of all three (classify() is called from nowhere else) and Spring runs the
        // scheduled sweep on one thread, so this is on the writer thread with no sweep in flight.
        matched.reset();
        missingInProjection.reset();
        fieldMismatch.reset();
    }

    /** Read the capture stream on, up to {@code target}. False (and the reader dropped) when the read failed. */
    private boolean readStream(long target) {
        if (!onLedger() || ledger == null) return true;   // no reader yet: the first held side opens one
        long t0 = System.nanoTime(), classifiedThrough = cursor;
        try {
            ledger.readThrough(target, ts -> ts > classifiedThrough && !dropped(ts) || suspects.containsKey(ts), ledgerSubjects());
            return true;
        } catch (Exception ex) {
            log.warn("Reconciliation ledger read failed this cycle: {}", ex.toString());
            ledger = null;
            return false;
        } finally {
            streamNanos += System.nanoTime() - t0;
        }
    }

    private String[] ledgerSubjects() {
        int partitions = ledgerFeed.partitionCount();
        String[] subjects = new String[partitions];
        for (int p = 0; p < partitions; p++) { subjects[p] = finos.traderx.tradeprocessor.JetStreamFeed.subject("/trades", p, partitions); }
        return subjects;
    }

    /** Classify the held sides against the stream; stop (and keep the cursor) at the first side not yet decidable. */
    private void classifyHeld() {
        if (fetched.isEmpty() && suspects.isEmpty()) return;
        if (ledger == null) {
            int n = ledgerFeed.partitionCount();
            String[] streams = new String[n];
            for (int p = 0; p < n; p++) { streams[p] = ledgerFeed.streamName(p); }
            try {
                // From the oldest side still undecided: a suspect is older than anything held.
                BlotterEntry oldest = suspects.isEmpty() ? fetched.peekFirst() : suspects.firstEntry().getValue().entry();
                ledger = new LedgerReader(ledgerFeed.natsConnection(), streams, n,
                    oldest.execTimeMillis() - LEDGER_START_SLACK.toMillis());
            } catch (Exception ex) { log.warn("Reconciliation ledger read failed this cycle: {}", ex.toString()); return; }
            if (!readStream(fetchedThrough)) return;
        }
        String[] subjects = ledgerSubjects();
        int partitions = subjects.length;
        Map<Integer, Long> committed = snapshotStore.cursors(scopeForLedger());
        // Per partition, once a pass: its committed snapshot cursor, and the head of ITS subject. The bridges publish
        // one lane per partition, so the stream is in tradeSeq order within a partition subject but interleaved across them.
        long[] partitionCursor = new long[partitions], head = new long[partitions];
        for (int p = 0; p < partitions; p++) { partitionCursor[p] = committed.getOrDefault(p, 0L); head[p] = ledger.headOf(subjects[p]); }
        String scope = runRegistry == null ? null : projectionScope;
        for (BlotterEntry e; (e = fetched.peekFirst()) != null; fetched.pollFirst()) {
            int partition = Math.floorMod(e.accountId(), partitions);
            Verdict v = classifyOnLedger(e, ledger.pending.get(e.id()), head[partition], partitionCursor[partition], scope);
            if (v == Verdict.WAIT) break;
            switch (v) {
                case MATCHED -> matched.increment();
                case FIELD_MISMATCH -> { fieldMismatch.increment(); log.warn("recon FIELD_MISMATCH (ledger) id={}", e.id()); }
                case MISSING -> suspect(e);   // FR-DRS-61: not a verdict yet
                default -> { }
            }
            cursor = e.tradeSeq();
            ledger.pending.remove(e.id());
        }
        recheckSuspects(head, partitionCursor, scope);
    }

    /** FR-DRS-61: hold a side its partition's stream passed without it. Bounded: past the bound the oldest is decided. */
    private void suspect(BlotterEntry e) {
        suspects.put(e.tradeSeq(), new Suspect(e, clock.millis()));
        while (suspects.size() > Math.max(pageLimit, bufferMax() / 4)) {
            missing(suspects.pollFirstEntry().getValue().entry(), "the recon holds no more suspects");
        }
    }

    /** A suspect that arrived is classified like any side; one still absent a projection grace later is MISSING. */
    private void recheckSuspects(long[] head, long[] partitionCursor, String scope) {
        long late = 0, lo = Long.MAX_VALUE, hi = 0, now = clock.millis();
        for (var it = suspects.values().iterator(); it.hasNext(); ) {
            Suspect s = it.next();
            BlotterEntry e = s.entry();
            int partition = Math.floorMod(e.accountId(), head.length);
            LedgerSide side = ledger.pending.get(e.id());
            if (side == null) {
                if (now - s.since() < projectionGraceMs) continue;
                missing(e, "still absent " + (now - s.since()) + " ms after its partition's stream passed it");
            } else {
                Verdict v = classifyOnLedger(e, side, head[partition], partitionCursor[partition], scope);
                if (v == Verdict.WAIT) continue;   // arrived; its partition's snapshot has not passed it yet
                if (v == Verdict.MATCHED) { matched.increment(); }
                else { fieldMismatch.increment(); log.warn("recon FIELD_MISMATCH (ledger) id={}", e.id()); }
                late++; lo = Math.min(lo, e.tradeSeq()); hi = Math.max(hi, e.tradeSeq());
                ledger.pending.remove(e.id());
            }
            it.remove();
        }
        if (late > 0) {
            lateArrivals.add(late);
            // The bridge's own rule is that a partition's stream is in apply order, because the projection books in
            // that order (average cost is order-dependent). A late side is that rule broken, so it is said.
            log.warn("recon LATE {} sides between tradeSeq {} and {} reached the stream after a later side of their"
                + " partition (stored out of tradeSeq order, as after a bridge catch-up); classified now, {} in all", late, lo, hi, lateArrivals.sum());
        }
    }

    private void missing(BlotterEntry e, String why) {
        missingInProjection.increment();
        log.warn("recon MISSING_IN_PROJECTION (ledger) id={} account={} security={}: {}", e.id(), e.accountId(), e.security(), why);
    }

    long lateArrivals() { return lateArrivals.sum(); }
    int suspects() { return suspects.size(); }

    private boolean dropped(long tradeSeq) {
        for (long[] g : gaps) { if (tradeSeq > g[0] && tradeSeq < g[1]) return true; }
        return false;
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
     * committed snapshot cursor has passed it (it has reached SQL positions); MISSING only when ITS
     * PARTITION's subject has moved past its tradeSeq without it. Anything else is WAIT: lag is not a
     * verdict. (A global head is wrong: partitions publish on separate lanes and interleave.)
     */
    static Verdict classifyOnLedger(BlotterEntry e, LedgerSide side, long partitionHeadTradeSeq, long committedCursor, String scope) {
        if (side == null) return e.tradeSeq() < partitionHeadTradeSeq ? Verdict.MISSING : Verdict.WAIT;
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

    /** Highest tradeSeq read so far per partition subject: the stream is ordered within a subject only. */
    static final class PartitionHeads {
        private final Map<String, Long> head = new java.util.concurrent.ConcurrentHashMap<>();   // FR-DRS-56: one reader a stream
        void observe(String subject, long tradeSeq) { head.merge(subject, tradeSeq, Math::max); }
        long headOf(String subject) { return head.getOrDefault(subject, 0L); }
        /**
         * FR-DRS-51: every subject of {@code subjects} this reader has SEEN is at or past {@code target}, and one has been
         * seen. A subject with no trades (an idle partition) is never seen and never holds the read back.
         */
        boolean passed(String[] subjects, long target) {
            boolean seen = false;
            for (String s : subjects) {
                Long h = head.get(s);
                if (h == null) continue;
                if (h < target) return false;
                seen = true;
            }
            return seen;
        }
    }

    /**
     * Reads the capture stream forward (all trade partitions) only as far as the recon has reached in the
     * engine's blotter, keeping the sides it has not classified yet. ponytail: in-memory, rebuilt from the
     * first unclassified side's exec time after a restart or a read failure.
     */
    static final class LedgerReader {
        final Map<String, LedgerSide> pending = new java.util.concurrent.ConcurrentHashMap<>();
        final PartitionHeads heads = new PartitionHeads();
        long headOf(String subject) { return heads.headOf(subject); }
        private final List<io.nats.client.JetStreamSubscription> subs = new java.util.ArrayList<>();
        private final List<String> filtersBySub = new java.util.ArrayList<>();   // each subscription's filter subject
        private final ObjectMapper json = new ObjectMapper();

        /** One subscription per distinct stream: the one capture stream, or one per partition (FR-DRS-32). */
        LedgerReader(io.nats.client.Connection nc, String[] streams, int partitions, long startMillis) throws Exception {
            java.util.Map<String, String> filters = new java.util.LinkedHashMap<>();
            for (int p = 0; p < streams.length; p++) {
                boolean shared = streams.length < 2 || streams[0].equals(streams[streams.length - 1]);
                filters.putIfAbsent(streams[p], partitions <= 1 ? "/trades" : shared ? "/trades.*" : "/trades." + p);
            }
            for (var f : filters.entrySet()) {
                var config = io.nats.client.api.ConsumerConfiguration.builder().filterSubject(f.getValue())
                    .ackPolicy(io.nats.client.api.AckPolicy.None)
                    .deliverPolicy(io.nats.client.api.DeliverPolicy.ByStartTime)
                    .startTime(java.time.ZonedDateTime.ofInstant(Instant.ofEpochMilli(Math.max(0, startMillis)), java.time.ZoneOffset.UTC))
                    .inactiveThreshold(java.time.Duration.ofMinutes(5)).build();
                filtersBySub.add(f.getValue());
                subs.add(nc.jetStream().subscribe(f.getValue(),
                    io.nats.client.PullSubscribeOptions.builder().stream(f.getKey()).configuration(config).build()));
            }
        }

        /**
         * Read each subscription until every partition subject it carries that has traded has passed the target, or it has
         * nothing more. FR-DRS-51: this read until EVERY partition's head passed the target, and an idle partition's never
         * does, so a sweep drained every stream to its head into {@link #pending} (run 4: all of stage 1's sides, held on
         * trade-processor-0 until it ran out of heap). Stopping early decides nothing: a side not read yet is WAIT until
         * its own partition's head passes it, and the next sweep reads on from here.
         */
        void readThrough(long targetTradeSeq, long classifiedThrough, String[] subjects) throws Exception {
            readThrough(targetTradeSeq, ts -> ts > classifiedThrough, subjects);
        }

        /**
         * FR-DRS-56: keeps only the sides {@code wanted}, and asks each stream without waiting. A waiting fetch sat out
         * its whole timeout on every stream with nothing new (a partition that never trades: 50 ms a page).
         */
        void readThrough(long targetTradeSeq, java.util.function.LongPredicate wanted, String[] subjects) throws Exception {
            // One stream's read is its own partition's sides, so the streams are read side by side: one thread parsed
            // about 370K sides/s of this JSON, under the 2x target once the fetch and the verdicts share it.
            // ponytail: the common pool, sized to the pod's cores; an own pool if these pulls ever starve another user.
            try {
                java.util.stream.IntStream.range(0, subs.size()).parallel().forEach(i -> {
                    try { readOne(i, targetTradeSeq, wanted, subjects); }
                    catch (Exception ex) { throw new java.util.concurrent.CompletionException(ex); }
                });
            } catch (java.util.concurrent.CompletionException ex) {
                throw ex.getCause() instanceof Exception cause ? cause : ex;
            }
        }

        private void readOne(int i, long targetTradeSeq, java.util.function.LongPredicate wanted, String[] subjects) throws Exception {
            var sub = subs.get(i);
            String[] carried = carried(filtersBySub.get(i), subjects);
            while (!heads.passed(carried, targetTradeSeq)) {
                List<io.nats.client.Message> msgs = fetchNoWait(sub);
                if (msgs.isEmpty()) return;
                for (var m : msgs) {
                  for (byte[] record : finos.traderx.messaging.nats.NatsJSONSubscriber.records(m.getData())) {   // FR-DRS-41
                    var p = json.readTree(record).path("payload");
                    String id = p.path("id").asText();
                    long ts = tradeSeqOf(id);
                    heads.observe(m.getSubject(), ts);
                    if (!wanted.test(ts)) continue;
                    pending.put(id, new LedgerSide(id, p.path("accountId").asInt(), p.path("security").asText(), p.path("side").asText(),
                        p.path("quantity").asInt(), p.path("price").decimalValue(), p.path("projectionScope").asText(RunRegistry.UNKNOWN),   // absent = legacy, as TradeOrder defaults it
                        m.metaData().streamSequence()));
                  }
                }
            }
        }

        /**
         * What the stream holds now, up to FETCH messages, without waiting for more. The bridges put up to 64 sides in
         * a message (FR-DRS-41), so one fetch past the page is at most 16K sides a stream.
         */
        static final int FETCH = 250;
        private static List<io.nats.client.Message> fetchNoWait(io.nats.client.JetStreamSubscription sub) throws InterruptedException {
            sub.pullNoWait(FETCH);
            List<io.nats.client.Message> out = new java.util.ArrayList<>(FETCH);
            // The server ends a no-wait pull with a status, which nextMessage returns as null; the timeout is for a dead link.
            for (io.nats.client.Message m; out.size() < FETCH && (m = sub.nextMessage(java.time.Duration.ofMillis(500))) != null; ) { out.add(m); }
            return out;
        }

        /** The partition subjects a subscription's filter carries: {@code /trades.*} all of them, otherwise its own. */
        static String[] carried(String filter, String[] subjects) {
            return filter.endsWith(".*") ? subjects : new String[] {filter};
        }

        void prune(long classifiedThrough) { prune(classifiedThrough, ts -> false); }

        /** FR-DRS-61: and keep the sides {@code kept} (the suspects) whatever their tradeSeq. */
        void prune(long classifiedThrough, java.util.function.LongPredicate kept) {
            pending.values().removeIf(s -> { long ts = tradeSeqOf(s.id()); return ts <= classifiedThrough && !kept.test(ts); });
        }
    }

    /**
     * The engine fills before trade-processor inserts the row, so a side absent from SQL is only
     * MISSING once it is older than the projection grace; younger, it is held (returns false) and the
     * cursor stops below it, so the next sweep looks again. Age is the fill's engine exec time, not
     * when a sweep first saw it: a run of genuine misses resolves in one sweep, not one grace each.
     */
    private boolean classify(BlotterEntry entry) {
        Optional<Trade> local = tradeRepository.findById(entry.id());
        if (local.isEmpty()) {
            if (clock.millis() - entry.execTimeMillis() < projectionGraceMs) return false;
            missingInProjection.increment();
            log.warn("recon MISSING_IN_PROJECTION id={} account={} security={}",
                entry.id(), entry.accountId(), entry.security());
            return true;
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
        return true;
    }

    private List<BlotterEntry> fetchBlotterPage(long sinceSeq, int limit) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/recon/trades/blotter?sinceSeq=" + sinceSeq + "&limit=" + limit);
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
        return new StatusSnapshot(cursor, matched.sum(), missingInProjection.sum(), fieldMismatch.sum(), lastSweepAt,
            unverified.sum(), held);
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
                                  Instant lastSweepAt, long unverified, long held) { }

    /** {@code orphanIds} is capped at {@link #MAX_REPORTED_ORPHANS} even when {@code orphanCount} is larger. */
    public record OrphanSweepResult(Instant sweptAt, int localTradeCount, int fullHistoryTradeCount,
                                     int orphanCount, List<String> orphanIds) { }
}
