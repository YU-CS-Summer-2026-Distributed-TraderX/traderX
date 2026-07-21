package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.agrona.BitUtil;
import org.agrona.collections.Int2ObjectHashMap;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * The Business Logic Processor (state 009b): single thread, entirely in memory,
 * event-sourced (FR-09B10..FR-09B16).
 *
 * Invariants (LMAX-BLP.md A2):
 *  - sole writer of the order book — no locks anywhere (009's orderMutationLock is gone);
 *  - no blocking external calls (no REST, no JPA, no NATS) — results are emitted as typed
 *    events into the output ring;
 *  - deterministic: time is event-carried (eventTimeMillis), ids derive from the order
 *    reference carried in the event, iteration is over arrays;
 *  - zero steady-state allocation: pooled RestingOrder entries, primitive structures,
 *    long fixed-point math.
 *
 * The matching policy is a genuine crossing limit-order book (YU13, FR-LOB01..05):
 * two-sided per-security {@link LimitBook}s with price-time priority. A marketable order
 * executes against resting opposite-side orders best-price-first, FIFO within a level —
 * FIFO is arrival order is consensus-log order, so time priority derives from the same
 * deterministic input sequence as every other decision. Executions price at the resting
 * order's limit; the security's last price is an OUTPUT of matching (the last trade
 * price), never a fill trigger. Market orders (no limit price) execute immediately
 * against the book and cancel any unfilled remainder — they never rest.
 */
public final class MatchingEngine implements EventHandler<InputEvent> {
    public static final int SNAPSHOT_ORDER_TUPLE_LENGTH = 15;

    /** Book band width in ticks (power of two); identical on every member (config identity).
     *  1&lt;&lt;17 ticks at the 0.001 grid = a $131.07 band (±$65.5 around the anchor). */
    public static final int DEFAULT_BOOK_LEVELS = 1 << 17;
    /** Book grid: Px units per tick. 1_000 = 0.001 — the exact 3dp granularity every edge
     *  price already carries (Px rounds to 3dp HALF_UP), so every price the edges can
     *  produce sits on the grid; only raw sub-0.001 ingress is off-grid. */
    public static final long DEFAULT_BOOK_TICK_PX = 1_000L;

    private final OutputPublisher out;
    private final HotPathMetrics metrics;
    // Authoritative pre-trade risk state (in-memory-risk-gateway, ADR-018): check + reserve
    // exact aggregate exposure in global sequence order BEFORE an order becomes executable
    // (FR-IMRG12/13). Null = risk disabled (legacy construction paths and parity tests).
    private final BlpRiskState risk;

    // orderRef -> entry, bounded by open + retained-terminal orders. NOT a flat array: a
    // ref-indexed array grows with the highest ref EVER issued (~4 B/slot x cumulative order
    // count, never shrunk) — the unbounded index that decayed throughput and OOM'd the 33M-order
    // soak (2026-07-21 postmortem). Presized in the constructor so it never rehashes after
    // warmup (zero steady-state allocation, NGC-01).
    private final Int2ObjectHashMap<RestingOrder> ordersByRef;
    private final LimitBook[] booksBySecurity;   // per-security two-sided book (lazy, log-driven)
    private final long[] lastPxBySecurity;       // last TRADE price; Px.NONE = no trade yet
    private int bookLevels;
    private long bookTickPx;
    private RestingOrder freeList;               // pre-allocated pool (BLP thread only)
    private final PositionBook positions;        // net positions, single-writer (FR-09B08/B10)
    private long tradeCounter;                    // global trade number, single-writer (deterministic ids)

    // Bounded terminal-order retention (state 009b Tier 2-B / milestone T09B14). Terminal orders stay
    // addressable so cancel/force-fill of a completed order reproduces 009's "return it unchanged"
    // semantics — but only the most recent `terminalCap` of them. Older terminals are evicted from
    // ordersByRef and their RestingOrder recycled to the pool, so steady-state memory is bounded
    // (open book + last `terminalCap` terminals) instead of growing without limit (the prior leak that
    // paced sustained throughput via GC and eventually OOM'd). FIFO of terminal orderRefs in transition
    // order; BLP thread only, allocation-free. An aged-out ref resolves to not-found (404), as one never
    // created — the durable record lives in the journal. terminalCap <= 0 disables eviction (unbounded).
    private final int[] terminalRing;
    private final int terminalCap;
    private int terminalHead;
    private int terminalCount;

    // Single-writer telemetry: only the BLP thread writes, edge threads (REST /health,
    // /metrics) read racily-but-safely. The counters are plain longs published by the
    // once-per-event release-store of blpSeq (the Disruptor Sequence.set idiom); readers
    // acquire-load blpSeq first (readFence), so the hot path pays no per-counter fences.
    // autoFill* names retained for edge/metrics compatibility: attempts = aggressors that
    // found a crossable opposite book, success = aggressors that executed at least one match.
    private long eventsProcessed;
    private long autoFillAttempts;
    private long autoFillSuccess;
    private long lastEventTimeMillis;
    private long ordersNew;
    private long ordersCancel;
    private long ordersForceFill;
    private long priceTicks;
    private long tradesNew;
    private long controlEvents;
    private volatile long blpSeq = -1;
    private volatile long blpThreadId;
    private volatile int pinCpu = -1;   // perf profile: BLP CPU to pin on start (<0 = unpinned)
    private Runnable snapshotTrigger;   // recovery: invoked on the BLP thread at a SNAPSHOT marker

    private static final VarHandle BLP_SEQ;

    static {
        try {
            BLP_SEQ = MethodHandles.lookup().findVarHandle(MatchingEngine.class, "blpSeq", long.class);
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final int DEFAULT_TERMINAL_RETAIN = 262_144;

    public MatchingEngine(OutputPublisher out, HotPathMetrics metrics, int maxSecurities,
                          int fillFullThreshold, int initialPoolSize, int positionCapacity,
                          int terminalRetain) {
        this(out, metrics, maxSecurities, fillFullThreshold, initialPoolSize, positionCapacity,
            terminalRetain, null);
    }

    public MatchingEngine(OutputPublisher out, HotPathMetrics metrics, int maxSecurities,
                          int fillFullThreshold, int initialPoolSize, int positionCapacity,
                          int terminalRetain, BlpRiskState risk) {
        this.out = out;
        this.metrics = metrics;
        this.risk = risk;
        // fillFullThreshold is retained for construction-site compatibility only: YU13's
        // crossing book fills exactly what the opposite side offers — there is no
        // threshold-driven half-fill policy any more.
        this.booksBySecurity = new LimitBook[maxSecurities];
        this.lastPxBySecurity = new long[maxSecurities];
        this.bookLevels = envInt("BOOK_LEVELS", DEFAULT_BOOK_LEVELS);
        this.bookTickPx = envLong("BOOK_TICK_PX", DEFAULT_BOOK_TICK_PX);
        this.positions = new PositionBook(positionCapacity);
        this.terminalCap = Math.max(0, terminalRetain);
        // Steady-state index population = retained terminals + the open-order working set (the
        // pool proxies the latter: past it, takeFromPool already allocates anyway). Table sized
        // at 2x that over a 0.55 load factor => threshold ~2.2x expected entries, so the map
        // never rehashes after warmup (NGC-01). terminalCap 0 = unbounded retention (tests).
        final int steadyOrders = Math.max(16, this.terminalCap + Math.max(initialPoolSize, 1024));
        this.ordersByRef = new Int2ObjectHashMap<>(
            BitUtil.findNextPositivePowerOfTwo(steadyOrders * 2), 0.55f);
        this.terminalRing = this.terminalCap > 0 ? new int[this.terminalCap] : null;
        for (int i = 0; i < initialPoolSize; i++) {
            RestingOrder pooled = new RestingOrder();
            pooled.nextFree = freeList;
            freeList = pooled;
        }
    }

    public MatchingEngine(OutputPublisher out, HotPathMetrics metrics, int maxSecurities,
                          int fillFullThreshold, int initialPoolSize, int positionCapacity) {
        this(out, metrics, maxSecurities, fillFullThreshold, initialPoolSize, positionCapacity,
            DEFAULT_TERMINAL_RETAIN);
    }

    public MatchingEngine(OutputPublisher out, HotPathMetrics metrics, int maxSecurities,
                          int fillFullThreshold, int initialPoolSize) {
        this(out, metrics, maxSecurities, fillFullThreshold, initialPoolSize, Math.max(1024, initialPoolSize));
    }

    private static int envInt(String name, int dflt) {
        final String v = System.getenv(name);
        return v == null || v.isEmpty() ? dflt : Integer.parseInt(v);
    }

    private static long envLong(String name, long dflt) {
        final String v = System.getenv(name);
        return v == null || v.isEmpty() ? dflt : Long.parseLong(v);
    }

    /** BatchEventProcessor start hook: runs on the BLP thread before the first event. */
    @Override
    public void onStart() {
        blpThreadId = Thread.currentThread().threadId();
        CpuAffinity.pinCurrentThread(pinCpu);   // perf profile only; pinCpu < 0 is a no-op
    }

    /** Pin the BLP thread to this CPU on start (perf profile); &lt; 0 = no pinning. Set before the ring starts. */
    public void setPinCpu(int cpu) {
        this.pinCpu = cpu;
    }

    // ----- book geometry (identical on every member — config identity, like the output ring) ----

    /** Test seam: set band width / grid before any book exists. */
    public void setBookGeometry(int levels, long tickPx) {
        requireNoBooks();
        this.bookLevels = levels;
        this.bookTickPx = tickPx;
    }

    /** Snapshot restore: adopt the snapshot's recorded geometry so replicas match exactly. */
    public void adoptBookGeometry(int levels, long tickPx) {
        if (levels == bookLevels && tickPx == bookTickPx) {
            return;
        }
        requireNoBooks();
        this.bookLevels = levels;
        this.bookTickPx = tickPx;
    }

    private void requireNoBooks() {
        for (LimitBook book : booksBySecurity) {
            if (book != null) {
                // Constant message: banned-API gate forbids runtime string concat here.
                throw new IllegalStateException("book geometry cannot change after a book exists");
            }
        }
    }

    public int bookLevels() {
        return bookLevels;
    }

    public long bookTickPx() {
        return bookTickPx;
    }

    @Override
    public void onEvent(InputEvent e, long sequence, boolean endOfBatch) {
        switch (e.type) {
            case InputEvent.TYPE_ORDER_NEW -> { ordersNew++; onNewOrder(e); }
            case InputEvent.TYPE_ORDER_CANCEL -> { ordersCancel++; onCancel(e); }
            case InputEvent.TYPE_FORCE_FILL -> { ordersForceFill++; onForceFill(e); }
            case InputEvent.TYPE_PRICE_TICK -> { priceTicks++; onPriceTick(e); }
            case InputEvent.TYPE_TRADE_NEW -> { tradesNew++; onTradeNew(e); }
            case InputEvent.TYPE_SNAPSHOT -> {
                if (snapshotTrigger != null) {   // null during recovery replay (markers are no-ops then)
                    snapshotTrigger.run();
                }
            }
            // Versioned control events (FR-IMRG11 / ADR-020): applied in the same global sequence
            // as commands and prices, so replay reproduces every original decision.
            case InputEvent.TYPE_ACCOUNT_CONTROL -> { controlEvents++; onAccountControl(e); }
            case InputEvent.TYPE_SECURITY_CONTROL -> { controlEvents++; onSecurityControl(e); }
            case InputEvent.TYPE_POLICY_CONTROL -> { controlEvents++; onPolicyControl(e); }
            case InputEvent.TYPE_RESTRICTION_CONTROL -> { controlEvents++; onRestrictionControl(e); }
            default -> { /* ignore unknown event types */ }
        }
        eventsProcessed++;
        lastEventTimeMillis = e.eventTimeMillis;
        // Release-store: publishes this event's plain counter/time writes to edge readers
        // without the full volatile-store fence on the BLP thread.
        BLP_SEQ.setRelease(this, sequence);
        metrics.recordBlpEventLatency(System.nanoTime() - e.ingressNanos);
    }

    // ----- bootstrap (single-threaded, before the ring goes live) -----------------------

    /** Warm the in-memory book from the read-model at startup (spec: warm-on-start). */
    public void bootstrapOrder(int orderRef, int accountId, int securityId, byte side, int quantity,
                               int remaining, long limitPx, byte status, long lastExecPx,
                               int lastFillQty, long createdAtMillis, long updatedAtMillis) {
        bootstrapOrder(orderRef, accountId, securityId, side, quantity, remaining, limitPx, status,
            (byte) 0, lastExecPx, lastFillQty, createdAtMillis, updatedAtMillis, 0L, 0);
    }

    /** Snapshot-restore variant carrying the order's authoritative risk decision and live
     *  reservation (FR-IMRG21); re-accumulates open-order reservations into the risk aggregates.
     *  Open rows arrive in ascending orderRef order — arrival order — so re-appending them into
     *  their price levels reproduces the exact FIFO time priority of the original book. */
    public void bootstrapOrder(int orderRef, int accountId, int securityId, byte side, int quantity,
                               int remaining, long limitPx, byte status, byte riskReason,
                               long lastExecPx, int lastFillQty, long createdAtMillis,
                               long updatedAtMillis, long reservedNotional, int reservedQty) {
        RestingOrder o = takeFromPool();
        o.orderRef = orderRef;
        o.accountId = accountId;
        o.securityId = securityId;
        o.side = side;
        o.quantity = quantity;
        o.remaining = remaining;
        o.limitPx = limitPx;
        o.status = status;
        o.riskReason = riskReason;
        o.lastExecPx = lastExecPx;
        o.lastFillQty = lastFillQty;
        o.createdAtMillis = createdAtMillis;
        o.updatedAtMillis = updatedAtMillis;
        o.reservedNotional = reservedNotional;
        o.reservedQty = reservedQty;
        if (risk != null && o.isOpen()) {
            risk.reaccumulateReservation(accountId, securityId, side, reservedNotional, reservedQty);
        }
        index(o);
        if (o.isOpen()) {
            final LimitBook book = bookFor(securityId);
            // Constant messages: this class is scanned by the banned-API gate (no runtime
            // string concat anywhere in the constant pool); the offending row is recoverable
            // from the restore position in the member log.
            if (!book.onGrid(limitPx)) {
                throw new IllegalStateException("restore corrupt: off-grid open order limit price");
            }
            final int slot = book.slotFor(limitPx);
            if (slot == LimitBook.NO_LEVEL) {
                // Fail closed: an open order the restored band cannot hold means the band
                // anchor or geometry did not survive restore intact.
                throw new IllegalStateException("restore incomplete: open order outside restored book band");
            }
            book.append(o, slot);
        } else {
            markTerminal(orderRef);   // terminal warm-start rows are evictable too (bounded retention)
        }
    }

    /** Warm the in-memory net positions (quantity + cost basis) from the persisted POSITIONS read-model. */
    public void bootstrapPosition(int accountId, int securityId, int quantity, long avgCostTicks) {
        positions.put(accountId, securityId, quantity, avgCostTicks);
    }

    /** Resume the global trade counter above the persisted max so trade ids never collide across restarts. */
    public void bootstrapTradeCounter(long lastTradeSeq) {
        if (lastTradeSeq > tradeCounter) {
            tradeCounter = lastTradeSeq;
        }
    }

    /** Snapshot restore: re-create a security's book and its band anchor (before order rows). */
    public void bootstrapBook(int securityId, long baseLevel) {
        final LimitBook book = bookFor(securityId);
        if (baseLevel >= 0) {
            book.bootstrapBase(baseLevel);
        }
    }

    // ----- recovery verification (startup only; NOT hot-path) ---------------------------------

    /** Canonical, iteration-order-independent snapshot of the recoverable BLP state, used to verify
     *  that journal replay reconstructs the same state as the DB warm-start (state 009b, step 1). */
    public record RecoveryDigest(int openOrders, int positions, long tradeCounter,
                                 long orderHash, long positionHash, int pricedSecurities) {}

    /**
     * Digest the recoverable state: the open order book (orderRef/status/remaining/limit/account/
     * security), net positions, and the trade counter. {@code lastPxBySecurity} is reported as a
     * count only ({@code pricedSecurities}) and deliberately excluded from the compared hashes — the
     * DB warm-start cannot restore prices, so replay legitimately recovers more than the DB.
     */
    public RecoveryDigest recoveryDigest() {
        long orderHash = 0L;
        int openOrders = 0;
        for (RestingOrder o : ordersByRef.values()) {
            if (o.orderRef == 0 || !o.isOpen()) {
                continue;
            }
            long h = 1125899906842597L;
            h = h * 31 + o.orderRef;
            h = h * 31 + o.status;
            h = h * 31 + o.remaining;
            h = h * 31 + o.limitPx;
            h = h * 31 + o.accountId;
            h = h * 31 + o.securityId;
            orderHash ^= avalanche(h);
            openOrders++;
        }
        long[] pos = positions.recoveryDigest();
        int priced = 0;
        for (int s = 0; s < lastPxBySecurity.length; s++) {
            if (lastPxBySecurity[s] != Px.NONE) {
                priced++;
            }
        }
        return new RecoveryDigest(openOrders, (int) pos[1], tradeCounter, orderHash, pos[0], priced);
    }

    private static long avalanche(long h) {
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    /** Open orders as {orderRef, status, remaining, limitPx, accountId, securityId}, ascending
     *  ref (debug/verify only). */
    public java.util.List<long[]> openOrderTuples() {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (RestingOrder o : ordersByRef.values()) {
            if (o.orderRef == 0 || !o.isOpen()) {
                continue;
            }
            out.add(new long[] { o.orderRef, o.status, o.remaining, o.limitPx, o.accountId, o.securityId });
        }
        out.sort((a, b) -> Long.compare(a[0], b[0]));
        return out;
    }

    /** Net positions as {accountId, securityId, quantity, avgCostTicks} (debug/verify only). */
    public java.util.List<long[]> positionTuples() {
        return positions.tuples();
    }

    // ----- snapshot/recovery (single-threaded: BLP thread, before/at the marker) ---------------

    public void setSnapshotTrigger(Runnable trigger) {
        this.snapshotTrigger = trigger;
    }

    public long tradeCounter() {
        return tradeCounter;
    }

    /** Every order still addressable in the book (open AND terminal), ascending ref, as
     *  {ref, acct, sec, side, qty, rem, limitPx, status, lastExecPx, lastFillQty, createdMs,
     *  updatedMs, riskReason, reservedNotional, reservedQty}. */
    public java.util.List<long[]> allOrderTuples() {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (RestingOrder o : ordersByRef.values()) {
            if (o.orderRef != 0) {
                final long[] tuple = new long[SNAPSHOT_ORDER_TUPLE_LENGTH];
                copySnapshotOrderTuple(o, tuple);
                out.add(tuple);
            }
        }
        out.sort((a, b) -> Long.compare(a[0], b[0]));
        return out;
    }

    /**
     * Every indexed orderRef in ascending order. Cold path only (snapshot serialization): the
     * map iterates in hash order, so callers needing the established ascending-ref snapshot
     * byte order (ADR-046) take this sorted copy instead.
     */
    public int[] snapshotOrderRefsAscending() {
        final int[] refs = new int[ordersByRef.size()];
        int n = 0;
        for (final RestingOrder o : ordersByRef.values()) {
            refs[n++] = o.orderRef;
        }
        java.util.Arrays.sort(refs);
        return refs;
    }

    /**
     * Copy one retained order into the reusable 15-field snapshot tuple. Returns false for an
     * absent/evicted ref. This keeps snapshot serialization O(retained orders) without exposing
     * mutable pooled orders or allocating one tuple per row.
     */
    public boolean copySnapshotOrderTuple(final int orderRef, final long[] target) {
        if (target.length < SNAPSHOT_ORDER_TUPLE_LENGTH) {
            throw new IllegalArgumentException(
                "snapshot order tuple needs " + SNAPSHOT_ORDER_TUPLE_LENGTH + " fields");
        }
        final RestingOrder order = lookup(orderRef);
        if (order == null || order.orderRef == 0) {
            return false;
        }
        copySnapshotOrderTuple(order, target);
        return true;
    }

    private static void copySnapshotOrderTuple(final RestingOrder order, final long[] target) {
        target[0] = order.orderRef;
        target[1] = order.accountId;
        target[2] = order.securityId;
        target[3] = order.side;
        target[4] = order.quantity;
        target[5] = order.remaining;
        target[6] = order.limitPx;
        target[7] = order.status;
        target[8] = order.lastExecPx;
        target[9] = order.lastFillQty;
        target[10] = order.createdAtMillis;
        target[11] = order.updatedAtMillis;
        target[12] = order.riskReason;
        target[13] = order.reservedNotional;
        target[14] = order.reservedQty;
    }

    /**
     * Retained terminal orderRefs oldest→newest — the bounded-retention ring's eviction order
     * (YU12, ADR-046). Snapshot restore must re-mark terminals in exactly this order: eviction
     * picks the oldest retained terminal, so a replica restored in a different order would evict
     * a different set and later answer cancel-of-terminal differently (not-found vs unchanged) —
     * a replicated-state divergence. Empty when eviction is disabled (order is then irrelevant).
     */
    public int[] terminalOrderRefsFifo() {
        if (terminalRing == null) {
            return new int[0];
        }
        int[] out = new int[terminalCount];
        for (int i = 0; i < terminalCount; i++) {
            int index = terminalHead + i;
            if (index >= terminalCap) {
                index -= terminalCap;
            }
            out[i] = terminalRing[index];
        }
        return out;
    }

    /** Known last trade prices as {securityId, ticks} — state the DB read-model cannot hold. */
    public java.util.List<long[]> priceTuples() {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (int s = 0; s < lastPxBySecurity.length; s++) {
            if (lastPxBySecurity[s] != Px.NONE) {
                out.add(new long[] { s, lastPxBySecurity[s] });
            }
        }
        return out;
    }

    /** Book band anchors as {securityId, baseLevel} for every created book (snapshot). */
    public java.util.List<long[]> bookBaseTuples() {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (int s = 0; s < booksBySecurity.length; s++) {
            if (booksBySecurity[s] != null) {
                out.add(new long[] { s, booksBySecurity[s].baseLevel() });
            }
        }
        return out;
    }

    /** Restore a last price on snapshot load (recovery), keyed by securityId. */
    public void bootstrapPrice(int securityId, long ticks) {
        if (securityId >= 0 && securityId < lastPxBySecurity.length) {
            lastPxBySecurity[securityId] = ticks;
        }
    }

    // ----- event handling ----------------------------------------------------------------

    private void onNewOrder(InputEvent e) {
        // Idempotent retry (FR-IMRG14): a clientOrderKey already decided maps to its ONE original
        // decision — re-emit the original order unchanged, never create or reserve a second one.
        if (risk != null && e.clientOrderKey() != 0L) {
            int originalRef = risk.existingOrderRef(e.clientOrderKey());
            if (originalRef >= 0) {
                RestingOrder original = lookup(originalRef);
                if (original != null) {
                    out.emitOrderUpdate(original, e.seq, 0, false,
                        lastPxBySecurity[original.securityId], e.ingressNanos);
                    return;
                }
            }
        }
        RestingOrder o = takeFromPool();
        o.orderRef = e.orderRef;
        o.accountId = e.accountId;
        o.securityId = e.securityId;
        o.side = e.side;
        o.quantity = e.qty;
        o.remaining = e.qty;
        o.limitPx = e.limitPx;
        o.status = RestingOrder.STATUS_NEW;
        o.riskReason = (byte) RiskReason.ACCEPTED.ordinal();
        o.lastExecPx = Px.NONE;
        o.lastFillQty = 0;
        o.createdAtMillis = e.eventTimeMillis;
        o.updatedAtMillis = e.eventTimeMillis;

        // A limit price must sit on the book grid and inside the band BEFORE any reservation is
        // taken, so a price rejection never has exposure to unwind. Market orders (no limit)
        // skip both checks — they never rest.
        final boolean market = e.limitPx <= Px.NONE;
        final LimitBook book = bookFor(e.securityId);
        int restSlot = LimitBook.NO_LEVEL;
        if (!market) {
            if (!book.onGrid(e.limitPx)) {
                reject(o, e, RiskReason.INVALID);
                return;
            }
            restSlot = book.slotFor(e.limitPx);   // anchors the band on the security's first limit
            if (restSlot == LimitBook.NO_LEVEL) {
                reject(o, e, RiskReason.PRICE_COLLAR);
                return;
            }
        }

        // Authoritative pre-trade decision + reservation, in sequence order, BEFORE the order can
        // enter the executable book (ADR-018 / FR-IMRG12/13). A rejection stays journaled and
        // addressable for audit but never rests, matches, or reserves (FR-IMRG23). Market orders
        // validate and reserve at the last trade price, falling back to the opposite best — an
        // unpriced empty market fails closed as PRICE_MISSING.
        if (risk != null) {
            long decideStart = System.nanoTime();
            final long validationPx = market ? marketValidationPx(book, e.securityId, e.side) : e.limitPx;
            RiskReason decision = risk.decideAndReserve(e.clientOrderKey(), 0L, e.orderRef,
                e.accountId, e.securityId, e.side, positions.get(e.accountId, e.securityId),
                e.qty, validationPx, e.eventTimeMillis, o);
            metrics.recordRiskDecisionLatency(System.nanoTime() - decideStart);
            if (decision != RiskReason.ACCEPTED) {
                o.riskReason = (byte) decision.ordinal();
                reject(o, e, null);
                return;
            }
        }
        index(o);
        // Ack first: the REST create response is the NEW order, exactly as in 009. The first
        // non-resting order-lifecycle ack after an offer is the gateway's correlation contract.
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CREATE, true,
            lastPxBySecurity[e.securityId], e.ingressNanos);

        cross(o, book, e);

        if (o.remaining > 0) {
            if (!market) {
                book.append(o, restSlot);   // rest at its level tail: arrival order IS time priority
            } else {
                cancelRemainder(o, e);      // market orders never rest (FR-LOB04)
            }
        }
    }

    /** Terminal rejection: journaled and addressable for audit, never rests or reserves. */
    private void reject(RestingOrder o, InputEvent e, RiskReason reason) {
        if (reason != null) {
            o.riskReason = (byte) reason.ordinal();
        }
        o.status = RestingOrder.STATUS_REJECTED;
        o.remaining = 0;
        index(o);
        markTerminal(o.orderRef);
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_REJECT, true,
            lastPxBySecurity[o.securityId], e.ingressNanos);
    }

    /** Reference price for validating/reserving a market order: last trade, else opposite best. */
    private long marketValidationPx(LimitBook book, int securityId, byte side) {
        final long last = lastPxBySecurity[securityId];
        if (last != Px.NONE) {
            return last;
        }
        final int slot = side == InputEvent.SIDE_BUY ? book.bestAskSlot() : book.bestBidSlot();
        return slot == LimitBook.NO_LEVEL ? Px.NONE : book.priceAt(slot);
    }

    // ----- matching (YU13: genuine crossing, price-time priority) -------------------------

    /**
     * Execute the aggressor against resting opposite-side orders: best price first, FIFO
     * within a level, at the RESTING order's price (grid alignment makes the level price the
     * resting limit exactly, so an execution can never violate either side's limit). Each
     * match step fills the resting order and the aggressor symmetrically — order update,
     * TradeBooked, and PositionUpdated per side — and advances the last trade price.
     */
    private void cross(RestingOrder a, LimitBook book, InputEvent e) {
        final boolean buy = a.side == InputEvent.SIDE_BUY;
        final byte restingSide = buy ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY;
        final boolean market = a.limitPx <= Px.NONE;
        int opp = buy ? book.bestAskSlot() : book.bestBidSlot();
        if (opp == LimitBook.NO_LEVEL) {
            return;
        }
        long levelPx = book.priceAt(opp);
        if (!market && (buy ? levelPx > a.limitPx : levelPx < a.limitPx)) {
            return;
        }
        autoFillAttempts++;
        final long matchStart = System.nanoTime();
        while (a.remaining > 0 && opp != LimitBook.NO_LEVEL) {
            levelPx = book.priceAt(opp);
            if (!market && (buy ? levelPx > a.limitPx : levelPx < a.limitPx)) {
                break;
            }
            final RestingOrder r = book.headAt(restingSide, opp);
            final int fillQty = Math.min(a.remaining, r.remaining);
            lastPxBySecurity[a.securityId] = levelPx;   // the last trade price is a matching OUTPUT
            applyMatchFill(r, fillQty, levelPx, e, OutputEvent.FLAG_RESTING_UPDATE, book);
            applyMatchFill(a, fillQty, levelPx, e, 0, book);
            opp = buy ? book.bestAskSlot() : book.bestBidSlot();
        }
        autoFillSuccess++;
        metrics.recordMatchLatency(System.nanoTime() - matchStart);
    }

    /**
     * Apply one side of a match: shrink the level aggregate (resting side), transition
     * status, convert reserved exposure exactly once (FR-IMRG16), book the trade at the
     * execution price, update the net position, and emit the paired order-update +
     * TradeBooked + PositionUpdated claim. {@code extraFlags} carries FLAG_RESTING_UPDATE
     * for the resting side so gateway ack correlation can tell counterparty updates from
     * direct responses (FR-LOB07).
     */
    private void applyMatchFill(RestingOrder o, int fillQty, long execPx, InputEvent e,
                                int extraFlags, LimitBook book) {
        final boolean resting = o.isResting();
        if (resting) {
            book.reduce(o, fillQty);
        }
        o.remaining -= fillQty;
        o.lastExecPx = execPx;
        o.lastFillQty = fillQty;
        o.updatedAtMillis = e.eventTimeMillis;

        int flags = extraFlags;
        if (o.remaining == 0) {
            o.status = RestingOrder.STATUS_FILLED;
            flags |= OutputEvent.FLAG_FILL;
            if (resting) {
                book.remove(o);   // remaining is 0: the aggregate was already reduced
            }
            markTerminal(o.orderRef);
        } else {
            o.status = RestingOrder.STATUS_PARTIALLY_FILLED;
            flags |= OutputEvent.FLAG_PARTIAL_FILL;
        }

        // Fill converts reserved exposure into executed exposure exactly once (FR-IMRG16).
        if (risk != null) {
            risk.consume(o.accountId, o.securityId, o.side, o, fillQty, execPx);
        }

        // Booking + position-keeping are fused into the BLP (FR-09B08/B10): the fill books the
        // trade at its execution price and updates the account's net position + weighted cost
        // basis in memory (single writer, no DB hop), then the order update, TradeBooked, and
        // PositionUpdated events leave as one paired claim — a single publish, a single
        // consumer signal (FR-09B15: side-effects are typed output events).
        final int signedQty = o.side == InputEvent.SIDE_BUY ? fillQty : -fillQty;
        final int newPosition = positions.bookTrade(o.accountId, o.securityId, signedQty, execPx);
        final long avgCostTicks = positions.lastAvgCostTicks();
        final long tradeSeq = ++tradeCounter;
        out.emitFillWithTradeAndPosition(o, fillQty, execPx, tradeSeq, newPosition, avgCostTicks,
            e.seq, flags, lastPxBySecurity[o.securityId], e.ingressNanos);
    }

    /** Cancel a market order's unfilled remainder in place: market orders never rest (FR-LOB04). */
    private void cancelRemainder(RestingOrder o, InputEvent e) {
        if (risk != null) {
            risk.release(o.accountId, o.securityId, o.side, o);   // released exactly once (FR-IMRG16)
        }
        o.status = RestingOrder.STATUS_CANCELED;
        o.remaining = 0;
        o.updatedAtMillis = e.eventTimeMillis;
        markTerminal(o.orderRef);
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CANCEL, true,
            lastPxBySecurity[o.securityId], e.ingressNanos);
    }

    private void onCancel(InputEvent e) {
        RestingOrder o = lookup(e.orderRef);
        if (o == null) {
            out.emitOrderNotFound(e.seq, e.ingressNanos);
            return;
        }
        long px = lastPxBySecurity[o.securityId];
        if (o.isOpen()) {
            if (o.isResting()) {
                booksBySecurity[o.securityId].remove(o);   // unlink while remaining is still open qty
            }
            if (risk != null) {
                risk.release(o.accountId, o.securityId, o.side, o);   // released exactly once (FR-IMRG16)
            }
            o.status = RestingOrder.STATUS_CANCELED;
            o.remaining = 0;
            o.updatedAtMillis = e.eventTimeMillis;
            markTerminal(o.orderRef);   // bounded retention: evicts the oldest terminal when full
            out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CANCEL, true, px, e.ingressNanos);
        } else {
            // 009 parity: canceling a terminal order returns (and re-publishes) it unchanged.
            out.emitOrderUpdate(o, e.seq, 0, true, px, e.ingressNanos);
        }
    }

    private void onForceFill(InputEvent e) {
        RestingOrder o = lookup(e.orderRef);
        if (o == null) {
            out.emitOrderNotFound(e.seq, e.ingressNanos);
            return;
        }
        long px = lastPxBySecurity[o.securityId];
        if (!o.isOpen() || o.remaining <= 0) {
            out.emitOrderUpdate(o, e.seq, 0, true, px, e.ingressNanos);
            return;
        }
        // 009 parity: force-fill executes the full remaining quantity at the last trade
        // price, falling back to the limit price when no trade has printed yet. The forced
        // execution IS a trade, so it advances the last trade price like any other.
        final long execPx = px != Px.NONE ? px : o.limitPx;
        if (o.isResting()) {
            booksBySecurity[o.securityId].remove(o);   // unlink while remaining is still open qty
        }
        lastPxBySecurity[o.securityId] = execPx;
        applyMatchFill(o, o.remaining, execPx, e, OutputEvent.FLAG_FORCE_FILL, booksBySecurity[o.securityId]);
    }

    /** Sequenced market-data price: feeds risk freshness/collar state. It no longer triggers
     *  fills — matching is driven exclusively by crossing orders (FR-LOB05). */
    private void onPriceTick(InputEvent e) {
        if (risk != null) {
            // Sequenced price + event-carried source time: freshness state for staleness checks
            // is deterministic on replay (FR-IMRG09/17).
            risk.onPrice(e.securityId, e.priceTicks, e.eventTimeMillis);
        }
        // A market-data tick seeds the mark only while no trade has printed; once the book
        // trades, the last TRADE price is the mark (ADR-051).
        if (lastPxBySecurity[e.securityId] == Px.NONE) {
            lastPxBySecurity[e.securityId] = e.priceTicks;
        }
    }

    /**
     * Market trade from the trade ticket (FR-09B08): no order and no matching — book the trade
     * and update the account's net position directly on the BLP thread (the single position
     * writer), then emit TradeBooked + PositionUpdated. Deterministic: quantity, side, and time
     * are carried in the event; the booking pipeline of trade-processor is fused in here.
     */
    private void onTradeNew(InputEvent e) {
        // Stamp the trade at the BLP's current mark for the security (FR-09B40: the /trades
        // payload carries the execution price); Px.NONE -> 0.000 at the edge when no trade or
        // tick has been seen yet, matching 009's trade-processor null-price handling.
        long execPx = lastPxBySecurity[e.securityId];
        if (risk != null) {
            long decideStart = System.nanoTime();
            RiskReason decision = risk.decideMarketTrade(e.clientOrderKey(), 0L, e.accountId,
                e.securityId, e.side, positions.get(e.accountId, e.securityId), e.qty, execPx,
                e.eventTimeMillis);
            metrics.recordRiskDecisionLatency(System.nanoTime() - decideStart);
            if (risk.duplicateReplay() || decision != RiskReason.ACCEPTED) {
                // Idempotent retry returns the original decision; a rejection books nothing and
                // moves no position (FR-IMRG14/23) — only the correlation ack leaves the BLP.
                out.emitTradeDecision(e.seq, (byte) decision.ordinal(),
                    decision == RiskReason.ACCEPTED, e.ingressNanos);
                return;
            }
        }
        int signedQty = e.side == InputEvent.SIDE_BUY ? e.qty : -e.qty;
        int newPosition = positions.bookTrade(e.accountId, e.securityId, signedQty, execPx);
        long avgCostTicks = positions.lastAvgCostTicks();
        long tradeSeq = ++tradeCounter;
        out.emitMarketTrade(e.accountId, e.securityId, e.side, e.qty, execPx, tradeSeq, newPosition,
            avgCostTicks, e.seq, e.eventTimeMillis, e.ingressNanos);
        metrics.recordMatchLatency(System.nanoTime() - e.ingressNanos);
    }

    // ----- control events (in-memory-risk-gateway, FR-IMRG11 / ADR-020) ---------------------
    // Payload slots are type-discriminated (see InputEvent javadoc): side = boolean, priceTicks =
    // control version, qty/limitPx = policy limit payload. Applied on the BLP thread only.

    private void onAccountControl(InputEvent e) {
        if (risk != null) {
            risk.putAccount(e.accountId, e.controlEnabled());
        }
    }

    private void onSecurityControl(InputEvent e) {
        if (risk != null) {
            risk.putSecurity(e.securityId, e.controlEnabled());
        }
    }

    private void onPolicyControl(InputEvent e) {
        if (risk != null) {
            risk.putPolicy(e.controlVersion(), e.controlEnabled());
            if (e.policyMaxPositionQty() > 0 && e.policyMaxConcentrationTicks() > 0L) {
                risk.putLimits(e.policyMaxPositionQty(), e.policyMaxConcentrationTicks());
            }
        }
    }

    private void onRestrictionControl(InputEvent e) {
        if (risk != null) {
            risk.putRestriction(e.securityId, e.controlEnabled());
        }
    }

    // ----- internal structures ------------------------------------------------------------

    private LimitBook bookFor(int securityId) {
        LimitBook book = booksBySecurity[securityId];
        if (book == null) {
            // Cold path: first order for the security. Log-driven, so creation (and the band
            // anchor chosen by the first limit price) is identical on every member and replay.
            book = new LimitBook(bookLevels, bookTickPx);
            booksBySecurity[securityId] = book;
        }
        return book;
    }

    /** The security's book, or null if no order has ever referenced the security. */
    public LimitBook book(int securityId) {
        return securityId >= 0 && securityId < booksBySecurity.length ? booksBySecurity[securityId] : null;
    }

    /**
     * Top-of-book depth for edge readers: best-first prices/quantities of one side, up to
     * {@code max} levels; returns the level count. Racy-but-safe: plain array reads may show
     * a stale level, never a broken structure (single-writer discipline).
     */
    public int bookDepth(int securityId, byte side, long[] outPx, long[] outQty, int max) {
        readFence();
        final LimitBook book = book(securityId);
        return book == null ? 0 : book.depth(side, outPx, outQty, max);
    }

    private RestingOrder lookup(int orderRef) {
        return ordersByRef.get(orderRef);
    }

    private void index(RestingOrder o) {
        ordersByRef.put(o.orderRef, o);
    }

    private RestingOrder takeFromPool() {
        RestingOrder o = freeList;
        if (o == null) {
            return new RestingOrder();
        }
        freeList = o.nextFree;
        o.nextFree = null;
        o.reset();
        return o;
    }

    /**
     * Record that {@code orderRef} just became terminal and, if the retention cap is full, evict the
     * oldest terminal order first. FIFO, BLP-thread only, allocation-free. Called exactly once per order
     * at the open→terminal transition (fill-to-zero, cancel, rejection, or a terminal warm-start
     * row) — republished terminal orders do not re-enter the ring.
     */
    private void markTerminal(int orderRef) {
        if (terminalRing == null) {
            return;   // eviction disabled (terminalCap <= 0): unbounded retention, pre-Tier-2B behavior
        }
        if (terminalCount == terminalCap) {
            int oldest = terminalRing[terminalHead];
            terminalHead = terminalHead + 1 == terminalCap ? 0 : terminalHead + 1;
            terminalCount--;
            evictOrder(oldest);
        }
        int tail = terminalHead + terminalCount;
        if (tail >= terminalCap) {
            tail -= terminalCap;
        }
        terminalRing[tail] = orderRef;
        terminalCount++;
    }

    /** Drop an aged-out terminal order from the ref index and return its entry to the pool (BLP thread).
     *  It was already off the book (unlinked at the terminal transition), so this only frees memory. */
    private void evictOrder(int orderRef) {
        RestingOrder o = ordersByRef.remove(orderRef);
        if (o == null) {
            return;
        }
        o.nextFree = freeList;   // recycle; takeFromPool resets fields on reuse
        freeList = o;
    }

    // ----- edge-readable telemetry ----------------------------------------------------------

    /**
     * Acquire-load of blpSeq: orders the subsequent plain counter read after the BLP's
     * release-store, so edge threads see counters at least as fresh as the last published
     * sequence they observe.
     */
    private void readFence() {
        BLP_SEQ.getAcquire(this);
    }

    public long eventsProcessed() {
        readFence();
        return eventsProcessed;
    }

    public long autoFillAttempts() {
        readFence();
        return autoFillAttempts;
    }

    public long autoFillSuccess() {
        readFence();
        return autoFillSuccess;
    }

    public long lastEventTimeMillis() {
        readFence();
        return lastEventTimeMillis;
    }

    public long blpSeq() {
        return blpSeq;
    }

    public long countOrdersNew() {
        readFence();
        return ordersNew;
    }

    public long countOrdersCancel() {
        readFence();
        return ordersCancel;
    }

    public long countForceFills() {
        readFence();
        return ordersForceFill;
    }

    public long countPriceTicks() {
        readFence();
        return priceTicks;
    }

    public long countTradesNew() {
        readFence();
        return tradesNew;
    }

    public long countControlEvents() {
        readFence();
        return controlEvents;
    }

    /** The authoritative risk state (null when risk is disabled). Edge readers must quiesce or
     *  tolerate racy reads; the BLP thread is the only writer. */
    public BlpRiskState riskState() {
        return risk;
    }

    /**
     * Net position for an account/security. Single-writer state read off the BLP thread; the
     * acquire-load orders the read after the BLP's last release-store. Intended for tests and
     * warm-start verification (read when the BLP is quiesced), not per-event edge polling.
     */
    public int positionQuantity(int accountId, int securityId) {
        readFence();
        return positions.get(accountId, securityId);
    }

    /** Weighted average cost basis (Px ticks) for an account/security; single-writer read off the BLP thread. */
    public long positionAvgCostTicks(int accountId, int securityId) {
        readFence();
        return positions.avgCostTicks(accountId, securityId);
    }

    public long blpThreadId() {
        return blpThreadId;
    }
}
