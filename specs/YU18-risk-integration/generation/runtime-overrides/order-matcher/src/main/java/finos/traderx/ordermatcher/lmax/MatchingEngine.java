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
 *
 * Self-trade prevention is GLOBAL and cancel-oldest (ADR-057): when an aggressor meets a resting
 * order of its own account, the resting order is cancelled and the aggressor continues into the
 * liquidity behind it. The policy is a fixed property of the engine, not a per-order or per-account
 * choice — see the ADR for why that is a deliberate simplification and what the upgrade costs.
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

    /**
     * YU17 (format-8, `format-8-price-derived-grid-design.md` section 2.1): a book's grid derived from
     * the collar's own replicated reference price -- the smallest power of ten strictly greater
     * than the reference in dollars, capped at the global grid so nothing is ever COARSER than
     * today, floored at 1 by construction.
     *
     * <p>THE SCALE CONVENTION LIVES HERE. The other convention is the fraction-of-par TICKER
     * category in {@code MatchingEngineClusteredService.FRACTION_OF_PAR_TICKER_PREFIXES}, which
     * asks a different question (quote GRANULARITY, six decimals of par) and OUTRANKS this map --
     * two conventions, two homes, each naming the other. Any future change to either derivation
     * re-interprets a stored anchor's unit and therefore requires a snapshot format bump; format 8
     * stores the tick in T_BOOK so the reinterpretation can no longer be silent.
     *
     * <p>Exact integer arithmetic on Px units (1e-6 dollars), allocation-free, pure. Measured
     * against all 69 live instruments the map puts every half-band between 6.6% and 64.9% of price
     * (design section 3). Every producible value (1, 10, 100, 1000) divides 10 000, so a cent price is
     * always on-grid and the UI ticket step can derive from the same number.
     *
     * <p>{@code capPx} is the CONFIGURED global grid ({@code BOOK_TICK_PX} -- 1000 by default and
     * on every deployment today), not a literal: the cap's job is "the top decade keeps exactly
     * today's geometry", and today's geometry is whatever the global is set to. That also keeps
     * the change monotone -- a book can only ever land on the global grid or FINER, never coarser
     * -- for any configured value, which is the property that lets this ship without re-sizing
     * every existing band. A cap that is not itself a power of ten is simply never produced (the
     * loop stops one decade below it), which is finer, so still monotone.
     */
    public static long decadeTickPx(final long refPx, final long capPx) {
        long t = 1L;
        while (t < capPx && t * 1_000_000L <= refPx) {
            t *= 10L;
        }
        return t;
    }

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
    // YU16 (ADR-060): per-security book-grid override; 0 = the global grid. DERIVED from the
    // committed ticker at registration/restore (the ADR-052 pattern) and stored nowhere - the
    // snapshot format is untouched. Consulted only on the cold book-creation path.
    private final long[] bookTickPxBySecurity;
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
    private long ordersReplace;
    private long ordersForceFill;
    private long priceTicks;
    private long tradesNew;
    private long controlEvents;
    private long selfTradesPrevented;   // ADR-057: resting orders cancelled by cancel-oldest STP
    private long bandReanchors;         // ADR-066: bands re-centred on the market reference
    private long bandStrandedCancels;   // ADR-066: resting orders cancelled by a band re-anchor
    /**
     * YU17 (ADR-072): the halves of {@link #tradeCounter} and the two band counters above that
     * belong to REPLAYED TAPE FLOW rather than to an operator.
     *
     * <p>WHY THEY EXIST. A proof asserts "my orders had exactly this trade effect" by bracketing a
     * global counter, and every one of these three is global over order writers — the library that
     * holds those readings says so itself. Continuous replayed flow moves all three, so the
     * readings stop being about the thing they name the day ADR-072 ships. Subtracting these gives
     * the operator-only sibling, which the replay cannot move by construction.
     *
     * <p>{@code externalTradeLegs} IS SNAPSHOTTED (via the service's header record) and the two
     * band shadows are NOT — each matching its sibling exactly. The trade counter is replicated
     * state that three members must agree on after a restore; the band counters are per-process
     * observability that a restarted member legitimately reads lower on, and their shadows have to
     * be read the same way or the subtraction goes negative on one member.
     */
    private long externalTradeLegs;
    private long externalBandReanchors;
    private long externalBandStrandedCancels;
    private long externalSelfTradesPrevented;   // ADR-072: the replay's half of selfTradesPrevented
    private long bookReticks;           // YU17: empty-book re-derivations that CHANGED a book's tick

    // ----- YU18 order types (components/order-types, FR-OT01..41) --------------------------------
    // Replicated state unless marked otherwise. Per-security stores are intrusive doubly-linked
    // lists through RestingOrder.storeNext/storePrev, kept in ascending ADMISSION SEQUENCE (FR-OT11),
    // so latching and repricing walk them in the total order the spec requires with no allocation.
    private int pendingCapacity = OrderTypes.DEFAULT_PENDING_CAPACITY;
    private int pegCapacity = OrderTypes.DEFAULT_PEG_CAPACITY;
    private int roundLimitOverride = OrderTypes.DEFAULT_ROUND_LIMIT;
    private final RestingOrder[] pendHead;
    private final RestingOrder[] pendTail;
    private final int[] pendCount;
    private final RestingOrder[] pegHead;
    private final RestingOrder[] pegTail;
    private final int[] pegCount;
    private int pendingTotal;
    private int pegTotal;
    /** FR-OT09: last qualifying print and whether one exists; NOT the risk mark. */
    private final long[] lastTradePx;
    private final boolean[] hasTraded;
    /** FR-OT25: the non-pegged best bid/ask a security's pegs were last priced against. */
    private final long[] pegRefBid;
    private final long[] pegRefAsk;
    /** FR-OT12 trigger queue: latched, not yet drained. Empty between commands (FR-OT35). */
    private RestingOrder[] triggerQueue;
    private int qHead;
    private int qTail;
    /** Append order into book levels. Only ORDERS rows in the snapshot; never emitted. */
    private long bookSeqCounter;
    /** FR-OT37 business-date lifecycle. */
    private int businessDate;
    private boolean dayOpen;
    private byte lastBusinessDayOutcome;
    public static final byte DAY_APPLIED = 0;
    public static final byte DAY_STALE = 1;
    public static final byte DAY_OUT_OF_SEQUENCE = 2;
    /** Peg walk round id (FR-OT25); per-process, only compared within one walk. */
    private int roundId;
    /**
     * 0, or FLAG_RESTING_UPDATE while the engine acts on orders that are NOT the input being
     * applied (trigger drains, peg reprices, day expiry). Every egress those produce must carry the
     * flag, or the gateway's first-direct-ack correlation would count them as this input's answer.
     */
    private int unsolicited;
    /** The security whose book the current command touched (-1 = none); settled after it. */
    private int settleSecurity = -1;
    // Per-process observability (never snapshotted, like bandReanchors).
    private long cascadeLimitEvents;
    private long fokInvariantBreaches;
    private int lastSettleRounds;
    private int maxSettleRounds;
    private long ordersTriggered;
    private long pegReprices;

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
        this.bookTickPxBySecurity = new long[maxSecurities];
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
        this.pendHead = new RestingOrder[maxSecurities];
        this.pendTail = new RestingOrder[maxSecurities];
        this.pendCount = new int[maxSecurities];
        this.pegHead = new RestingOrder[maxSecurities];
        this.pegTail = new RestingOrder[maxSecurities];
        this.pegCount = new int[maxSecurities];
        this.lastTradePx = new long[maxSecurities];
        this.hasTraded = new boolean[maxSecurities];
        this.pegRefBid = new long[maxSecurities];
        this.pegRefAsk = new long[maxSecurities];
        this.triggerQueue = new RestingOrder[this.pendingCapacity + 1];
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

    /**
     * YU18 test seam (review r3 item 1): smaller order-type limits for a TEST engine. Production
     * never calls this; the defaults are compile-time consensus constants, recorded in the snapshot
     * and checked on restore ({@link #orderTypeLimits}). Only legal before any pending order or peg
     * exists. {@code roundLimit} 0 = the proven per-step bound alone; a positive value may only
     * LOWER it (FR-OT26).
     */
    public void setOrderTypeLimits(final int pending, final int pegs, final int roundLimit) {
        if (pendingTotal != 0 || pegTotal != 0) {
            throw new IllegalStateException("order-type limits cannot change while orders are held");
        }
        if (pending < 1 || pegs < 1 || roundLimit < 0) {
            throw new IllegalArgumentException("order-type limits must be positive");
        }
        this.pendingCapacity = pending;
        this.pegCapacity = pegs;
        this.roundLimitOverride = roundLimit;
        this.triggerQueue = new RestingOrder[pending + 1];
    }

    /** {pendingCapacity, pegCapacity, roundLimitOverride}: the consensus limits in force. */
    public long[] orderTypeLimits() {
        return new long[] { pendingCapacity, pegCapacity, roundLimitOverride };
    }

    public long bookTickPx() {
        return bookTickPx;
    }

    @Override
    public void onEvent(InputEvent e, long sequence, boolean endOfBatch) {
        switch (e.type) {
            case InputEvent.TYPE_ORDER_NEW -> { ordersNew++; onNewOrder(e); }
            case InputEvent.TYPE_ORDER_CANCEL -> { ordersCancel++; onCancel(e); }
            case InputEvent.TYPE_ORDER_REPLACE -> { ordersReplace++; onReplace(e); }
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
            case InputEvent.TYPE_BUSINESS_DAY -> { controlEvents++; onBusinessDay(e); }
            default -> { /* ignore unknown event types */ }
        }
        // YU18 (FR-OT26): every command that touched a book finishes its cascade here, in the same
        // apply — triggers drained, pegs repriced — before the next command can observe anything.
        if (settleSecurity >= 0) {
            final int s = settleSecurity;
            settleSecurity = -1;
            settle(s, e);
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
        applyStagedExtension(o);   // YU18 format 11: typed state rides a T_ORDER_EXT just before
        if (risk != null && o.isOpen()) {
            risk.reaccumulateReservation(accountId, securityId, side, reservedNotional, reservedQty);
        }
        index(o);
        if (o.status == RestingOrder.STATUS_PENDING_TRIGGER) {
            if (pendingTotal >= pendingCapacity) {
                throw new IllegalStateException("restore incomplete: pending store capacity");
            }
            linkPending(o);   // admission-sequence order is re-derived, not trusted to row order
            return;
        }
        if (o.orderType == OrderTypes.PEGGED && o.isOpen()) {
            if (pegTotal >= pegCapacity) {
                throw new IllegalStateException("restore incomplete: peg store capacity");
            }
            linkPeg(o);
            if (o.status == RestingOrder.STATUS_SUSPENDED) {
                return;
            }
        }
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
            restAt(o, book, slot);
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

    /** YU17 (ADR-072): restore the replayed half of the trade counter from the snapshot header.
     *  Same monotone rule as {@link #bootstrapTradeCounter}, and it MUST be restored: the operator
     *  sibling is a subtraction, and three members that restore different halves report three
     *  different operator counts for one committed log. */
    public void bootstrapExternalTradeLegs(long legs) {
        if (legs > externalTradeLegs) {
            externalTradeLegs = legs;
        }
    }

    /**
     * Snapshot restore: re-create a security's book on the STORED grid and adopt its band anchor
     * (before order rows).
     *
     * <p>YU17 (format-8 design section 2.4): {@code tickPx} comes off the T_BOOK record and the
     * derivation is NEVER consulted here. That is the whole point of storing it -- a restoring
     * build reproduces the geometry the writing build actually had, rather than whatever its own
     * rule would compute today. MECS validates the value before calling (fail-closed on
     * non-positive, off-cap, or not dividing 10 000).
     */
    public void bootstrapBook(int securityId, long baseLevel, long tickPx) {
        LimitBook book = booksBySecurity[securityId];
        if (book == null) {
            book = new LimitBook(bookLevels, tickPx);
            booksBySecurity[securityId] = book;
        } else if (book.tickTicks() != tickPx) {
            book.retick(tickPx);   // throws if anything rests: restore order puts T_BOOK before T_ORDER
        }
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

    /**
     * Book geometry as {securityId, baseLevel, tickPx} for every created book (snapshot).
     *
     * <p>YU17 (format-8 design section 2.4): the TICK rides beside the anchor from format 8 onward, and
     * it has to. {@code baseLevel} is DENOMINATED in the book's tick, so a stored anchor with no
     * stored unit is silently reinterpreted by any build whose derivation rule differs -- the
     * hazard class the format-4 postmortem exists to forbid, and the reason
     * {@code MIN_READABLE_SNAPSHOT_FORMAT} rises to 8. With the unit stored, restore reads geometry
     * instead of re-deriving it and no future change to the derivation can misread an old anchor.
     *
     * <p>Includes books that exist but have never been anchored ({@code baseLevel} -1); restore
     * re-creates them on the stored tick and leaves them un-anchored, which is what they were.
     */
    public java.util.List<long[]> bookBaseTuples() {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (int s = 0; s < booksBySecurity.length; s++) {
            if (booksBySecurity[s] != null) {
                out.add(new long[] { s, booksBySecurity[s].baseLevel(), booksBySecurity[s].tickTicks() });
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
        if (e.securityId < 0 || e.securityId >= booksBySecurity.length) {
            return;   // unreachable from the gateway; bookFor below would have thrown before YU18
        }
        settleSecurity = e.securityId;
        if (e.orderType != OrderTypes.LEGACY) {
            onTypedNewOrder(e);   // YU18: every typed order (FR-OT01); untyped keeps this path exactly
            return;
        }
        RestingOrder o = takeFromPool();
        o.admissionSeq = admissionSeqOf(e);
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
        // YU17 (format-8 design section 2.3): a book holding nothing re-derives its grid HERE, before the
        // on-grid check below judges this limit against it. See rederiveIfEmpty for the four jobs
        // this one line is doing.
        rederiveIfEmpty(book, e.securityId);
        int restSlot = LimitBook.NO_LEVEL;
        if (!market) {
            if (!book.onGrid(e.limitPx)) {
                reject(o, e, RiskReason.INVALID);
                return;
            }
            restSlot = bandSlot(book, e.securityId, e.limitPx, null, e);
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
                restAt(o, book, restSlot);  // rest at its level tail: arrival order IS time priority
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
            // Self-trade prevention, cancel-oldest (ADR-057). One int compare against replicated
            // state on the apply thread, so every member and every replay reaches it identically.
            // Cancel-oldest is not a preference: cross() re-reads headAt each iteration, so a
            // policy that SKIPS a self order without removing it returns the same order forever
            // and the apply loop never terminates — on all three members and on replay. Removing
            // the head is what lets the loop advance, and it is also the only policy that lets the
            // aggressor reach genuine counterparties queued BEHIND its own stale quote.
            if (r.accountId == a.accountId) {
                preventSelfTrade(r, e, book);
                opp = buy ? book.bestAskSlot() : book.bestBidSlot();
                continue;
            }
            // YU18 (FR-OT20): a resting iceberg matches only its visible tranche.
            final int fillQty = Math.min(a.remaining, r.visibleQty());
            lastPxBySecurity[a.securityId] = levelPx;   // the last trade price is a matching OUTPUT
            applyMatchFill(r, fillQty, levelPx, e, OutputEvent.FLAG_RESTING_UPDATE, book);
            applyMatchFill(a, fillQty, levelPx, e, unsolicited, book);
            // YU18 (FR-OT09/10): each fill is one qualifying print, in execution order. Latching
            // only queues; the queue drains after this aggressor finishes (FR-OT12).
            recordPrint(a.securityId, levelPx, e);
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
            if (o.orderType == OrderTypes.ICEBERG) {
                o.displayed -= fillQty;   // FR-OT20: fills come out of the visible tranche only
            }
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
            terminal(o);
        } else {
            o.status = RestingOrder.STATUS_PARTIALLY_FILLED;
            flags |= OutputEvent.FLAG_PARTIAL_FILL;
            if (resting && o.orderType == OrderTypes.ICEBERG && o.displayed == 0) {
                // FR-OT21: replenishment is a TRANSFER from hidden to visible, in this same match
                // step, to the TAIL of the same level. The order keeps its one reservation (taken
                // for the full quantity at admission) and no risk decision runs, so it can neither
                // be refused nor double counted. remove() subtracts displayed (0) and append() adds
                // the new tranche, so the level never observably empties.
                final int slot = o.bookLevel;
                book.remove(o);
                o.displayed = Math.min(o.displayQty, o.remaining);
                restAt(o, book, slot);
            }
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
        // ADR-072: attributed to the account of the LEG, not of the aggressor. A replayed order
        // crossing an operator's resting one books one leg each, and each side's own counter is
        // the honest reading of who traded.
        if (InputEvent.isReplayFlow(o.accountId)) {
            externalTradeLegs++;
        }
        out.emitFillWithTradeAndPosition(o, fillQty, execPx, tradeSeq, newPosition, avgCostTicks,
            e.seq, flags, lastPxBySecurity[o.securityId], e.ingressNanos);
    }

    /**
     * Cancel a resting order the aggressor would otherwise have self-traded against (ADR-057).
     *
     * Mechanically identical to {@link #onCancel} — unlink, release the reservation exactly once,
     * mark terminal, emit — with two differences that are the whole client-facing contract:
     *
     * <ul>
     *   <li>{@code riskReason = SELF_TRADE_PREVENTED}, which reaches the client on egress ack byte
     *       22. It is what distinguishes "the venue cancelled this to prevent a self-trade" from
     *       "my own cancel succeeded" (reason ACCEPTED) and from a rejection (a different kind).</li>
     *   <li>{@code FLAG_RESTING_UPDATE}, because this is an UNSOLICITED cancel of an order that is
     *       not the input being applied. Without it the gateway's first-direct-ack correlation
     *       would answer the aggressor's own offer with a CANCELED ack for a different order, and
     *       the batch outstanding count would go wrong (FR-LOB07).</li>
     * </ul>
     *
     * The aggressor is never the one cancelled, which is what makes the compound case with an
     * atomic replace decidable: a replace's new order can trigger this against the participant's
     * other resting orders, but can never itself be removed by it.
     */
    private void preventSelfTrade(RestingOrder r, InputEvent e, LimitBook book) {
        selfTradesPrevented++;
        // ADR-072: the operator-only half. STP is decidable here BECAUSE the guard at the call site
        // (`r.accountId == a.accountId`) only reaches this method when the resting order and the
        // aggressor are the SAME account — so there is no "which side counts" question to get
        // wrong, unlike a trade leg, where the two sides can be different writers and both are
        // counted separately. PER-PROCESS, matching selfTradesPrevented, which is a plain field
        // and is in neither the snapshot writer nor the reader; see the persistence rule above the
        // twins in ClusterNodeMain before adding another.
        if (InputEvent.isReplayFlow(r.accountId)) {
            externalSelfTradesPrevented++;
        }
        cancelUnsolicited(r, e, book, RiskReason.SELF_TRADE_PREVENTED);
    }

    /** The venue cancels a resting order the client did not ask to cancel: STP, or stranded by a
     *  band re-anchor. {@code reason} reaches the client on the ack; FLAG_RESTING_UPDATE keeps the
     *  gateway's first-direct-ack correlation from answering the input being applied with it. */
    private void cancelUnsolicited(RestingOrder r, InputEvent e, LimitBook book, RiskReason reason) {
        book.remove(r);   // unlink while remaining is still open qty (the level aggregate subtracts it)
        if (risk != null) {
            risk.release(r.accountId, r.securityId, r.side, r);   // released exactly once (FR-IMRG16)
        }
        r.status = RestingOrder.STATUS_CANCELED;
        r.remaining = 0;
        r.displayed = 0;
        r.riskReason = (byte) reason.ordinal();
        r.updatedAtMillis = e.eventTimeMillis;
        terminal(r);
        out.emitOrderUpdate(r, e.seq, OutputEvent.FLAG_CANCEL | OutputEvent.FLAG_RESTING_UPDATE,
            true, lastPxBySecurity[r.securityId], e.ingressNanos);
    }

    /**
     * YU18: the venue cancels an OPEN order that may be resting, pending or suspended (DAY expiry,
     * cascade exhaustion). The resting case is exactly {@link #cancelUnsolicited}.
     */
    private void cancelByVenue(RestingOrder r, InputEvent e, RiskReason reason) {
        if (r.isResting()) {
            cancelUnsolicited(r, e, booksBySecurity[r.securityId], reason);
            return;
        }
        unlinkStore(r);
        if (risk != null) {
            risk.release(r.accountId, r.securityId, r.side, r);
        }
        r.status = RestingOrder.STATUS_CANCELED;
        r.remaining = 0;
        r.displayed = 0;
        r.riskReason = (byte) reason.ordinal();
        r.updatedAtMillis = e.eventTimeMillis;
        terminal(r);
        out.emitOrderUpdate(r, e.seq, OutputEvent.FLAG_CANCEL | OutputEvent.FLAG_RESTING_UPDATE,
            true, lastPxBySecurity[r.securityId], e.ingressNanos);
    }

    // ----- book-derived market data (ADR-067's undisputed slice) ----------------------------
    //
    // The book already knows its best bid, best ask and mark; until now none of it was exported
    // anywhere, and the console drew a synthetic price it was handed. These are READ-ONLY
    // accessors for the member's HTTP surface -- beside consensus, never sequenced: every member
    // computes them from applied state, so sequencing them would grow the log for data each
    // member already holds. Same unsynchronized off-thread read posture as recoveryDigest():
    // a torn read costs one stale scrape, never engine state. Px.NONE (0) = absent -- a one-sided
    // book reports the side it has and nothing else; no midpoint is synthesized here or anywhere.

    /** Best resting bid in Px ticks, or {@link Px#NONE} when there is no book or the side is empty. */
    public long bestBidPx(int securityId) {
        final LimitBook book = securityId >= 0 && securityId < booksBySecurity.length
            ? booksBySecurity[securityId] : null;
        if (book == null) {
            return Px.NONE;
        }
        final int slot = book.bestBidSlot();
        return slot == LimitBook.NO_LEVEL ? Px.NONE : book.priceAt(slot);
    }

    /** Best resting ask in Px ticks, or {@link Px#NONE} when there is no book or the side is empty. */
    public long bestAskPx(int securityId) {
        final LimitBook book = securityId >= 0 && securityId < booksBySecurity.length
            ? booksBySecurity[securityId] : null;
        if (book == null) {
            return Px.NONE;
        }
        final int slot = book.bestAskSlot();
        return slot == LimitBook.NO_LEVEL ? Px.NONE : book.priceAt(slot);
    }

    /** The mark in Px ticks (last trade, else the seeding tick -- ADR-051), or {@link Px#NONE}. */
    public long markPx(int securityId) {
        return securityId >= 0 && securityId < lastPxBySecurity.length
            ? lastPxBySecurity[securityId] : Px.NONE;
    }

    // ----- price band follows the market (ADR-066) ------------------------------------------

    /**
     * The price the collar is judged against: the sequenced feed price the risk gate holds
     * (it keeps ticking after the book prints, unlike the mark), else the mark (last trade,
     * or the seeding tick when nothing printed — ADR-051), else {@link Px#NONE}. Every input
     * is replicated and snapshotted, so the anchor it yields is identical on every member and
     * on replay.
     */
    private long collarReferencePx(int securityId) {
        if (risk != null) {
            final long feed = risk.lastPrice(securityId);
            if (feed > 0L) {
                return feed;
            }
        }
        return lastPxBySecurity[securityId];
    }

    /**
     * Book slot for a limit, letting the band follow the market. An un-anchored book anchors
     * on the reference (the limit only when nothing has ticked or printed — the pre-ADR-066
     * rule, kept as the fallback). A limit the current band refuses is re-judged against a
     * band centred on the reference: if that band admits it, the book is re-anchored there —
     * lazily, only when it changes the answer, so a band the market still sits inside never
     * pays — and resting orders the new band cannot hold are cancelled (reason PRICE_COLLAR)
     * before the re-index. {@code keep} is a replace's own resting order: re-anchoring may not
     * strand the order being replaced, so a replace that would is refused and the order stands.
     */
    private int bandSlot(LimitBook book, int securityId, long limitPx, RestingOrder keep, InputEvent e) {
        final long ref = collarReferencePx(securityId);
        if (!book.anchored()) {
            book.anchorAround(ref != Px.NONE ? ref : limitPx);
        }
        final int slot = book.slotFor(limitPx);
        if (slot != LimitBook.NO_LEVEL || ref == Px.NONE) {
            return slot;
        }
        final long newBase = book.baseCentredOn(ref / book.tickTicks());
        if (newBase == book.baseLevel() || !book.fitsAt(newBase, limitPx)) {
            return LimitBook.NO_LEVEL;   // outside the market's collar too: a genuine refusal
        }
        if (keep != null && keep.isResting() && !book.fitsAt(newBase, keep.limitPx)) {
            return LimitBook.NO_LEVEL;
        }
        cancelStranded(book, InputEvent.SIDE_BUY, newBase, e);
        cancelStranded(book, InputEvent.SIDE_SELL, newBase, e);
        book.rebase(newBase);
        bandReanchors++;
        if (InputEvent.isReplayFlow(e.accountId)) {   // ADR-072
            externalBandReanchors++;
        }
        return book.slotFor(limitPx);
    }

    /** Cancel every resting order on {@code side} whose level a band anchored at {@code newBase}
     *  cannot hold. Occupied levels are walked top-down so cancels arrive in a fixed order. */
    private void cancelStranded(LimitBook book, byte side, long newBase, InputEvent e) {
        int slot = book.occupiedBelow(side, book.levels());
        while (slot != LimitBook.NO_LEVEL) {
            final int next = book.occupiedBelow(side, slot);
            if (!book.fitsAt(newBase, book.priceAt(slot))) {
                RestingOrder r;
                while ((r = book.headAt(side, slot)) != null) {
                    bandStrandedCancels++;
                    // Attributed to the flow that CAUSED the re-anchor, not to the order it
                    // stranded: assert_band_effects asks "how much band movement did THIS scenario
                    // cause", and the scenario is the order that moved the band.
                    if (InputEvent.isReplayFlow(e.accountId)) {   // ADR-072
                        externalBandStrandedCancels++;
                    }
                    cancelUnsolicited(r, e, book, RiskReason.PRICE_COLLAR);
                }
            }
            slot = next;
        }
    }

    /** Cancel a market order's unfilled remainder in place: market orders never rest (FR-LOB04). */
    private void cancelRemainder(RestingOrder o, InputEvent e) {
        if (risk != null) {
            risk.release(o.accountId, o.securityId, o.side, o);   // released exactly once (FR-IMRG16)
        }
        o.status = RestingOrder.STATUS_CANCELED;
        o.remaining = 0;
        o.updatedAtMillis = e.eventTimeMillis;
        terminal(o);
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CANCEL | unsolicited, true,
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
                settleSecurity = o.securityId;             // YU18: a removed best level moves pegs
            }
            unlinkStore(o);   // YU18: a pending stop or suspended peg leaves its store (FR-OT28)
            if (risk != null) {
                risk.release(o.accountId, o.securityId, o.side, o);   // released exactly once (FR-IMRG16)
            }
            o.status = RestingOrder.STATUS_CANCELED;
            o.remaining = 0;
            o.displayed = 0;
            o.updatedAtMillis = e.eventTimeMillis;
            terminal(o);   // bounded retention: evicts the oldest terminal when full
            out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CANCEL, true, px, e.ingressNanos);
        } else {
            // 009 parity: canceling a terminal order returns (and re-publishes) it unchanged.
            out.emitOrderUpdate(o, e.seq, 0, true, px, e.ingressNanos);
        }
    }

    /**
     * Atomic replace: cancel-and-add of one live order inside a SINGLE apply (ADR-058).
     *
     * <p>Modelling replace as two sequenced operations was the tempting shortcut and it is wrong.
     * cancel-then-add leaves a window in which the client's order does not exist, so a rejected add
     * (risk gate, price collar) leaves them with NOTHING and answers one request with two messages;
     * add-then-cancel briefly doubles the exposure the risk gate sees and lets an immediately
     * marketable new order fill on both. One sequenced command removes the window by construction:
     * there is no point between the two halves at which any observer — member, replay, or client —
     * can be scheduled.
     *
     * <p>The order KEEPS its orderRef. FIX 4.4 permits it, and it is the strongest form of the
     * atomicity this command exists for: the client's order identity is never even momentarily
     * absent. It also means the replace consumes no value from the orderRef generator, so the
     * generator stays exactly what the snapshot header says it is.
     *
     * <p>Structure: everything that can FAIL is evaluated before anything is mutated, so a rejected
     * replace leaves the original order bit-identical — including its risk reservation, which is
     * released and then restored from the two numbers {@code release} zeroed.
     *
     * <p>Queue priority is LOST, except on a strict size-down at an unchanged price. That is the
     * rule real venues use, and it is the only case where nothing about the order becomes more
     * aggressive; every other replace goes to the tail of its level, which is what "cancel and add"
     * means for time priority.
     *
     * <p><b>Compound case — replace x self-trade prevention (the case neither ADR covered).</b> The
     * replaced order is unlinked from the book BEFORE it crosses, so it is an aggressor and can
     * never meet itself. If it meets OTHER resting orders of the same account, cancel-oldest fires
     * on those and the replace survives — the aggressor is never the side STP cancels. So a client
     * that asked only to modify an order can never be left with nothing by STP, and the ordering is
     * fixed by the code path rather than by timing: the replace commits, then matching runs, then
     * STP acts within matching. Identical on every member and on replay.
     */
    private void onReplace(InputEvent e) {
        // Idempotent retry (FR-IMRG14): a replace key already decided maps to its ONE original
        // decision. Re-emit the order as it stands; never apply the replace twice.
        if (risk != null && e.clientOrderKey() != 0L) {
            final int originalRef = risk.existingOrderRef(e.clientOrderKey());
            if (originalRef >= 0) {
                final RestingOrder original = lookup(originalRef);
                if (original != null) {
                    out.emitOrderUpdate(original, e.seq, 0, false,
                        lastPxBySecurity[original.securityId], e.ingressNanos);
                    return;
                }
            }
        }
        final RestingOrder o = lookup(e.orderRef);
        if (o == null) {
            out.emitOrderNotFound(e.seq, e.ingressNanos);
            return;
        }
        final long px = lastPxBySecurity[o.securityId];
        if (!o.isOpen()) {
            // Too late to replace. NOT the terminal-republish that cancel does: republishing a
            // FILLED order emits KIND_ORDER_FILLED, which is exactly what a replace that filled on
            // the way in also emits — the gateway could not tell "replaced and filled" from "was
            // already done", and would report the second as a success. A reject is unambiguous, and
            // the order is untouched either way.
            out.emitRequestRejected(o, e.seq, (byte) RiskReason.INVALID.ordinal(), px, e.ingressNanos);
            return;
        }
        settleSecurity = o.securityId;
        // YU18 (FR-OT05/29): type and TIF are immutable. A typed replace names the order's own type
        // (TIF absent or equal); an untyped replace applies only to limit-shaped orders.
        if ((e.orderType != OrderTypes.LEGACY && e.orderType != o.orderType)
            || (e.tif != OrderTypes.TIF_NONE && e.tif != o.tif)) {
            out.emitRequestRejected(o, e.seq, (byte) RiskReason.INVALID.ordinal(), px, e.ingressNanos);
            return;
        }
        final boolean limitShaped = o.orderType == OrderTypes.LEGACY || o.orderType == OrderTypes.LIMIT
            || (o.orderType == OrderTypes.STOP_LIMIT && o.triggered);
        if (!limitShaped) {
            onTypedReplace(e, o, px);
            return;
        }

        final LimitBook book = bookFor(o.securityId);
        final int filled = o.quantity - o.remaining;
        final int newRemaining = e.qty - filled;
        final long newLimitPx = e.limitPx;
        // A replace to a quantity at or below what already executed cannot be honoured as a
        // modification, and turning it into a cancel would answer a modify request with a
        // termination the client did not ask for. Reject; the order stands.
        if (e.qty <= 0 || newRemaining <= 0 || newLimitPx <= Px.NONE || !book.onGrid(newLimitPx)) {
            out.emitRequestRejected(o, e.seq, (byte) RiskReason.INVALID.ordinal(), px, e.ingressNanos);
            return;
        }
        final int newSlot = bandSlot(book, o.securityId, newLimitPx, o, e);
        if (newSlot == LimitBook.NO_LEVEL) {
            out.emitRequestRejected(o, e.seq, (byte) RiskReason.PRICE_COLLAR.ordinal(), px, e.ingressNanos);
            return;
        }

        if (risk != null) {
            // Release BEFORE deciding: evaluating the new order on top of the old order's own
            // reservation double-counts the account's exposure, and would reject an ordinary
            // size-DOWN for using credit it is in the middle of giving back.
            final long heldNotional = o.reservedNotional;
            final int heldQty = o.reservedQty;
            risk.release(o.accountId, o.securityId, o.side, o);
            final long decideStart = System.nanoTime();
            final RiskReason decision = risk.decideAndReserve(e.clientOrderKey(), 0L, o.orderRef,
                o.accountId, o.securityId, o.side, positions.get(o.accountId, o.securityId),
                newRemaining, newLimitPx, e.eventTimeMillis, o);
            metrics.recordRiskDecisionLatency(System.nanoTime() - decideStart);
            if (decision != RiskReason.ACCEPTED) {
                // Exact restore: release() zeroed both the account aggregates and the holder, so
                // putting the same two numbers back leaves exposure bit-identical to before.
                risk.reaccumulateReservation(o.accountId, o.securityId, o.side, heldNotional, heldQty);
                o.setReservation(heldNotional, heldQty);
                out.emitRequestRejected(o, e.seq, (byte) decision.ordinal(), px, e.ingressNanos);
                return;
            }
        }

        // ---- committed from here: nothing below can fail ----
        if (newLimitPx == o.limitPx && newRemaining < o.remaining && o.isResting()) {
            book.reduce(o, o.remaining - newRemaining);   // keeps its queue slot: strict size-down
            o.quantity = e.qty;
            o.remaining = newRemaining;
            o.updatedAtMillis = e.eventTimeMillis;
            out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_REPLACE, true, px, e.ingressNanos);
            return;
        }
        if (o.isResting()) {
            book.remove(o);   // unlink while remaining still holds the OLD open quantity
        }
        o.quantity = e.qty;
        o.remaining = newRemaining;
        o.limitPx = newLimitPx;
        o.status = filled > 0 ? RestingOrder.STATUS_PARTIALLY_FILLED : RestingOrder.STATUS_NEW;
        o.updatedAtMillis = e.eventTimeMillis;
        // Ack first, exactly as onNewOrder does: the first non-resting order-lifecycle ack after an
        // offer is the gateway's correlation contract, and a replace that crosses would otherwise
        // have its fill ack arrive first.
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_REPLACE, true, px, e.ingressNanos);
        cross(o, book, e);
        if (o.remaining > 0) {
            restAt(o, book, newSlot);   // tail of its level: a replace surrenders time priority
        }
    }

    private void onForceFill(InputEvent e) {
        RestingOrder o = lookup(e.orderRef);
        if (o == null) {
            out.emitOrderNotFound(e.seq, e.ingressNanos);
            return;
        }
        long px = lastPxBySecurity[o.securityId];
        // 009 parity: force-fill executes the full remaining quantity at the last trade
        // price, falling back to the limit price when no trade has printed yet. The forced
        // execution IS a trade, so it advances the last trade price like any other.
        final long execPx = px != Px.NONE ? px : o.limitPx;
        if (!o.isOpen() || o.remaining <= 0 || execPx <= Px.NONE) {
            // YU18: a pending STOP has no limit to fall back on; with no mark it stays as it is.
            out.emitOrderUpdate(o, e.seq, 0, true, px, e.ingressNanos);
            return;
        }
        if (o.isResting()) {
            booksBySecurity[o.securityId].remove(o);   // unlink while remaining is still open qty
        }
        // YU18: an operator force-fill is NOT a qualifying print (decision D3), but it does take the
        // order out of its store and can move a peg reference.
        unlinkStore(o);
        settleSecurity = o.securityId;
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
        if (InputEvent.isReplayFlow(e.accountId)) {   // ADR-072
            externalTradeLegs++;
        }
        out.emitMarketTrade(e.accountId, e.securityId, e.side, e.qty, execPx, tradeSeq, newPosition,
            avgCostTicks, e.seq, e.eventTimeMillis, e.ingressNanos);
        metrics.recordMatchLatency(System.nanoTime() - e.ingressNanos);
    }

    // ===== YU18 order types (components/order-types) ==========================================

    private static long admissionSeqOf(InputEvent e) {
        return e.admissionSeq != 0L ? e.admissionSeq : e.seq;
    }

    /** Append to a level's tail, stamping the append order the snapshot restores FIFO from. */
    private void restAt(RestingOrder o, LimitBook book, int slot) {
        o.bookSeq = ++bookSeqCounter;
        book.append(o, slot);
    }

    /** Every open-to-terminal transition: leave any store, then enter bounded retention. */
    private void terminal(RestingOrder o) {
        unlinkStore(o);
        markTerminal(o.orderRef);
    }

    private void rejectTyped(RestingOrder o, InputEvent e, RiskReason reason) {
        reject(o, e, reason);
    }

    /**
     * FR-OT01..08, 13, 16..18, 20, 23, 31: admission of a typed order. Everything that can refuse
     * is evaluated before anything is mutated, exactly as the untyped path does; a refusal is a
     * journaled, addressable REJECTED order that never reserved anything.
     */
    private void onTypedNewOrder(InputEvent e) {
        final RestingOrder o = takeFromPool();
        o.orderRef = e.orderRef;
        o.accountId = e.accountId;
        o.securityId = e.securityId;
        o.side = e.side;
        o.quantity = e.qty;
        o.remaining = e.qty;
        o.status = RestingOrder.STATUS_NEW;
        o.riskReason = (byte) RiskReason.ACCEPTED.ordinal();
        o.lastExecPx = Px.NONE;
        o.createdAtMillis = e.eventTimeMillis;
        o.updatedAtMillis = e.eventTimeMillis;
        o.orderType = e.orderType;
        o.tif = e.tif == OrderTypes.TIF_NONE ? OrderTypes.defaultTif(e.orderType) : e.tif;
        o.admissionSeq = admissionSeqOf(e);
        o.displayQty = e.displayQty;
        o.pegRef = e.pegRef;
        o.pegOffset = e.pegOffset;
        o.trailMode = e.trailMode;
        o.trailValue = e.trailValue;
        final byte type = e.orderType;
        final int s = e.securityId;

        // A log entry is data: re-run the boundary's stateless validation (FR-OT03, FR-OT41).
        if (OrderTypes.validate(type, o.tif, e.side, e.qty, e.limitPx, e.stopPx, e.displayQty,
                e.pegRef, e.pegOffset, e.trailMode, e.trailValue) != OrderTypes.OK) {
            rejectTyped(o, e, RiskReason.INVALID);
            return;
        }
        if (o.tif == OrderTypes.DAY) {
            if (!dayOpen) {
                rejectTyped(o, e, RiskReason.NO_TRADING_DAY);   // FR-OT08
                return;
            }
            o.sessionDate = businessDate;
        }
        final LimitBook book = bookFor(s);
        rederiveIfEmpty(book, s);
        switch (type) {
            case OrderTypes.STOP, OrderTypes.STOP_LIMIT, OrderTypes.TRAILING_STOP ->
                admitPending(o, e, book);
            case OrderTypes.PEGGED -> admitPeg(o, e, book);
            default -> admitImmediate(o, e, book);   // MARKET, LIMIT, ICEBERG
        }
    }

    /** MARKET / LIMIT / ICEBERG, with IOC and FOK (FR-OT06, 07, 17, 20, 31). */
    private void admitImmediate(RestingOrder o, InputEvent e, LimitBook book) {
        final int s = o.securityId;
        final boolean market = o.orderType == OrderTypes.MARKET;
        int restSlot = LimitBook.NO_LEVEL;
        if (!market) {
            if (!book.onGrid(e.limitPx)) {
                rejectTyped(o, e, RiskReason.INVALID);
                return;
            }
            restSlot = bandSlot(book, s, e.limitPx, null, e);
            if (restSlot == LimitBook.NO_LEVEL) {
                rejectTyped(o, e, RiskReason.PRICE_COLLAR);
                return;
            }
            o.limitPx = e.limitPx;
        }
        if (risk != null) {
            final long validationPx = market ? marketValidationPx(book, s, o.side) : o.limitPx;
            final RiskReason decision = risk.decideAndReserve(e.clientOrderKey(), 0L, o.orderRef,
                o.accountId, s, o.side, positions.get(o.accountId, s), o.quantity, validationPx,
                e.eventTimeMillis, o);
            if (decision != RiskReason.ACCEPTED) {
                o.riskReason = (byte) decision.ordinal();
                reject(o, e, null);
                return;
            }
        }
        if (o.tif == OrderTypes.FOK && !fokFeasible(o, book)) {
            // FR-OT31: infeasible -> no prints, no STP, no replenishment. Only this order's own
            // record changes: its reservation is released and it answers CANCELED FOK_UNFILLABLE.
            if (risk != null) {
                risk.release(o.accountId, s, o.side, o);
            }
            o.status = RestingOrder.STATUS_CANCELED;
            o.remaining = 0;
            o.riskReason = (byte) RiskReason.FOK_UNFILLABLE.ordinal();
            index(o);
            terminal(o);
            out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CANCEL, true, lastPxBySecurity[s],
                e.ingressNanos);
            return;
        }
        index(o);
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CREATE, true, lastPxBySecurity[s],
            e.ingressNanos);
        cross(o, book, e);
        if (o.remaining == 0) {
            return;
        }
        if (o.tif == OrderTypes.FOK) {
            // Invariant breach: the preflight promised a full fill. Never a valid partial FOK —
            // counted, visible on /health, and a test asserts it stays 0.
            fokInvariantBreaches++;
            o.riskReason = (byte) RiskReason.FOK_UNFILLABLE.ordinal();
            cancelRemainder(o, e);
        } else if (market || o.tif == OrderTypes.IOC) {
            cancelRemainder(o, e);
        } else {
            if (o.orderType == OrderTypes.ICEBERG) {
                o.displayed = Math.min(o.displayQty, o.remaining);   // FR-OT20: a NEW tranche
            }
            restAt(o, book, restSlot);
        }
    }

    /**
     * FR-OT31: read-only walk of exactly the matching order execution uses. Own-account orders add
     * nothing (STP cancels them); other accounts add their full remaining, hidden included, because
     * replenishment only moves a tranche to the tail of the SAME level the walk is still in; pegs
     * add at their current price because repricing waits for the aggressor. Fills after the
     * admission decision only consume the aggressor's reservation, so nothing can refuse later.
     */
    private boolean fokFeasible(RestingOrder a, LimitBook book) {
        final boolean buy = a.side == InputEvent.SIDE_BUY;
        final byte restingSide = buy ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY;
        final boolean market = a.limitPx <= Px.NONE;
        long need = a.quantity;
        int slot = buy ? book.bestAskSlot() : book.bestBidSlot();
        while (slot != LimitBook.NO_LEVEL && need > 0) {
            final long levelPx = book.priceAt(slot);
            if (!market && (buy ? levelPx > a.limitPx : levelPx < a.limitPx)) {
                break;
            }
            for (RestingOrder r = book.headAt(restingSide, slot); r != null; r = r.bookNext) {
                if (r.accountId != a.accountId) {
                    need -= r.remaining;
                }
            }
            slot = buy ? book.occupiedAbove(restingSide, slot) : book.occupiedBelow(restingSide, slot);
        }
        return need <= 0;
    }

    /** STOP / STOP_LIMIT / TRAILING_STOP admission to PENDING_TRIGGER (FR-OT13, 16, 18, 27). */
    private void admitPending(RestingOrder o, InputEvent e, LimitBook book) {
        final int s = o.securityId;
        final long reservePx;
        if (o.orderType == OrderTypes.TRAILING_STOP) {
            if (!hasTraded[s]) {
                rejectTyped(o, e, RiskReason.TRAIL_REFERENCE_MISSING);   // FR-OT18, decision D7
                return;
            }
            o.watermark = lastTradePx[s];
            final long stop = OrderTypes.trailingStop(o.side, o.trailMode, o.trailValue, o.watermark,
                book.tickTicks());
            if (stop <= 0L) {
                rejectTyped(o, e, RiskReason.TRAIL_INVALID);   // FR-OT41
                return;
            }
            o.stopPx = stop;
            reservePx = stop;   // D5 (approved): the stop level at admission
        } else {
            if (!book.onGrid(e.stopPx)
                || (o.orderType == OrderTypes.STOP_LIMIT && !book.onGrid(e.limitPx))) {
                rejectTyped(o, e, RiskReason.INVALID);
                return;
            }
            o.stopPx = e.stopPx;
            if (hasTraded[s] && stopThrough(o, lastTradePx[s])) {
                rejectTyped(o, e, RiskReason.STOP_ALREADY_TRIGGERED);   // FR-OT13, decision D1
                return;
            }
            o.limitPx = o.orderType == OrderTypes.STOP_LIMIT ? e.limitPx : Px.NONE;
            // D5 (approved): a STOP reserves at its stop price; a STOP_LIMIT at its limit.
            reservePx = o.orderType == OrderTypes.STOP_LIMIT ? e.limitPx : e.stopPx;
        }
        if (pendingTotal >= pendingCapacity) {
            rejectTyped(o, e, RiskReason.CAPACITY);   // NFR-OT03
            return;
        }
        if (risk != null) {
            final RiskReason decision = risk.decideAndReserve(e.clientOrderKey(), 0L, o.orderRef,
                o.accountId, s, o.side, positions.get(o.accountId, s), o.quantity, reservePx,
                e.eventTimeMillis, o);
            if (decision != RiskReason.ACCEPTED) {
                o.riskReason = (byte) decision.ordinal();
                reject(o, e, null);
                return;
            }
        }
        o.status = RestingOrder.STATUS_PENDING_TRIGGER;
        index(o);
        linkPending(o);
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CREATE, true, lastPxBySecurity[s],
            e.ingressNanos);
    }

    private static boolean stopThrough(RestingOrder o, long printPx) {
        return stopThrough(o.side, o.stopPx, printPx);
    }

    /** The one trigger predicate: admission, replace and the print all judge equality as through. */
    private static boolean stopThrough(byte side, long stopPx, long printPx) {
        return side == InputEvent.SIDE_BUY ? printPx >= stopPx : printPx <= stopPx;
    }

    /** FR-OT13/29: a stop the CURRENT trade reference has already reached cannot be (re)set. */
    private boolean stopAlreadyThrough(int s, byte side, long stopPx) {
        return hasTraded[s] && stopThrough(side, stopPx, lastTradePx[s]);
    }

    /** PEGGED (local book) admission (FR-OT23, 24, 39). */
    private void admitPeg(RestingOrder o, InputEvent e, LimitBook book) {
        final int s = o.securityId;
        if (!book.onGrid(e.limitPx)) {
            rejectTyped(o, e, RiskReason.INVALID);
            return;
        }
        if (pegTotal >= pegCapacity) {
            rejectTyped(o, e, RiskReason.CAPACITY);
            return;
        }
        o.capPx = e.limitPx;
        final long bid = nonPegBest(book, InputEvent.SIDE_BUY);
        final long ask = nonPegBest(book, InputEvent.SIDE_SELL);
        final long target = pegTarget(o, book, bid, ask);
        if (target == PEG_NO_REFERENCE) {
            rejectTyped(o, e, RiskReason.PEG_REFERENCE_MISSING);
            return;
        }
        if (target == PEG_INVALID_PRICE) {
            rejectTyped(o, e, RiskReason.PEG_PRICE_INVALID);
            return;
        }
        final int slot = bandSlot(book, s, target, null, e);
        if (slot == LimitBook.NO_LEVEL) {
            rejectTyped(o, e, RiskReason.PRICE_COLLAR);
            return;
        }
        // FR-OT39: a buy cap is a ceiling; a sell cap is a floor and bounds nothing from above.
        o.riskPx = o.side == InputEvent.SIDE_BUY ? o.capPx : Math.max(o.capPx, target);
        if (risk != null) {
            final RiskReason decision = risk.decideAndReserve(e.clientOrderKey(), 0L, o.orderRef,
                o.accountId, s, o.side, positions.get(o.accountId, s), o.quantity, o.riskPx,
                e.eventTimeMillis, o);
            if (decision != RiskReason.ACCEPTED) {
                o.riskReason = (byte) decision.ordinal();
                reject(o, e, null);
                return;
            }
        }
        if (pegHead[s] == null) {
            // The first peg of a security adopts the current reference; while pegs exist the
            // cascade loop keeps it current (FR-OT25).
            pegRefBid[s] = bid;
            pegRefAsk[s] = ask;
        }
        o.limitPx = target;
        index(o);
        linkPeg(o);
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CREATE, true, lastPxBySecurity[s],
            e.ingressNanos);
        cross(o, book, e);   // only an opposite MIDPOINT peg can be reached (FR-OT23 consequence)
        if (o.remaining > 0) {
            restAt(o, book, slot);
        }
    }

    // ----- stores ------------------------------------------------------------------------------

    private void linkPending(RestingOrder o) {
        linkSorted(o, pendHead, pendTail, RestingOrder.STORE_PENDING);
        pendCount[o.securityId]++;
        pendingTotal++;
    }

    private void linkPeg(RestingOrder o) {
        linkSorted(o, pegHead, pegTail, RestingOrder.STORE_PEG);
        pegCount[o.securityId]++;
        pegTotal++;
    }

    /** Insert in ascending admission sequence (FR-OT11). Appends in the common case. */
    private static void linkSorted(RestingOrder o, RestingOrder[] head, RestingOrder[] tail, byte store) {
        final int s = o.securityId;
        RestingOrder after = tail[s];
        while (after != null && after.admissionSeq > o.admissionSeq) {
            after = after.storePrev;
        }
        o.store = store;
        o.storePrev = after;
        o.storeNext = after == null ? head[s] : after.storeNext;
        if (after == null) {
            head[s] = o;
        } else {
            after.storeNext = o;
        }
        if (o.storeNext == null) {
            tail[s] = o;
        } else {
            o.storeNext.storePrev = o;
        }
    }

    private void unlinkStore(RestingOrder o) {
        if (o.store == RestingOrder.STORE_NONE) {
            return;
        }
        final int s = o.securityId;
        final boolean pending = o.store == RestingOrder.STORE_PENDING;
        final RestingOrder[] head = pending ? pendHead : pegHead;
        final RestingOrder[] tail = pending ? pendTail : pegTail;
        if (o.storePrev == null) {
            head[s] = o.storeNext;
        } else {
            o.storePrev.storeNext = o.storeNext;
        }
        if (o.storeNext == null) {
            tail[s] = o.storePrev;
        } else {
            o.storeNext.storePrev = o.storePrev;
        }
        o.storeNext = null;
        o.storePrev = null;
        o.store = RestingOrder.STORE_NONE;
        if (pending) {
            pendCount[s]--;
            pendingTotal--;
        } else {
            pegCount[s]--;
            pegTotal--;
        }
    }

    // ----- prints, latching and the trigger queue (FR-OT09..12, 19) -----------------------------

    /**
     * One qualifying print. Walks the security's pending store in admission order, so orders
     * latched by the same print enter the queue in admission order regardless of side (FR-OT11).
     * A trailing stop is tested against its CURRENT level first and its watermark updated only if
     * it did not latch (FR-OT19). A ratchet that moves the stop LEVEL is published (F3, below).
     * Allocation-free; O(pending in this security).
     */
    // ponytail: linear scan of the security's pending list per print; a price-indexed trigger
    // ladder if pending counts per security ever grow large.
    private void recordPrint(int s, long px, InputEvent e) {
        lastTradePx[s] = px;
        hasTraded[s] = true;
        RestingOrder o = pendHead[s];
        while (o != null) {
            final RestingOrder next = o.storeNext;
            if (stopThrough(o, px)) {
                unlinkStore(o);
                triggerQueue[qTail++] = o;   // capacity pendingCapacity + 1: cannot overflow
            } else if (o.orderType == OrderTypes.TRAILING_STOP) {
                updateWatermark(o, px, e);
            }
            o = next;
        }
    }

    /**
     * F3 (RI-07): when the ratchet moves the stop LEVEL, publish it. FR-OT33 makes the read model's
     * stopprice the order's CURRENT level; without this update it kept the level of the order's
     * last event (seen live: 101.5 shown while the engine held 102). The order is never the input
     * being applied, so the update is FLAG_RESTING_UPDATE (the gateway must not count it as this
     * command's answer), like a peg reprice. A watermark move that leaves the grid-rounded level
     * unchanged publishes nothing. Output only: no state beyond what this method already set.
     */
    private void updateWatermark(RestingOrder o, long px, InputEvent e) {
        final boolean sell = o.side == InputEvent.SIDE_SELL;
        if (sell ? px <= o.watermark : px >= o.watermark) {
            return;
        }
        final long stop = OrderTypes.trailingStop(o.side, o.trailMode, o.trailValue, px,
            booksBySecurity[o.securityId].tickTicks());
        if (stop > 0L) {   // a sell stop only rises and a buy stop only falls; -1 is unreachable
            o.watermark = px;
            if (stop != o.stopPx) {
                o.stopPx = stop;
                o.updatedAtMillis = e.eventTimeMillis;
                out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_RESTING_UPDATE, true,
                    lastPxBySecurity[o.securityId], e.ingressNanos);
            }
        }
    }

    /** FR-OT12: drain FIFO; a drained order's own prints append to the tail. */
    private void drainTriggers(InputEvent e) {
        while (qHead < qTail) {
            final RestingOrder o = triggerQueue[qHead];
            triggerQueue[qHead++] = null;
            fireTrigger(o, e);
        }
        qHead = 0;
        qTail = 0;
    }

    /**
     * Convert a latched order: collar (STOP_LIMIT), then the internal risk revalidation of FR-OT38
     * — release, then decide with client key 0 so every check really runs and nothing enters the
     * idempotency store — then execute as an aggressor. Refusal ends REJECTED with nothing
     * reserved. D5 (approved): a STOP / TRAILING_STOP is re-decided as a MARKET order at
     * marketValidationPx — the mark the latching print just set — which does NOT bound the fill
     * price (FR-OT16 limitation).
     */
    private void fireTrigger(RestingOrder o, InputEvent e) {
        final int s = o.securityId;
        final LimitBook book = bookFor(s);
        final boolean stopLimit = o.orderType == OrderTypes.STOP_LIMIT;
        if (risk != null) {
            risk.release(o.accountId, s, o.side, o);
        }
        int slot = LimitBook.NO_LEVEL;
        if (stopLimit) {
            slot = bandSlot(book, s, o.limitPx, null, e);
            if (slot == LimitBook.NO_LEVEL) {
                rejectLatched(o, e, RiskReason.PRICE_COLLAR);
                return;
            }
        }
        if (risk != null) {
            final long px = stopLimit ? o.limitPx : marketValidationPx(book, s, o.side);
            final RiskReason decision = risk.decideAndReserve(0L, 0L, o.orderRef, o.accountId, s,
                o.side, positions.get(o.accountId, s), o.remaining, px, e.eventTimeMillis, o);
            if (decision != RiskReason.ACCEPTED) {
                rejectLatched(o, e, decision);
                return;
            }
        }
        ordersTriggered++;
        o.triggered = true;
        o.status = RestingOrder.STATUS_NEW;
        if (!stopLimit) {
            o.limitPx = Px.NONE;   // STOP and TRAILING_STOP execute as MARKET
        }
        o.updatedAtMillis = e.eventTimeMillis;
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_TRIGGERED | OutputEvent.FLAG_RESTING_UPDATE,
            true, lastPxBySecurity[s], e.ingressNanos);
        cross(o, book, e);
        if (o.remaining > 0) {
            if (stopLimit) {
                restAt(o, book, slot);
            } else {
                cancelRemainder(o, e);
            }
        }
    }

    private void rejectLatched(RestingOrder o, InputEvent e, RiskReason reason) {
        o.riskReason = (byte) reason.ordinal();
        o.status = RestingOrder.STATUS_REJECTED;
        o.remaining = 0;
        o.updatedAtMillis = e.eventTimeMillis;
        terminal(o);
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_REJECT | OutputEvent.FLAG_RESTING_UPDATE,
            true, lastPxBySecurity[o.securityId], e.ingressNanos);
    }

    // ----- pegs (FR-OT23..25, 39) ---------------------------------------------------------------

    private static final long PEG_NO_REFERENCE = -1L;
    private static final long PEG_INVALID_PRICE = 0L;

    /** Best price on {@code side} over NON-pegged orders only (FR-OT23), or Px.NONE. */
    // ponytail: skips peg-only levels one by one; fine while pegs are a minority of a book.
    private static long nonPegBest(LimitBook book, byte side) {
        final boolean bid = side == InputEvent.SIDE_BUY;
        int slot = bid ? book.bestBidSlot() : book.bestAskSlot();
        while (slot != LimitBook.NO_LEVEL) {
            for (RestingOrder o = book.headAt(side, slot); o != null; o = o.bookNext) {
                if (o.orderType != OrderTypes.PEGGED) {
                    return book.priceAt(slot);
                }
            }
            slot = bid ? book.occupiedBelow(side, slot) : book.occupiedAbove(side, slot);
        }
        return Px.NONE;
    }

    /**
     * The peg's price from a reference: PRIMARY same-side best, or MIDPOINT rounded passively
     * (buy down, sell up); plus the passive offset; clipped by the cap. PEG_NO_REFERENCE when the
     * reference is absent, PEG_INVALID_PRICE when the result is outside (0, MAX_PRICE_TICKS].
     */
    private static long pegTarget(RestingOrder o, LimitBook book, long bid, long ask) {
        final long tick = book.tickTicks();
        final boolean buy = o.side == InputEvent.SIDE_BUY;
        final long ref;
        if (o.pegRef == OrderTypes.PEG_PRIMARY) {
            ref = buy ? bid : ask;
            if (ref == Px.NONE) {
                return PEG_NO_REFERENCE;
            }
        } else {
            if (bid == Px.NONE || ask == Px.NONE) {
                return PEG_NO_REFERENCE;
            }
            final long sum = bid / tick + ask / tick;
            ref = (buy ? Math.floorDiv(sum, 2L) : -Math.floorDiv(-sum, 2L)) * tick;
        }
        long px = ref + (long) o.pegOffset * tick;   // |offset| <= 10 000, tick <= 1 000: no overflow
        px = buy ? Math.min(px, o.capPx) : Math.max(px, o.capPx);
        if (!book.onGrid(px)) {   // a cap left off-grid by a later re-tick: round passively
            px = buy ? OrderTypes.floorToGrid(px, tick) : OrderTypes.ceilToGrid(px, tick);
        }
        return px > 0L && px <= OrderTypes.MAX_PRICE_TICKS ? px : PEG_INVALID_PRICE;
    }

    /** FR-OT25: walk the security's pegs in admission order against the stored reference. */
    private void repricePegs(int s, LimitBook book, InputEvent e) {
        final int round = ++roundId;
        RestingOrder p = pegHead[s];
        while (p != null) {
            if (p.repriceMark == round) {
                p = p.storeNext;
                continue;
            }
            p.repriceMark = round;
            final RestingOrder next = p.storeNext;
            repriceOne(p, book, e);
            // A reprice can fill or STP-cancel other pegs. If the one after us left the list,
            // restart from the head; the marks skip everything already walked this round.
            p = next != null && next.store == RestingOrder.STORE_PEG ? next : pegHead[s];
        }
    }

    private void repriceOne(RestingOrder p, LimitBook book, InputEvent e) {
        final int s = p.securityId;
        final long t = pegTarget(p, book, pegRefBid[s], pegRefAsk[s]);
        if (t <= 0L) {
            suspend(p, book, e, RestingOrder.SUSPEND_REFERENCE, RiskReason.ACCEPTED);
            return;
        }
        if (p.status != RestingOrder.STATUS_SUSPENDED && p.isResting() && p.limitPx == t) {
            return;   // unchanged price keeps its priority
        }
        final int slot = book.slotFor(t);
        if (slot == LimitBook.NO_LEVEL) {
            suspend(p, book, e, RestingOrder.SUSPEND_REFERENCE, RiskReason.ACCEPTED);
            return;
        }
        if (p.side == InputEvent.SIDE_SELL && t > p.riskPx && risk != null) {
            // FR-OT39: executable sell exposure grows. Re-reserve at t BEFORE placing it there.
            final long heldNotional = p.reservedNotional;
            final int heldQty = p.reservedQty;
            risk.release(p.accountId, s, p.side, p);
            final RiskReason decision = risk.decideAndReserve(0L, 0L, p.orderRef, p.accountId, s,
                p.side, positions.get(p.accountId, s), p.remaining, t, e.eventTimeMillis, p);
            if (decision != RiskReason.ACCEPTED) {
                risk.reaccumulateReservation(p.accountId, s, p.side, heldNotional, heldQty);
                p.setReservation(heldNotional, heldQty);
                suspend(p, book, e, RestingOrder.SUSPEND_RISK, decision);
                return;
            }
            p.riskPx = t;
        }
        if (p.isResting()) {
            book.remove(p);
        }
        pegReprices++;
        p.limitPx = t;
        p.suspendReason = RestingOrder.SUSPEND_NONE;
        p.riskReason = (byte) RiskReason.ACCEPTED.ordinal();
        p.status = p.remaining < p.quantity ? RestingOrder.STATUS_PARTIALLY_FILLED : RestingOrder.STATUS_NEW;
        p.updatedAtMillis = e.eventTimeMillis;
        out.emitOrderUpdate(p, e.seq, OutputEvent.FLAG_RESTING_UPDATE, true, lastPxBySecurity[s],
            e.ingressNanos);
        cross(p, book, e);
        if (p.remaining > 0) {
            restAt(p, book, slot);
        }
    }

    private void suspend(RestingOrder p, LimitBook book, InputEvent e, byte why, RiskReason reason) {
        final byte reasonOrdinal = (byte) reason.ordinal();
        if (p.status == RestingOrder.STATUS_SUSPENDED && p.suspendReason == why
            && p.riskReason == reasonOrdinal) {
            return;
        }
        if (p.isResting()) {
            book.remove(p);
        }
        p.status = RestingOrder.STATUS_SUSPENDED;
        p.suspendReason = why;
        p.riskReason = reasonOrdinal;
        p.updatedAtMillis = e.eventTimeMillis;
        out.emitOrderUpdate(p, e.seq, OutputEvent.FLAG_RESTING_UPDATE, true,
            lastPxBySecurity[p.securityId], e.ingressNanos);
    }

    // ----- the cascade loop (FR-OT26, FR-OT40) --------------------------------------------------

    /**
     * Run the security's cascade to a fixpoint. Each round (1) reprices the pegs when the
     * non-pegged reference moved, then (2) drains the trigger queue TO EMPTY. A further round runs
     * only if the reference moved again. Fast path: no pegs and nothing latched — two reads, which
     * is all a legacy-only book ever pays.
     */
    private void settle(int s, InputEvent e) {
        if (qHead == qTail && pegHead[s] == null) {
            return;
        }
        final int saved = unsolicited;
        unsolicited = OutputEvent.FLAG_RESTING_UPDATE;
        final LimitBook book = bookFor(s);
        final int limit = roundLimit(s, book);
        int rounds = 0;
        while (true) {
            boolean refMoved = false;
            long bid = Px.NONE;
            long ask = Px.NONE;
            if (pegHead[s] != null) {
                bid = nonPegBest(book, InputEvent.SIDE_BUY);
                ask = nonPegBest(book, InputEvent.SIDE_SELL);
                refMoved = bid != pegRefBid[s] || ask != pegRefAsk[s];
            }
            if (!refMoved && qHead == qTail) {
                break;
            }
            if (rounds == limit) {
                exhaust(s, book, e);
                break;
            }
            rounds++;
            if (refMoved) {
                pegRefBid[s] = bid;
                pegRefAsk[s] = ask;
                repricePegs(s, book, e);
            }
            drainTriggers(e);
        }
        lastSettleRounds = rounds;
        if (rounds > maxSettleRounds) {
            maxSettleRounds = rounds;
        }
        unsolicited = saved;
    }

    /**
     * FR-OT26: the proven per-step bound from replicated state — P = pending in this security plus
     * already latched, N = orders in its book — rounds &lt;= 3P + N + 6. A positive configured
     * limit can only LOWER it (tests do, to reach FR-OT40).
     */
    private int roundLimit(int s, LimitBook book) {
        final long p = (long) pendCount[s] + (qTail - qHead);
        final long bound = 3L * p + book.openOrders() + 6L;
        final int proven = (int) Math.min(Integer.MAX_VALUE, bound);
        return roundLimitOverride > 0 ? Math.min(roundLimitOverride, proven) : proven;
    }

    /**
     * FR-OT40 (review r3 item 2): the limit is checked at the top of a round, after the previous
     * round drained the queue to empty, so the queue is empty here by construction. What remains
     * is a reference that moved again: the security's pegs could be left at a stale, crossed
     * price, so they are CANCELED CASCADE_LIMIT in admission order. Committed fills stand; pending
     * (unlatched) stops stay pending; the member keeps running.
     */
    private void exhaust(int s, LimitBook book, InputEvent e) {
        cascadeLimitEvents++;
        while (qHead < qTail) {   // defensive only: unreachable under drain-to-empty
            final RestingOrder o = triggerQueue[qHead];
            triggerQueue[qHead++] = null;
            cancelByVenue(o, e, RiskReason.CASCADE_LIMIT);
        }
        qHead = 0;
        qTail = 0;
        RestingOrder p = pegHead[s];
        while (p != null) {
            final RestingOrder next = p.storeNext;
            cancelByVenue(p, e, RiskReason.CASCADE_LIMIT);
            p = next;
        }
    }

    // ----- typed replace (FR-OT29) --------------------------------------------------------------

    /**
     * Replace of a pending STOP / STOP_LIMIT / TRAILING_STOP, an ICEBERG or a PEGGED order. Same
     * ADR-058 shape as the limit path: everything that can refuse is decided first, the reservation
     * is released and re-decided with the replace's key, and a refusal restores it exactly and
     * leaves the order bit-identical.
     */
    private void onTypedReplace(InputEvent e, RestingOrder o, long px) {
        final int s = o.securityId;
        final LimitBook book = bookFor(s);
        final int filled = o.quantity - o.remaining;
        final int newRemaining = e.qty - filled;
        final byte type = o.orderType;
        final boolean pending = o.status == RestingOrder.STATUS_PENDING_TRIGGER;
        if (OrderTypes.isTriggered(type) && !pending) {
            out.emitRequestRejected(o, e.seq, (byte) RiskReason.INVALID.ordinal(), px, e.ingressNanos);
            return;
        }
        final byte pegRef = type == OrderTypes.PEGGED ? o.pegRef : 0;
        if (e.qty <= 0 || newRemaining <= 0
            || OrderTypes.validate(type, o.tif, o.side, e.qty, e.limitPx, e.stopPx, e.displayQty,
                pegRef, e.pegOffset, e.trailMode, e.trailValue) != OrderTypes.OK) {
            out.emitRequestRejected(o, e.seq, (byte) RiskReason.INVALID.ordinal(), px, e.ingressNanos);
            return;
        }
        long newStop = 0L;
        long reservePx;
        long newTarget = 0L;
        switch (type) {
            case OrderTypes.STOP, OrderTypes.STOP_LIMIT -> {
                if (!book.onGrid(e.stopPx) || (type == OrderTypes.STOP_LIMIT && !book.onGrid(e.limitPx))) {
                    out.emitRequestRejected(o, e.seq, (byte) RiskReason.INVALID.ordinal(), px, e.ingressNanos);
                    return;
                }
                newStop = e.stopPx;
                if (stopAlreadyThrough(s, o.side, newStop)) {
                    out.emitRequestRejected(o, e.seq, (byte) RiskReason.STOP_ALREADY_TRIGGERED.ordinal(),
                        px, e.ingressNanos);
                    return;
                }
                reservePx = type == OrderTypes.STOP_LIMIT ? e.limitPx : newStop;
            }
            case OrderTypes.TRAILING_STOP -> {
                newStop = OrderTypes.trailingStop(o.side, e.trailMode, e.trailValue, o.watermark,
                    book.tickTicks());
                if (newStop <= 0L) {
                    out.emitRequestRejected(o, e.seq, (byte) RiskReason.TRAIL_INVALID.ordinal(), px,
                        e.ingressNanos);
                    return;
                }
                // Review I2: the watermark is the best print SINCE admission, not the current one,
                // so a tighter trail can land a stop the market is already through. Refused here,
                // before the reservation moves, exactly as a STOP replace is (FR-OT29).
                if (stopAlreadyThrough(s, o.side, newStop)) {
                    out.emitRequestRejected(o, e.seq, (byte) RiskReason.STOP_ALREADY_TRIGGERED.ordinal(),
                        px, e.ingressNanos);
                    return;
                }
                reservePx = newStop;
            }
            case OrderTypes.ICEBERG -> {
                if (!book.onGrid(e.limitPx) || e.displayQty >= e.qty) {
                    out.emitRequestRejected(o, e.seq, (byte) RiskReason.INVALID.ordinal(), px, e.ingressNanos);
                    return;
                }
                if (bandSlot(book, s, e.limitPx, o, e) == LimitBook.NO_LEVEL) {
                    out.emitRequestRejected(o, e.seq, (byte) RiskReason.PRICE_COLLAR.ordinal(), px,
                        e.ingressNanos);
                    return;
                }
                reservePx = e.limitPx;
            }
            case OrderTypes.PEGGED -> {
                if (!book.onGrid(e.limitPx)) {
                    out.emitRequestRejected(o, e.seq, (byte) RiskReason.INVALID.ordinal(), px, e.ingressNanos);
                    return;
                }
                final long savedCap = o.capPx;
                final int savedOffset = o.pegOffset;
                o.capPx = e.limitPx;
                o.pegOffset = e.pegOffset;
                newTarget = pegTarget(o, book, nonPegBest(book, InputEvent.SIDE_BUY),
                    nonPegBest(book, InputEvent.SIDE_SELL));
                o.capPx = savedCap;
                o.pegOffset = savedOffset;
                reservePx = o.side == InputEvent.SIDE_BUY ? e.limitPx
                    : Math.max(e.limitPx, newTarget > 0L ? newTarget : e.limitPx);
            }
            default -> {
                out.emitRequestRejected(o, e.seq, (byte) RiskReason.INVALID.ordinal(), px, e.ingressNanos);
                return;
            }
        }
        if (risk != null) {
            final long heldNotional = o.reservedNotional;
            final int heldQty = o.reservedQty;
            risk.release(o.accountId, s, o.side, o);
            final RiskReason decision = risk.decideAndReserve(e.clientOrderKey(), 0L, o.orderRef,
                o.accountId, s, o.side, positions.get(o.accountId, s), newRemaining, reservePx,
                e.eventTimeMillis, o);
            if (decision != RiskReason.ACCEPTED) {
                risk.reaccumulateReservation(o.accountId, s, o.side, heldNotional, heldQty);
                o.setReservation(heldNotional, heldQty);
                out.emitRequestRejected(o, e.seq, (byte) decision.ordinal(), px, e.ingressNanos);
                return;
            }
        }
        // ---- committed from here ----
        o.updatedAtMillis = e.eventTimeMillis;
        switch (type) {
            case OrderTypes.STOP, OrderTypes.STOP_LIMIT, OrderTypes.TRAILING_STOP -> {
                o.quantity = e.qty;
                o.remaining = newRemaining;
                o.stopPx = newStop;
                if (type == OrderTypes.STOP_LIMIT) {
                    o.limitPx = e.limitPx;
                }
                if (type == OrderTypes.TRAILING_STOP) {
                    o.trailMode = e.trailMode;
                    o.trailValue = e.trailValue;
                }
                out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_REPLACE, true, px, e.ingressNanos);
            }
            case OrderTypes.ICEBERG -> replaceIceberg(e, o, book, newRemaining, px);
            default -> replacePeg(e, o, book, newRemaining, newTarget, reservePx, px);
        }
    }

    /** FR-OT20/29 iceberg replace with conservation: quantity = filled + displayed + hidden. */
    private void replaceIceberg(InputEvent e, RestingOrder o, LimitBook book, int newRemaining, long px) {
        final boolean keep = o.isResting() && e.limitPx == o.limitPx && newRemaining < o.remaining
            && e.displayQty <= o.displayQty;
        if (keep) {
            final int newDisplayed = Math.min(o.displayed, Math.min(e.displayQty, newRemaining));
            book.reduce(o, o.displayed - newDisplayed);   // keeps its queue slot
            o.displayed = newDisplayed;
            o.quantity = e.qty;
            o.remaining = newRemaining;
            o.displayQty = e.displayQty;
            out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_REPLACE, true, px, e.ingressNanos);
            return;
        }
        if (o.isResting()) {
            book.remove(o);
        }
        final int filled = o.quantity - o.remaining;
        o.quantity = e.qty;
        o.remaining = newRemaining;
        o.displayQty = e.displayQty;
        o.displayed = 0;   // an aggressor shows nothing; display applies once it rests
        o.limitPx = e.limitPx;
        o.status = filled > 0 ? RestingOrder.STATUS_PARTIALLY_FILLED : RestingOrder.STATUS_NEW;
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_REPLACE, true, px, e.ingressNanos);
        cross(o, book, e);
        if (o.remaining > 0) {
            o.displayed = Math.min(o.displayQty, o.remaining);   // a NEW tranche at the tail
            restAt(o, book, book.slotFor(o.limitPx));
        }
    }

    private void replacePeg(InputEvent e, RestingOrder o, LimitBook book, int newRemaining,
                            long newTarget, long riskPx, long px) {
        final boolean keep = o.isResting() && newTarget == o.limitPx && newRemaining < o.remaining;
        o.capPx = e.limitPx;
        o.pegOffset = e.pegOffset;
        o.riskPx = riskPx;
        if (keep) {
            book.reduce(o, o.remaining - newRemaining);
            o.quantity = e.qty;
            o.remaining = newRemaining;
            out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_REPLACE, true, px, e.ingressNanos);
            return;
        }
        if (o.isResting()) {
            book.remove(o);
        }
        o.quantity = e.qty;
        o.remaining = newRemaining;
        final int slot = newTarget > 0L ? book.slotFor(newTarget) : LimitBook.NO_LEVEL;
        if (slot == LimitBook.NO_LEVEL) {
            o.status = RestingOrder.STATUS_SUSPENDED;
            o.suspendReason = RestingOrder.SUSPEND_REFERENCE;
            out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_REPLACE, true, px, e.ingressNanos);
            return;
        }
        o.limitPx = newTarget;
        o.suspendReason = RestingOrder.SUSPEND_NONE;
        o.riskReason = (byte) RiskReason.ACCEPTED.ordinal();
        o.status = o.remaining < o.quantity ? RestingOrder.STATUS_PARTIALLY_FILLED : RestingOrder.STATUS_NEW;
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_REPLACE, true, px, e.ingressNanos);
        cross(o, book, e);
        if (o.remaining > 0) {
            restAt(o, book, slot);
        }
    }

    // ----- business-date lifecycle (FR-OT08, FR-OT37) -------------------------------------------

    /**
     * SESSION_START / DAY_END. Sequenced, clock-free, identical on every member and replay. The
     * outcome ({@link #DAY_APPLIED}, {@link #DAY_STALE}, {@link #DAY_OUT_OF_SEQUENCE}) is read by
     * the service for its ack; the PRE_OPEN queue's DAY entries are the service's to expire.
     */
    private void onBusinessDay(InputEvent e) {
        final int date = e.qty;
        if (e.side == InputEvent.BUSINESS_DAY_START) {
            if (date > businessDate) {
                expireDayOrders(e);   // a missing or late DAY_END for the old date is harmless
                businessDate = date;
                dayOpen = true;
                lastBusinessDayOutcome = DAY_APPLIED;
            } else {
                lastBusinessDayOutcome = DAY_STALE;
            }
            return;
        }
        if (date == businessDate && dayOpen) {
            expireDayOrders(e);
            dayOpen = false;
            lastBusinessDayOutcome = DAY_APPLIED;
        } else if (date > businessDate) {
            lastBusinessDayOutcome = DAY_OUT_OF_SEQUENCE;
        } else {
            lastBusinessDayOutcome = DAY_STALE;
        }
    }

    /** Cold path: every live DAY order, ascending orderRef, then each touched security settles. */
    private void expireDayOrders(InputEvent e) {
        final int[] refs = snapshotOrderRefsAscending();
        final boolean[] touched = new boolean[booksBySecurity.length];
        final int saved = unsolicited;
        unsolicited = OutputEvent.FLAG_RESTING_UPDATE;
        for (final int ref : refs) {
            final RestingOrder o = lookup(ref);
            if (o != null && o.isOpen() && o.tif == OrderTypes.DAY) {
                touched[o.securityId] = true;
                cancelByVenue(o, e, RiskReason.DAY_EXPIRED);
            }
        }
        unsolicited = saved;
        for (int s = 0; s < touched.length; s++) {
            if (touched[s]) {
                settle(s, e);
            }
        }
    }

    public int businessDate() {
        return businessDate;
    }

    public boolean dayOpen() {
        return dayOpen;
    }

    public byte lastBusinessDayOutcome() {
        return lastBusinessDayOutcome;
    }

    /** Restore / sandbox reset: adopt the replicated business date (FR-OT37). */
    public void bootstrapBusinessDay(int date, boolean open) {
        businessDate = date;
        dayOpen = open;
    }

    // ----- order-type snapshot support (format 11, FR-OT35) ------------------------------------

    public static final int ORDER_EXT_TUPLE_LENGTH = 17;
    private final long[] stagedExt = new long[ORDER_EXT_TUPLE_LENGTH];
    private boolean extStaged;

    /** True for orders that carry typed state (written as T_ORDER_EXT). */
    public boolean copyOrderExtension(final int orderRef, final long[] t) {
        final RestingOrder o = lookup(orderRef);
        if (o == null || (o.orderType == OrderTypes.LEGACY && o.tif == OrderTypes.TIF_NONE)) {
            return false;
        }
        t[0] = o.orderRef;
        t[1] = o.orderType;
        t[2] = o.tif;
        t[3] = o.stopPx;
        t[4] = o.displayQty;
        t[5] = o.displayed;
        t[6] = o.pegRef;
        t[7] = o.pegOffset;
        t[8] = o.capPx;
        t[9] = o.riskPx;
        t[10] = o.suspendReason;
        t[11] = o.trailMode;
        t[12] = o.trailValue;
        t[13] = o.watermark;
        t[14] = o.sessionDate;
        t[15] = o.admissionSeq;
        t[16] = o.triggered ? 1L : 0L;
        return true;
    }

    /** Restore: the next bootstrapOrder for {@code ext[0]} takes these typed fields. */
    public void stageOrderExtension(final long[] ext) {
        System.arraycopy(ext, 0, stagedExt, 0, ORDER_EXT_TUPLE_LENGTH);
        extStaged = true;
    }

    private void applyStagedExtension(RestingOrder o) {
        if (!extStaged) {
            return;
        }
        if (stagedExt[0] != o.orderRef) {
            throw new IllegalStateException("restore corrupt: order extension without its order row");
        }
        extStaged = false;
        o.orderType = (byte) stagedExt[1];
        o.tif = (byte) stagedExt[2];
        o.stopPx = stagedExt[3];
        o.displayQty = (int) stagedExt[4];
        o.displayed = (int) stagedExt[5];
        o.pegRef = (byte) stagedExt[6];
        o.pegOffset = (int) stagedExt[7];
        o.capPx = stagedExt[8];
        o.riskPx = stagedExt[9];
        o.suspendReason = (byte) stagedExt[10];
        o.trailMode = (byte) stagedExt[11];
        o.trailValue = stagedExt[12];
        o.watermark = stagedExt[13];
        o.sessionDate = (int) stagedExt[14];
        o.admissionSeq = stagedExt[15];
        o.triggered = stagedExt[16] != 0L;
    }

    /**
     * Snapshot order for OPEN rows: resting orders in book-append order (so restore re-creates
     * every level's exact FIFO, including orders that replenished, repriced or were replaced to a
     * tail), then open non-resting orders (pending, suspended) in ascending ref.
     */
    public int[] snapshotOpenRefsInRestoreOrder() {
        final java.util.List<RestingOrder> resting = new java.util.ArrayList<>();
        final java.util.List<RestingOrder> held = new java.util.ArrayList<>();
        for (final RestingOrder o : ordersByRef.values()) {
            if (o.orderRef == 0 || !o.isOpen()) {
                continue;
            }
            (o.isResting() ? resting : held).add(o);
        }
        resting.sort((a, b) -> Long.compare(a.bookSeq, b.bookSeq));
        held.sort((a, b) -> Integer.compare(a.orderRef, b.orderRef));
        final int[] out = new int[resting.size() + held.size()];
        int n = 0;
        for (final RestingOrder o : resting) {
            out[n++] = o.orderRef;
        }
        for (final RestingOrder o : held) {
            out[n++] = o.orderRef;
        }
        return out;
    }

    /** {securityId, lastTradePx, hasTraded, pegRefBid, pegRefAsk} for securities with any of it. */
    public java.util.List<long[]> orderTypeSecurityTuples() {
        final java.util.List<long[]> out = new java.util.ArrayList<>();
        for (int s = 0; s < lastTradePx.length; s++) {
            if (hasTraded[s] || pegHead[s] != null) {
                out.add(new long[] { s, lastTradePx[s], hasTraded[s] ? 1L : 0L, pegRefBid[s], pegRefAsk[s] });
            }
        }
        return out;
    }

    public void bootstrapOrderTypeSecurity(int s, long tradePx, boolean traded, long refBid, long refAsk) {
        lastTradePx[s] = tradePx;
        hasTraded[s] = traded;
        pegRefBid[s] = refBid;
        pegRefAsk[s] = refAsk;
    }

    /**
     * FR-OT35 restore assertions: invariant I-S (every live DAY order belongs to the open current
     * date) and iceberg conservation. A violation means the snapshot is not one this build wrote.
     */
    public void verifyRestoredOrderTypes() {
        for (final RestingOrder o : ordersByRef.values()) {
            if (o.orderRef == 0 || !o.isOpen()) {
                continue;
            }
            if (o.tif == OrderTypes.DAY && (!dayOpen || o.sessionDate != businessDate)) {
                throw new IllegalStateException("restore corrupt: a live DAY order outside the open business date");
            }
            if (o.orderType == OrderTypes.ICEBERG
                && (o.displayed < 0 || o.displayed > o.remaining || o.displayed > o.displayQty
                    || (o.isResting() && o.displayed == 0))) {
                throw new IllegalStateException("restore corrupt: iceberg conservation");
            }
        }
        if (qHead != qTail) {
            throw new IllegalStateException("restore corrupt: trigger queue not empty");
        }
    }

    /** FR-OT35: always 0 between commands; the snapshot writer asserts it. */
    public int triggerQueueDepth() {
        return qTail - qHead;
    }

    public int pendingCount(int securityId) {
        return pendCount[securityId];
    }

    public int pegCount(int securityId) {
        return pegCount[securityId];
    }

    public long lastTradePx(int securityId) {
        return lastTradePx[securityId];
    }

    public boolean hasTraded(int securityId) {
        return hasTraded[securityId];
    }

    /** Test/edge read of one order's typed state as {status, remaining, displayed, limitPx,
     *  stopPx, watermark, riskPx, suspendReason, reservedNotional, reservedQty, riskReason}. */
    public long[] orderState(int orderRef) {
        final RestingOrder o = lookup(orderRef);
        if (o == null) {
            return null;
        }
        return new long[] { o.status, o.remaining, o.displayed, o.limitPx, o.stopPx, o.watermark,
            o.riskPx, o.suspendReason, o.reservedNotional, o.reservedQty, o.riskReason };
    }

    public long cascadeLimitEvents() {
        readFence();
        return cascadeLimitEvents;
    }

    public long fokInvariantBreaches() {
        readFence();
        return fokInvariantBreaches;
    }

    public int lastSettleRounds() {
        return lastSettleRounds;
    }

    public int maxSettleRounds() {
        readFence();
        return maxSettleRounds;
    }

    public long ordersTriggered() {
        readFence();
        return ordersTriggered;
    }

    public long pegReprices() {
        readFence();
        return pegReprices;
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
            // anchor, chosen from replicated state by bandSlot) is identical on every member and replay.
            book = new LimitBook(bookLevels, tickPxForBook(securityId));
            booksBySecurity[securityId] = book;
        }
        return book;
    }

    /**
     * YU17 (format-8 design section 2.1): the grid a book for {@code securityId} should be on RIGHT NOW,
     * from replicated state only, in priority order:
     *
     * <ol>
     *   <li>the ADR-060 ticker CATEGORY override (bonds: tick 1) -- granularity-mandatory, and it
     *       beats the map: a fraction-of-par price needs six decimals, and CORP-JPM-20310601 trades
     *       ABOVE par live, where a reference-derived tick 10 would refuse legal bond quotes as
     *       off-grid (design section 1.3);</li>
     *   <li>the decade map on the collar's reference (feed price, else mark);</li>
     *   <li>the global grid -- PROVISIONAL, not a fallback that has to be right: a book with no
     *       reference re-derives at its next empty admission (see {@link #onNewOrder}).</li>
     * </ol>
     *
     * <p>Every input is replicated and snapshotted (the same property ADR-066's band anchor stands
     * on) and the map is pure, so the answer is identical on every member, on replay, and on
     * ClusterRecon's from-scratch rebuild.
     */
    private long tickPxForBook(int securityId) {
        final long category = bookTickPxBySecurity[securityId];
        if (category != 0L) {
            return category;
        }
        final long ref = collarReferencePx(securityId);
        return ref > 0L ? decadeTickPx(ref, bookTickPx) : bookTickPx;
    }

    /**
     * YU17 (format-8 design section 2.3): re-derive an EMPTY book's grid before the order that found it
     * empty is judged against it. THIS ONE RULE DOES FOUR JOBS -- a reader who sees only one of
     * them will simplify it away and break the other three:
     *
     * <ol>
     *   <li><b>the frozen accident dies.</b> A book created before its security's first tick holds
     *       the provisional global grid for ONE occupancy, not for the epoch;</li>
     *   <li><b>scale drift self-heals.</b> A security that moves orders of magnitude re-derives at
     *       its next empty moment, so no re-index-at-a-different-scale mechanism is needed: the
     *       only book that could need one is one continuously occupied across the whole move, and
     *       ADR-066 empties that one itself (re-anchor + stranded cancel) before it turns over;</li>
     *   <li><b>it is load-bearing for DETERMINISM, not hygiene.</b> Un-anchored books are absent
     *       from the snapshot, so a member that restored (no book) and one that never restarted
     *       (book exists, empty) must agree on the next order's grid. Both compute
     *       {@link #tickPxForBook} from the same replicated reference at the same apply. Without
     *       this rule the survivor would keep a creation-time tick the restorer cannot
     *       reconstruct;</li>
     *   <li><b>it is what makes the unpriced fallback PROVISIONAL</b> -- and therefore what lets
     *       this ship with no option-category constant at all: an unpriced book is one
     *       tick-then-empty-admission away from its real grid, so the fallback never needs to be
     *       right, only safe.</li>
     * </ol>
     *
     * <p>Cost on the occupied path: one O(1) int compare. On the empty path: a few compares and two
     * field writes -- no allocation, no array work (an empty book's levels, occupancy, heads and
     * best pointers are already clear; gates V1/V2).
     */
    private void rederiveIfEmpty(LimitBook book, int securityId) {
        if (book.openOrders() != 0) {
            return;
        }
        final long derived = tickPxForBook(securityId);
        if (derived != book.tickTicks()) {
            book.retick(derived);
            bookReticks++;
        }
    }

    /**
     * YU16 (ADR-060): install a derived per-security book grid before the security's book exists.
     * Cold path (symbol registration / snapshot restore). The value is a pure function of the
     * committed ticker, never stored: T_SYMBOL restores ahead of T_BOOK, so restore re-derives it
     * in time for {@link #bootstrapBook} to rebuild the book on the identical grid.
     */
    public void overrideBookTickPx(int securityId, long tickPx) {
        if (securityId >= 0 && securityId < bookTickPxBySecurity.length) {
            bookTickPxBySecurity[securityId] = tickPx;
        }
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

    public long countOrdersReplace() {
        readFence();
        return ordersReplace;
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

    /** Resting orders cancelled by self-trade prevention (ADR-057). The authoritative count: egress
     *  acks are best-effort and drop under flood, so a gateway-side tally undercounts. */
    public long countSelfTradesPrevented() {
        readFence();
        return selfTradesPrevented;
    }

    /** ADR-066: how many times a book's band was re-centred on the market reference. */
    public long bandReanchors() {
        readFence();
        return bandReanchors;
    }

    /** ADR-066: resting orders cancelled because a re-anchored band could not hold them. */
    public long bandStrandedCancels() {
        readFence();
        return bandStrandedCancels;
    }

    /** YU17 (ADR-072): trade legs booked for a replay account. Replicated and snapshotted, so
     *  {@code tradeCounter() - externalTradeLegs()} is the operator-only count on every member. */
    public long externalTradeLegs() {
        readFence();
        return externalTradeLegs;
    }

    /** ADR-072: STP cancels caused by replayed tape flow. Per-process, like its parent. */
    public long externalSelfTradesPrevented() {
        readFence();
        return externalSelfTradesPrevented;
    }

    /** YU17 (ADR-072): the replayed half of {@link #bandReanchors()}. PER-PROCESS, like its
     *  sibling — read it as a delta against a baseline, never as a cross-member absolute. */
    public long externalBandReanchors() {
        readFence();
        return externalBandReanchors;
    }

    /** YU17 (ADR-072): the replayed half of {@link #bandStrandedCancels()}. PER-PROCESS. */
    public long externalBandStrandedCancels() {
        readFence();
        return externalBandStrandedCancels;
    }

    /**
     * YU17 (format-8 design section 2.6): empty-book re-derivations that CHANGED a book's tick. An
     * accident window that fires is a counted event, never a silent state change.
     *
     * <p>PER-PROCESS, like {@link #bandReanchors} and for the same reason: a plain in-process
     * field, never snapshotted (observability must not enter the deterministic state machine,
     * where disagreement would become digest divergence instead of a metrics artefact). So a
     * member's ABSOLUTE value is a function of how much log THAT process has applied since it
     * started -- a restarted member legitimately reads lower than its peers on a cluster in perfect
     * agreement. Read it as a per-member DELTA against a captured baseline; cross-member equality
     * of absolutes is an assertion that cannot pass.
     */
    public long bookReticks() {
        readFence();
        return bookReticks;
    }

    /** The Px grid a security's book is currently on, or 0 when no book exists (read side). */
    public long bookTickPxOf(int securityId) {
        final LimitBook book = book(securityId);
        return book == null ? 0L : book.tickTicks();
    }

    /**
     * YU17 (design section 2.6): true when this book's stored grid disagrees with what its CURRENT
     * reference would derive -- i.e. the security's price scale has drifted away from the grid the
     * book was last established on. Only meaningful while the book is occupied; an empty book
     * re-derives on its next admission, so a drift there is one order away from healing.
     * Read-side only; it decides nothing.
     */
    public boolean bookTickDrifted(int securityId) {
        final LimitBook book = book(securityId);
        return book != null && book.openOrders() > 0 && book.tickTicks() != tickPxForBook(securityId);
    }

    /**
     * YU17 (ADR-069, format-8 scope section 1.3): refuse a request that TARGETS an existing order,
     * without mutating that order -- the shape {@code onReplace} already uses for a too-late
     * replace. The venue's session gate is the caller; this method knows nothing about phases,
     * which is what keeps the engine clock-free.
     *
     * <p>Emitted through {@code emitRequestRejected} on purpose: the payload is the order exactly
     * as it still stands, so the read model's row keeps the status it really has and only the ack's
     * kind and reason byte carry the refusal. Writing a REJECTED status onto a live order's row
     * would lose the order to anyone watching.
     */
    public void refuseTargetedRequest(InputEvent e, RiskReason reason) {
        final RestingOrder o = lookup(e.orderRef);
        if (o == null) {
            out.emitOrderNotFound(e.seq, e.ingressNanos);
            return;
        }
        out.emitRequestRejected(o, e.seq, (byte) reason.ordinal(),
            lastPxBySecurity[o.securityId], e.ingressNanos);
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
