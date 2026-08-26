package finos.traderx.ordermatcher.cluster;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.HotPathMetrics;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.OccSymbol;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.OutputPublisher;
import finos.traderx.ordermatcher.lmax.Px;
import finos.traderx.ordermatcher.lmax.SwapConventions;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import finos.traderx.ordermatcher.risk.RiskReason;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.concurrent.TimeUnit;

/**
 * Hosts the deterministic {@link MatchingEngine} plus the authoritative {@link BlpRiskState}
 * inside an Aeron Cluster service (ADR-044).
 *
 * Every input is a committed consensus-log message (ADR-045): the inherited SBE
 * {@code InputEventMessage} decoded straight into the reusable {@link InputEvent} and applied on
 * the single service thread. Event time is the cluster timestamp, never a local clock. Risk
 * control state (accounts, securities, policy, restrictions) is seeded and mutated exclusively
 * by sequenced {@code *_CONTROL} ingress — there is no side-channel bootstrap feed.
 *
 * YU13: the hosted engine is a genuine crossing limit-order book (price-time priority; the
 * consensus-log order IS the time priority). Egress acks carry a resting-update class byte so
 * gateway ack correlation distinguishes an input's direct lifecycle response from counterparty
 * resting-order updates emitted by the same apply (FR-LOB07).
 *
 * YU15: a sequenced risk-extract marker (ADR-055) names a consensus sequence N at which every
 * member renders the identical position cut. It mutates no state — the cut is a read of state the
 * log has already agreed on — so it is safe to interleave with ordinary flow, and the sequence it
 * lands at is the immutable name the downstream EOD fixture is stamped with.
 *
 * Snapshot completeness (ADR-046, `system/snapshot-completeness-matrix.md`): {@code
 * onTakeSnapshot} persists every future-output generator and admission dependency — the
 * generators, the book (band geometry + per-security band anchors, then open rows in ascending
 * orderRef order — arrival order, so restore re-appends them into their price levels in the
 * exact original FIFO — then retained terminal rows in eviction-FIFO order so bounded-retention
 * eviction stays replica-identical), positions, engine prices, risk policy, account
 * control/executed exposure, security control/price freshness, and idempotency entries in
 * retention order. Per-order reservations ride on the order rows; the account/exposure
 * aggregates are rebuilt from them on load so the two can never disagree. Recovery fails closed
 * when a restored identifier reaches the restored generator.
 */
public final class MatchingEngineClusteredService implements ClusteredService {
    // ponytail: spike-fixed engine/risk sizing; the production path re-reads these from
    // properties — they must be identical on every member (config identity, see the matrix)
    //
    // 64 -> 1024 (2026-07-31): 64 was sized for the demo universe and could not hold the YU04
    // control feed, which replays reference-data's full 510-security universe through consensus —
    // the subscriber wedged at ticker 65 (a consumer that will not silently drop securities cannot
    // get past a full table). 1024 = the universe plus headroom, power of two. This is capacity,
    // not LAYOUT: the snapshot writes ONE T_SECURITY tuple per registered security (variable
    // length), so no record changed shape — which is why SNAPSHOT_FORMAT was left at 3 here, and
    // that was the error. Layout compatibility is not READER capability: a widened domain lets
    // this build emit a symbol id an older 64-build rejects outright. Bumped to 4 on 2026-08-05
    // after exactly that failure; see SNAPSHOT_FORMAT below. Per-security fixed cost is ~26 bytes in
    // BlpRiskState plus a lazily-created book, and the Spring tier has run
    // blp.books.max-securities=4096 all along — 64 was the outlier, not the design. Like ANY
    // change to this block it must land on all members via a fresh-epoch same-image roll, never a
    // rolling swap: a 64-member and a 1024-member disagree about whether ticker 65 registers,
    // which is permanent divergence.
    static final int MAX_SECURITIES = 1024;
    static final int POOL_SIZE = 1024;
    // The service thread is BOTH producer (engine emits during apply) and consumer (drainOutputs
    // after apply), so a single event whose output burst exceeds free ring space self-deadlocks in
    // RingBuffer.next() — hit on GKE 2026-07-19 when a price tick mass-executed a ~20k-order book
    // (3 slots per fill) against the old 4096 ring. Size must exceed 3x the worst resting-book
    // cascade. Default 1<<16 covers a ~21k-fill cascade (~10MB of pre-allocated slots — multi-
    // member in-process tests OOM'd at 1<<18); deployments with bigger books set
    // CLUSTER_OUTPUT_RING_SIZE (the GKE manifest pins 1<<18). YU13's crossing engine emits 6
    // slots per match step (both sides), bounded by the aggressor's quantity — the drain-and-
    // retry OutputPublisher override remains the structural guard either way.
    static final int OUTPUT_RING_SIZE = 1 << 16;

    static int outputRingSize() {
        final String env = System.getenv("CLUSTER_OUTPUT_RING_SIZE");
        return env == null || env.isEmpty() ? OUTPUT_RING_SIZE : Integer.parseInt(env);
    }
    static final int MAX_ACCOUNTS = 64;
    /**
     * Duplicate-suppression window, GLOBAL across every session (FR-IMRG14).
     *
     * <p>1024 was not a small window, it was a decorative one. A count-based bound only becomes a
     * protection WINDOW via the arrival rate, and this table is shared by every session: at kind's
     * measured ~240 FIX orders/s it bought ~4.3s; on the batch path at 438k/s it bought ~2.3ms; and
     * at 500 sessions it was ~2 orders of protection each — MORE sessions made the window SHORTER.
     * A FIX client reconnect takes seconds to tens of seconds, so a resend almost always landed
     * past the eviction frontier and double-booked. Verified by eviction, not computed: a key
     * resent after 1100 intervening distinct keys returned a NEW orderRef.
     *
     * <p>256Ki sizes the window for where resends actually happen — the session path at a projected
     * ~3k/s — giving ~90s of cover. Entries are 28 bytes in the snapshot (4-byte type + 3 longs),
     * so a full table adds ~7MB to a snapshot and ~8.5MB of presized heap; both are measured in
     * this state's bench rather than assumed, because snapshot duration is an apply-thread freeze.
     * Presized at construction, so the 250x table never rehashes on the hot path (NGC-01).
     */
    static final int IDEMPOTENCY_CAPACITY = 256 * 1024;
    static final long CREDIT_LIMIT_TICKS = Long.MAX_VALUE / 4;
    static final int MAX_ORDER_QUANTITY = 1_000_000;
    static final long MAX_ORDER_NOTIONAL_TICKS = Long.MAX_VALUE / 4;
    static final long PRICE_MAX_AGE_MILLIS = Long.MAX_VALUE / 4;

    /**
     * OTC contract store capacity (YU17). Contracts are permanent replicated state — a swap has no
     * lifecycle in this state, so nothing ever removes one — which makes an unbounded store an
     * unbounded snapshot, and a snapshot write is an apply-thread freeze. 4096 contracts is ~272KB
     * of snapshot at 68 bytes each, an order of magnitude inside the budget the idempotency table
     * already sets. At capacity a booking is REFUSED, deterministically and identically on every
     * member, exactly as symbol registration refuses past MAX_SECURITIES — never silently dropped.
     */
    static final int MAX_CONTRACTS = 4096;

    /**
     * Format 8 (YU17, the format-8 mint -- `system/format-8-mint-scope.md`,
     * `system/format-8-price-derived-grid-design.md`). Three changes ride one epoch, because a
     * mint is one irreversible shot:
     *
     * <ul>
     *   <li><b>T_BOOK grows a column</b> to {securityId, baseLevel, tickPx}. This is the change
     *       that closes the door: {@code baseLevel} is denominated in the book's tick, and until
     *       now the tick was DERIVED rather than stored, so any change to the derivation rule
     *       silently reinterpreted an old anchor in the wrong unit -- and whether a given epoch
     *       holds an affected book is DATA-DEPENDENT, exactly the compatibility hazard the format-4
     *       postmortem above exists to forbid. Storing the unit beside the anchor retires the whole
     *       hazard class;</li>
     *   <li><b>T_SESSION</b> = the venue phase plus the pre-open queue's depth, and
     *       <b>T_QUEUED_ORDER</b> = one held order each, in insertion order. A member that
     *       snapshots CLOSED restores CLOSED: ADR-069's "a halt a restart can bypass is not a
     *       halt", made mechanical;</li>
     *   <li><b>the grid derivation itself</b> becomes price-derived (MatchingEngine.decadeTickPx),
     *       which is a deterministic-core change and cannot be rolled gradually under any
     *       circumstances.</li>
     * </ul>
     *
     * <p>Format 7 (YU17 FX-rate fix): the T_FX_RATE record joins the snapshot for the FX conversion
     * rates the credit gate values non-USD swap notionals with. A new record TYPE, not a changed
     * one — every format-6 record keeps its shape and meaning, so MIN_READABLE stays at 3 and a
     * format-6 epoch rolls forward untouched (it simply has no rates yet, and every non-USD
     * booking is refused PRICE_MISSING until rates are sequenced). The bump exists for the other
     * direction: a format-7 snapshot handed to a format-6 build must abort legibly at the header
     * rather than deep in record parsing.
     *
     * <p>Format 6 (YU17 phase 2): T_CONTRACT gains the three option-wrapper columns so a swaption can
     * be carried beside a swap. This was the first change to a record's SHAPE rather than an
     * addition of a new type, and the reader dispatched on the restored format so a format-5 record
     * was read as its eight columns with the wrapper defaulted to a swap. Format 8's MIN_READABLE
     * raise retired that dispatch: format 5 no longer reaches the reader.
     *
     * <p>Format 5 (YU17 phase 1): the T_CONTRACT record joins the snapshot for the OTC contract
     * store. It is a new record TYPE, not a changed one — every format-4 record keeps its shape and
     * meaning here, which is why {@link #MIN_READABLE_SNAPSHOT_FORMAT} stays at 3 and a format-4
     * epoch rolls forward onto this build untouched. The bump exists for the other direction: a
     * format-5 snapshot handed to a YU16 build would hit T_CONTRACT at `default ->` and abort with
     * "unknown snapshot record type: 12", and the version number makes that legible at the header
     * instead of deep in record parsing.
     *
     * <p>Format 4 (YU15): MAX_SECURITIES 64 -> 1024 widened the symbol-id domain. Format 3 (YU14):
     * the security record carries the contract multiplier (6 columns); format 2 (YU13) added book
     * geometry/band anchors, which carry forward unchanged.
     *
     * <p>4 exists because 3 was NOT bumped when the domain widened, leaving two builds that were
     * indistinguishable at the header while disagreeing about what a valid symbol id is. Observed
     * 2026-08-05: a 64-build handed a snapshot written here failed deep in record parsing with
     * "snapshot corrupt: symbol id 64" — a false accusation, since the snapshot was intact and the
     * reader was simply too old — and its service agent died on all three members while the pods
     * stayed READY, so the rig reported healthy with every engine dead.
     *
     * <p>The sharper problem was that the incompatibility was DATA-DEPENDENT. Below 64 registered
     * securities a 1024-build's snapshot restores in a 64-build perfectly, so a rollback rehearsed
     * on a quiet rig succeeds and the identical rollback fails the moment the 65th security exists.
     * A version number makes that hazard unconditional and legible at the header instead of latent.
     * Bump this for any change to what the records can CONTAIN, not merely to their shape.
     *
     * <p>NOT bumped for ADR-066 (the band follows the market), deliberately: T_BOOK still carries
     * {securityId, baseLevel} with the same domain (any anchor >= 0), and the reference the new
     * anchor is derived from (T_SECURITY's feed price, T_PRICE's mark) was already captured. Only
     * the RULE that picks baseLevel changed; a format-7 snapshot written by either build restores
     * exactly in the other, and a first-limit anchor restored into this build is simply re-centred
     * lazily the first time the market disagrees with it. Mixed-version members still diverge —
     * that is the deterministic-core roll rule, not a snapshot-readability question.
     */
    /**
     * <p>Format 9 (YU17, ADR-072 -- replayed prints become order flow): the T_HEADER record grows
     * two longs, {@code externalOrderRefs} and the engine's {@code externalTradeLegs}. They are
     * the REPLAYED halves of the ref generator and the trade counter, so that
     * {@code global - external} is an operator-only reading a continuous replayed order feed
     * cannot move. Nothing else about the format changes and no record type is added.
     *
     * <p>MIN_READABLE moves with it, and that is the honest statement rather than a convenience:
     * a format-8 header carries neither field, so a format-8 snapshot restored here would leave
     * both at zero and every operator counter would then be inflated by the whole epoch's replayed
     * flow -- a wrong ANSWER, silently, which is precisely the class the format-4 postmortem below
     * exists to forbid. A fresh epoch is mandatory.
     */
    static final int SNAPSHOT_FORMAT = 9;
    /**
     * Oldest format this build can still restore. <b>3 -> 8 (YU17 format-8 mint): the first raise
     * ever.</b>
     *
     * <p>3 -> 4 only WIDENED the symbol-id domain, 4 -> 5 and 6 -> 7 only ADDED a record type, and
     * 5 -> 6 changed T_CONTRACT's shape in a way the reader handles explicitly by format -- so
     * every format-3 through -7 record still meant here exactly what it meant there, and this
     * build could roll onto an existing epoch without wiping it.
     *
     * <p>Format 8 ends that, and NOT because of the two new record types (those alone would have
     * been MIN_READABLE-preserving additions). It is the T_BOOK tick column: a pre-8 T_BOOK carries
     * an anchor with no unit, and this build's grid derivation is not the one that wrote it, so
     * restoring it would reinterpret the anchor at the wrong scale -- silently, and only for the
     * securities whose derived tick happened to change. Refusing every pre-8 snapshot at the header
     * makes a FRESH EPOCH MANDATORY rather than merely budgeted, which is the honest statement of
     * what this build is.
     *
     * <p>The same reasoning applies to ANY future change of the grid derivation: it re-interprets
     * every stored anchor, so it is a format bump, not a behaviour tweak. Since format 8 stores the
     * tick, such a bump no longer has to raise MIN_READABLE -- the unit is in the record.
     */
    static final int MIN_READABLE_SNAPSHOT_FORMAT = 9;
    static final int T_HEADER = 1;
    static final int T_ORDER = 2;
    static final int T_POSITION = 3;
    static final int T_PRICE = 4;
    static final int T_END = 5;
    static final int T_POLICY = 6;
    static final int T_ACCOUNT = 7;
    static final int T_SECURITY = 8;
    static final int T_IDEMPOTENCY = 9;
    static final int T_SYMBOL = 10;
    static final int T_BOOK = 11;
    /** YU17: one booked OTC swap. See {@link #CONTRACT_TUPLE_LENGTH} for the column order. */
    static final int T_CONTRACT = 12;
    /** YU17 FX-rate fix: {currencyIndex, usdRateTicks} — one per currency with a sequenced rate.
     *  Never written for index 0: USD is the limit currency and its rate is identity by
     *  construction, not state. */
    static final int T_FX_RATE = 13;
    /** YU17 format 8: {phase, queueDepth} -- the venue session. Exactly one row, always written.
     *  {@code queueDepth} is read from the LIVE queue, never tallied from what the write loop
     *  emitted: a count a writer derives from its own output is a checksum of its own bug. */
    static final int T_SESSION = 14;
    /** YU17 format 8: one PRE_OPEN-queued order, {@link #QUEUED_TUPLE_LENGTH} columns, written and
     *  restored in INSERTION ORDER -- which is the release order at the open, so the order of these
     *  rows is state, not presentation. */
    static final int T_QUEUED_ORDER = 15;

    /** {orderRef, accountId, securityId, side, qty, limitPx, clientOrderKey, eventTimeMillis} --
     *  the complete replicated content of a queued ORDER_NEW. {@code seq}, {@code ingressNanos} and
     *  the request id are not state, exactly as {@code applyRequestId} is not. */
    static final int QUEUED_TUPLE_LENGTH = 8;

    /**
     * Record types a format-8 snapshot MUST contain, as a bitmask over the T_* type ids
     * (see {@link #recordTypesSeen}).
     *
     * <p>WHY THIS EXISTS. A snapshot record stream is self-describing and knows no expected counts,
     * so until now a restore handed a stream with WHOLE RECORD TYPES MISSING accepted it in
     * silence: no throw, clean termination on T_END, state simply absent. {@code finishLoad}
     * checked exactly two things (header seen, generator above every issued ref) and
     * MIN_READABLE catches none of it. A stream missing T_END is a different and already-safe
     * failure ({@code loadSnapshot} refuses it at end-of-stream); the silent case is T_END present
     * with types absent.
     *
     * <p>Only the UNCONDITIONAL records can be required. T_ACCOUNT, T_SECURITY, T_SYMBOL, T_ORDER,
     * T_BOOK, T_POSITION, T_PRICE, T_IDEMPOTENCY, T_CONTRACT and T_FX_RATE are all legitimately
     * absent from an empty or young epoch, so requiring them would be a guard that fires on correct
     * data. T_HEADER, T_POLICY and T_SESSION are written on every snapshot this build takes, so
     * their absence is always a defect.
     *
     * <p>The queue is NOT covered by presence -- zero T_QUEUED_ORDER rows is legitimate whenever
     * the queue is empty, and that is precisely the dangerous record: a queue silently restored
     * empty reads as "no halt was in effect". T_SESSION's {@code queueDepth} column covers it by
     * count instead (see {@link #finishLoad}).
     */
    static final int REQUIRED_RECORD_TYPES =
        (1 << T_HEADER) | (1 << T_POLICY) | (1 << T_SESSION);

    /**
     * Venue session phase (ADR-069). Replicated state living in MECS beside the contract store and
     * the FX rates, NEVER in {@link MatchingEngine}: the engine stays clock-free and untouched by
     * the session machine, exactly as it is untouched by swap bookings.
     *
     * <p>The core never knows what time it is. "6:30 ET" is only ever <em>when a producer issued
     * the command</em>.
     */
    static final byte PHASE_CLOSED = 0;
    static final byte PHASE_PRE_OPEN = 1;
    static final byte PHASE_OPEN = 2;

    /** Phase names, index-aligned with the constants above; the /health and gateway surfaces. */
    static final String[] PHASE_NAMES = { "CLOSED", "PRE_OPEN", "OPEN" };

    /**
     * Pre-open queue capacity, SIZED not guessed (skill: {@code size-a-configuration-bound}).
     *
     * <p>The binding unit is not "orders" -- it is <b>how large a halt this venue can hold without
     * refusing business</b>, against <b>what one apply costs when it releases them</b>, and those
     * pull opposite ways. Both gradients are MEASURED by {@code QueuedOrderSizingTest}, which
     * prints the table below on every run rather than leaving the rationale in a commit message:
     *
     * <pre>
     *   cap      queue bytes   snapshot total   outputs at the open   % of output ring
     *   256          17,408           17,620                   256              0.4%   measured
     *   4096        278,528          278,740                 4,096              6.3%   measured
     *   65536     4,456,448                -                65,536            100.0%   extrapolated
     *   unit costs: 68 B/row, 1 output per released rest; output ring 65,536 slots
     *   reference:  the idempotency table already costs 7,340,032 B/snapshot
     * </pre>
     *
     * <ul>
     *   <li><b>Snapshot bytes (an apply-thread FREEZE, not heap).</b> A full 4096-order queue adds
     *       272 KB -- 26x inside the ~7 MB the idempotency table already spends on every snapshot,
     *       which is the budget this project has already accepted. At 65536 it would be 4.25 MB,
     *       comparable to that table: the point at which the queue stops being cheap.</li>
     *   <li><b>The OPEN apply's output cascade.</b> The release replays every queued order through
     *       the engine inside ONE apply. A full queue of pure rests emits 4096 outputs into the
     *       65,536-slot ring -- 6.3%. At 65536 it would be exactly 100% of the ring, which would
     *       make {@code drainOnBackpressure} the release's NORMAL path on the single most important
     *       apply of the day rather than its structural backstop. (Crossing releases emit more per
     *       order and are bounded by that same backstop either way.)</li>
     * </ul>
     *
     * <p>Opposing (too-small) side, reported because a negligible cost is still a finding: the
     * rig's whole fixture universe is 69 instruments and the heaviest proof traffic moves the
     * order-ref generator by tens per window, so a pre-open window would have to take thousands of
     * distinct client orders before the first CAPACITY refusal. At 256 that is reachable; 4096
     * leaves roughly two orders of magnitude of headroom on real traffic.
     *
     * <p>At capacity the order is REFUSED {@code CAPACITY}, deterministically and identically on
     * every member -- exactly as {@link #MAX_CONTRACTS} and {@code MAX_SECURITIES} refuse, never
     * silently dropped. Like every constant in this block it is config identity: a member with a
     * different value disagrees about whether order 4097 was queued, which is permanent divergence,
     * which is why the sizing test extrapolates the row above the cap instead of measuring it.
     */
    static final int MAX_QUEUED_ORDERS = 4096;

    /**
     * {contractId, accountId, payFixed, notional, fixedRateTicks, conventionIndex,
     * effectiveEpochDay, maturityEpochDay, productType, expiryEpochDay, exerciseStyle} — the
     * complete economics of a vanilla fixed-float IRS, or of a swaption on one, as this state
     * models them (D5: terms only, no valuation; D6: no lifecycle, so no accrual, no schedule and
     * no exercise). {@code contractId} is the consensus sequence the booking landed at, which is
     * unique within the epoch by construction and needs no generator of its own.
     *
     * <p>The first eight columns mean the same thing for both products, because a swaption's
     * underlying IS a swap: for a swaption, {@code fixedRateTicks} is the strike and
     * {@code payFixed} is the direction of the underlying's fixed leg. The last three are the
     * option wrapper and are zero for a swap.
     */
    static final int CONTRACT_TUPLE_LENGTH = 11;

    static final int PRODUCT_SWAP = 0;
    static final int PRODUCT_SWAPTION = 1;

    /**
     * YU16 (ADR-060): a BOND's book grid is ONE Px tick, derived from the committed ticker exactly
     * as the YU14 contract multiplier is (ADR-052) - stored nowhere, identical on every member, on
     * replay and on restore. The global 0.001 grid is three decimals in price space, which for a
     * bond price stored as a fraction of par (ADR-057) is one decimal of percentage: the book
     * would reject every six-decimal bond limit as off-grid. Grid 1 admits the full fraction; the
     * band at that grid still spans +/-6.5 points of par around the anchor, six times the widest
     * configured walk.
     */
    static final long BOND_BOOK_TICK_PX = 1L;

    /**
     * The ticker prefixes that mean "this instrument is quoted as a FRACTION OF PAR at six
     * decimals". THE ONE PLACE the grid convention is written down.
     *
     * <p>WHY A TICKER CONVENTION AND NOT THE INSTRUMENT JOIN. The grid must be a pure function of
     * committed state, re-derivable identically on every member, on replay and on restore, with
     * nothing stored. The committed state for a symbol IS its ticker - that is the whole content
     * of T_SYMBOL. Deriving the grid from instrument static instead would mean carrying that
     * static through consensus, which means changing SymbolRegisterMessage, which means the SBE
     * schema - the artifact that defines wire compatibility between builds. That is far more blast
     * radius than a price grid is worth, so the convention is encoded in the ticker and enforced
     * in reference data (cdm-catalog.spec.ts asserts every Debt instrument's key matches these
     * prefixes, so adding MUNI- or AGCY- without updating this list fails a test rather than
     * silently handing the new bond the equity grid).
     *
     * <p>Extended from Treasury-only to all bonds on 2026-08-16. It is a DETERMINISTIC-CORE change
     * despite touching no stored format: a member on the old build and one on the new compute
     * different grids for the same committed CORP- ticker, so the same log entry reaches different
     * state and the split is permanent. It cannot be rolled gradually - scale to zero, wipe the
     * PVCs, mint a fresh epoch, all members and the gateway together off one build.
     */
    private static final String[] FRACTION_OF_PAR_TICKER_PREFIXES = { "UST-", "CORP-" };

    /** True when the ticker names an instrument quoted as a fraction of par (see the field above). */
    static boolean isFractionOfParTicker(final String ticker) {
        if (ticker == null) {
            return false;
        }
        for (final String prefix : FRACTION_OF_PAR_TICKER_PREFIXES) {
            if (ticker.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static long derivedBookTickPxFor(final String ticker) {
        return isFractionOfParTicker(ticker) ? BOND_BOOK_TICK_PX : 0L;
    }

    /** Egress ack kind for symbol registration (outside OutputEvent's 1..8 range). */
    public static final byte KIND_SYMBOL_REGISTERED = 100;

    /** Egress ack kind for the YU15 risk-extract marker; the ack carries the sequence N it landed
     *  at, which is the name the extract is stamped with. */
    public static final byte KIND_RISK_EXTRACT_MARKED = 101;

    /** Egress ack kind for a YU17 swap booking. Deliberately outside OutputEvent's 1..8 range, so
     *  it can never be mistaken for an order-lifecycle ack by the gateway's egress correlation. */
    public static final byte KIND_SWAP_BOOKED = 102;

    /**
     * Egress ack kind for a YU17 session-phase command (ADR-069), correlating by its OWN request id
     * at ack byte 13 -- exactly as the symbol, extract and swap acks do.
     *
     * <p><b>This routing is the regression trap, not a style choice.</b> The OPEN apply also emits
     * every released order's lifecycle acks, and those echo {@code applyRequestId} at bytes 24..31.
     * The phase command is offered with request id 0 precisely so those echoes can complete
     * NOTHING (the gateway never registers id 0 -- see the {@code Inflight} invariant). Route the
     * phase ack through bytes 24..31 instead and a released order's ack completes the operator's
     * pending, which is the ratcheting-offset bug that produced the gateway wedge, rebuilt.
     */
    public static final byte KIND_SESSION_PHASE = 103;

    // long appliedSeq, int orderRef, byte kind, long tradeSeq at 13..20, then three class bytes:
    //  21 restingClass — 1 = counterparty resting-order update, 0 = direct response (FR-LOB07);
    //  22 riskReason   — RiskReason ordinal, so a synchronous /trades reject can answer WHY
    //                    (YU12 3394186 put this at 21; moved here because 21 is taken above);
    //  23 marketTrade  — 1 = this output came from a TYPE_TRADE_NEW apply. YU13's crossing book
    //                    emits KIND_TRADE_BOOKED twice per MATCH for ordinary order fills, so
    //                    kind alone no longer identifies the /trades path; without this byte the
    //                    gateway would answer a market trade from a foreign fill's ack.
    //  24..31 requestId — the gateway-chosen 64-bit request id this apply's input carried (ack
    //                    correlation fix, option B): the gateway matches acks by THIS KEY, never
    //                    by arrival order, so a stranded offer can no longer shift every later ack
    //                    onto the wrong request. 0 = the input named no request (another producer's
    //                    tick, a batch offer, a pre-B log entry) — the gateway never registers id 0,
    //                    so a zero can complete nothing. It rides the ingress message's inputSeq
    //                    slot (dead on this tier: the gateway always offered 0 and event.seq is
    //                    overwritten with ++appliedSeq on apply), so the INGRESS schema and the log
    //                    format are unchanged; only this egress record grew, 24 -> 32. A 24-byte
    //                    reader against this writer is a mixed-version fleet and is refused loudly
    //                    at the gateway's length check — members and gateways roll together.
    static final int EGRESS_ACK_LENGTH = 32;

    /** Snapshot transport seam: production offers to the cluster snapshot publication; tests
     *  capture buffers directly. One call per record. */
    interface SnapshotWriter {
        void write(DirectBuffer buffer, int offset, int length);
    }

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final InputEvent event = new InputEvent();
    private final UnsafeBuffer ackBuffer = new UnsafeBuffer(new byte[EGRESS_ACK_LENGTH]);
    private final ExpandableArrayBuffer snapshotBuffer = new ExpandableArrayBuffer();

    private Cluster cluster;
    // Initialized so a clusterless harness (a capturing test session) can reach offerEgress;
    // onStart replaces it with the cluster's own strategy before any real traffic.
    private IdleStrategy idle = new org.agrona.concurrent.NoOpIdleStrategy();
    private BlpRiskState risk;
    private MatchingEngine engine;
    private RingBuffer<OutputEvent> outputRing;
    private Sequence outputConsumed;
    private ClientSession activeSession; // apply-scoped: egress target for mid-apply backpressure drains
    // Apply-scoped: true while a TYPE_TRADE_NEW is being applied, so every output drained for it
    // (including mid-apply backpressure drains) is stamped as a market-trade decision. YU13's
    // crossing book emits KIND_TRADE_BOOKED for both sides of every ORDER match, so the ack kind
    // alone can no longer tell the /trades path from ordinary fills.
    private boolean applyingMarketTrade;
    // Apply-scoped (ack-correlation fix, option B): the gateway-chosen request id the CURRENT input
    // carried in the ingress inputSeq slot, captured after decode and BEFORE event.seq is
    // overwritten with ++appliedSeq. Echoed at ack bytes 24..31 by every egress this apply emits
    // (including mid-apply backpressure drains, which are the same apply). NEVER state: it is read
    // by no decision, written to no snapshot, and identical-state applies differing only in it
    // produce identical replicated state — which is why SNAPSHOT_FORMAT did not move for this.
    private long applyRequestId;
    private boolean stampFirstApplyAsLeader; // Phase-0 SLO clock: armed on LEADER, fires once

    private long nextOrderRef = 1;
    /**
     * YU17 (ADR-072): how many of the refs {@link #nextOrderRef} has issued went to REPLAYED TAPE
     * FLOW rather than to an operator. {@code nextOrderRef - 1 - externalOrderRefs} is the
     * operator-only count, and the replay cannot move it by construction.
     *
     * <p>This is the counter ADR-072 is about. `lib-consensus-readings.sh` retreated to the
     * order-ref generator when the feed adapter made the global applied sequence unreadable, on
     * the promise that "ticks never touch it" — and replayed prints are order-shaped, so they do.
     * Widening the tolerance was ruled out there in advance: it deletes the check. Splitting the
     * counter at the writer keeps it.
     *
     * <p>SNAPSHOTTED (format 9). It has to be: the reading quiesces across all three members, two
     * proofs restart one, and a member that restored a zero here would report an operator count
     * inflated by every replayed order in the epoch so far.
     */
    private long externalOrderRefs;
    private long highestIssuedRef;
    private long appliedSeq;
    private boolean snapshotHeaderSeen;
    // Format-8 mint: the "read a record at its own width, dispatching on the restored format"
    // seam is GONE, because MIN_READABLE_SNAPSHOT_FORMAT now equals SNAPSHOT_FORMAT — exactly one
    // format is readable, so a per-format width branch could never take its other arm. It was kept
    // for a while as a dead branch with a comment, which is a build claiming a compatibility it
    // does not have. Reintroduce it (a restoredFormat field set at T_HEADER, read where the shape
    // differs) the next time MIN_READABLE lags SNAPSHOT_FORMAT; the T_CONTRACT case below is the
    // worked example, in git.
    // Symbol identity as replicated state (matrix F2): ids assigned in committed-log order,
    // never evicted, so the generator derives from the mapping itself on restore.
    private final String[] tickerById = new String[MAX_SECURITIES];
    private int nextSymbolId;
    // YU17 OTC contract store: replicated state, appended in committed-log order, never removed
    // (D6). Ascending insertion order IS ascending contractId, because the id is the booking's own
    // consensus sequence — so iteration order is deterministic without a sort, on every member and
    // after restore. Cold path (a handful of bookings a day), so the ArrayList's allocation never
    // touches the order hot loop.
    private final java.util.List<long[]> contracts = new java.util.ArrayList<>();
    // YU17 FX-rate fix: USD per unit of currency, 1e6 fixed-point, indexed by SwapConventions
    // currency index. Replicated state — written ONLY by the sequenced TYPE_FX_RATE apply and by
    // snapshot restore, never from any apply-time lookup, so every member and every replay holds
    // the identical rate at the identical sequence. 0 = no rate sequenced yet, and the credit gate
    // fails closed on it. Slot 0 (USD) stays 0 and is never read: USD is identity by construction.
    private final long[] fxUsdTicksPerCurrency = new long[SwapConventions.currencyCount()];
    /**
     * YU17 (ADR-069): the venue phase. Replicated state -- written ONLY by the sequenced
     * TYPE_SESSION_CONTROL apply and by snapshot restore.
     *
     * <p>A fresh epoch starts OPEN (decision a): every proof and fixture assumes a trading book,
     * and CLOSED-until-commanded remains available by issuing the command during bring-up, so
     * nothing is foreclosed.
     */
    private byte phase = PHASE_OPEN;
    /**
     * YU17 (ADR-069 section 1.4): orders held while PRE_OPEN, {@link #QUEUED_TUPLE_LENGTH} columns each.
     *
     * <p><b>Insertion order is log order and is LOAD-BEARING</b> -- it is the release order at the
     * open, which makes time priority at the open the same thing the book's FIFO already derives
     * from the log, rather than an accident. Written to and restored from the snapshot in that
     * order. Cold path (a handful of halts a day), so the ArrayList never touches the order hot
     * loop -- the same posture as {@link #contracts}.
     */
    private final java.util.List<long[]> queuedOrders = new java.util.ArrayList<>();
    /**
     * clientOrderKey -> index into {@link #queuedOrders}: idempotency at QUEUE time, so a retried
     * key finds the queued original instead of queueing a second copy. The engine's own idempotency
     * table cannot answer here, because the risk decision has not run yet -- it runs at the OPEN.
     *
     * <p>TRANSIENT, and never snapshotted: it is derived state, rebuilt from the queue on restore
     * (the ADR-052/060 pattern). Snapshotting a derived index is how two members come to disagree
     * about a mapping neither of them decided.
     */
    private final java.util.Map<Long, Integer> queuedByClientKey = new java.util.HashMap<>();
    /**
     * Snapshot restore: bit per T_* record type actually seen in the stream, checked against
     * {@link #REQUIRED_RECORD_TYPES} at {@link #finishLoad}. Generalises the
     * {@code snapshotHeaderSeen} boolean that was already here -- restore-side only, no format
     * change. Bit 0 is unused (there is no type 0).
     */
    private int recordTypesSeen;
    /** Snapshot restore: the queue depth T_SESSION declared, so {@link #finishLoad} can assert the
     *  stream actually carried that many T_QUEUED_ORDER rows. -1 = no T_SESSION seen yet. */
    private long restoredQueueDepth = -1L;
    /** Reusable order shell for the session machine's own egress (queue ack, session cancel).
     *  Apply-thread only, never indexed by the engine, never reserved against, never snapshotted
     *  as an order: a queued order lives in {@link #queuedOrders}, not in the book. */
    private final finos.traderx.ordermatcher.lmax.RestingOrder sessionOrder =
        new finos.traderx.ordermatcher.lmax.RestingOrder();
    /** Reusable input for replaying a queued order through the engine at the open. */
    private final InputEvent releaseEvent = new InputEvent();
    /** The engine's output channel, kept so the session machine can emit order-lifecycle events for
     *  orders the engine never sees. Same ring, same thread, single producer. */
    private OutputPublisher outputs;

    private volatile int snapshotsTaken;
    private volatile long lastLoadedNextOrderRef = -1;
    private volatile long lastExtractCutSeq = -1;
    private volatile String lastExtractCutSha;
    private volatile Cluster.Role role = Cluster.Role.FOLLOWER;
    // LATENCY-01 Phase B: leader-clock commit/apply split; null unless LATENCY_DECOMP=1.
    private final LeaderApplyLatency latency = LeaderApplyLatency.fromEnvOrNull();

    // OTEL-01: the member half of the distributed trace; null unless OTEL_TRACES=1, so the apply
    // path's allocation gate and the Epsilon-GC proofs see exactly the code they saw before. Spans
    // are emitted on the LEADER only: a follower applies the same log, but its commit-to-apply delay
    // is not the round-trip the gateway is waiting on, and three members emitting the same span ids
    // would triple-write one trace. The leader check also excludes replay, which never runs as leader.
    private final SpanSink traces = SpanSink.fromEnvOrNull("traderx-cluster-member");
    private final int traceMask = SpanSink.sampleMaskFromEnv();
    // OTEL-01 follow-up: the committed ack kind the CURRENT input produced, picked by the same rule
    // the gateway uses to choose the ack that completes a pending order — first direct (non-resting)
    // order-lifecycle output. Reset before every apply. It is what lets this member escalate a
    // rejected order into the trace sample in step with the gateway, off the identical byte, with
    // nothing exchanged. Telemetry-only: never read by the engine, never written to any output.
    private byte directAckKind;
    // OTEL-01 (brief 07): the trace key of the order CURRENTLY being applied, carried to the drain
    // loop for the order bridge to stamp on the read-model row — the same field trick applyRequestId
    // already uses, because the drain is a separate method running inside this apply on this thread.
    // Zero for everything that is not a traced NEW, and the drain zeroes it again for any output
    // belonging to a DIFFERENT order (see the FLAG_RESTING_UPDATE note there). Telemetry-only: never
    // read by the engine, never written to replicated state.
    private long applyTraceKey;
    // Leader-side cluster-egress → NATS /trades bridge (YU12): only started when TRADE_BRIDGE_NATS_URL
    // is set, so default behaviour is unchanged. Null on every member until then.
    private TradeNatsPublisher tradeBridge;
    // Leader-side order-lifecycle → NATS /orders bridge (YU13): the order-state sibling of the trade
    // bridge, gated on the same env so default behaviour is unchanged. Null on every member until then.
    private OrderNatsPublisher orderBridge;
    // Leader-side capture tap for the KDB-X analytical store (brief 06): third sibling of the two
    // bridges, gated on KDB_TAP_DIR, null on every member until it is set. Off-consensus and
    // best-effort — the Aeron Archive journal remains the authoritative replay source, untouched.
    private KdbTapWriter kdbTap;
    // Leader-side position-cut → NATS bridge (YU15). Gated by RISK_EXTRACT_NATS_URL; null until set.
    private RiskExtractCutPublisher extractBridge;
    // Read-side output tap (YU05 recon/regulatory on this tier — see ClusterRecon). Null in every
    // production member unless ClusterNodeMain wires the recon blotter, and null in EVERY shadow
    // but the one replaying the log, so the apply path's state, outputs and egress bytes are
    // unchanged. It exists so a full-log replay can drive the REAL apply path instead of a
    // reimplementation of it: the orderRef generator, applied-sequence, symbol-id assignment and
    // cluster-time conversion are then the same code on both, and cannot drift apart.
    private volatile java.util.function.Consumer<OutputEvent> outputSink;

    @Override
    public void onStart(final Cluster cluster, final Image snapshotImage) {
        this.cluster = cluster;
        this.idle = cluster.idleStrategy();
        this.role = cluster.role();
        initEngine();
        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }
        final String bridgeUrl = System.getenv("TRADE_BRIDGE_NATS_URL");
        if (bridgeUrl != null && !bridgeUrl.isBlank()) {
            // Epoch-qualified order ids so the read-model key never collides across incarnations
            // (brief 05 item 0). Same value on every member via the manifest; bumped with a DB wipe.
            final String epoch = System.getenv("CLUSTER_EPOCH");
            tradeBridge = new TradeNatsPublisher(bridgeUrl, "/trades", epoch, 1 << 16);
            tradeBridge.start();
            orderBridge = new OrderNatsPublisher(bridgeUrl, "/orders",
                epoch == null || epoch.isBlank() ? "1" : epoch, 1 << 16);
            orderBridge.start();
        }
        final String extractUrl = System.getenv("RISK_EXTRACT_NATS_URL");
        if (extractUrl != null && !extractUrl.isBlank()) {
            extractBridge = new RiskExtractCutPublisher(extractUrl,
                System.getenv().getOrDefault("RISK_EXTRACT_CUT_SUBJECT", "risk.extract.cut"));
            extractBridge.start();
        }
        // Analytical capture tap (brief 06), independent of the NATS bridges on purpose: kdb is a
        // side observer, so a deployment can run the read-model bridges without it or it without
        // them. Its own queue and thread too — sharing the order bridge's would let a slow disk
        // back up the read model.
        final String tapDir = System.getenv("KDB_TAP_DIR");
        if (tapDir != null && !tapDir.isBlank()) {
            final String tapEpoch = System.getenv("CLUSTER_EPOCH");
            // Same identity ClusterNodeMain uses, and unique per pod either way: the capture files
            // from all three members have to be loadable side by side in one directory.
            String member = System.getenv("CLUSTER_MEMBER_ID");
            if (member == null || member.isBlank()) {
                member = System.getenv("HOSTNAME");
            }
            kdbTap = new KdbTapWriter(new java.io.File(tapDir),
                tapEpoch == null || tapEpoch.isBlank() ? "1" : tapEpoch,
                member == null || member.isBlank() ? "0" : member, 1 << 16);
            kdbTap.start();
        }
    }

    /** Test seam for the analytical capture tap; production wires it from KDB_TAP_DIR in
     *  {@link #onStart}, which leaves an injected writer alone because the env is unset there. */
    void kdbTap(final KdbTapWriter tap) {
        this.kdbTap = tap;
    }

    /** Fresh deterministic core; package-private so unit tests can drive the record codec. */
    void initEngine() {
        initEngine(POOL_SIZE, POOL_SIZE);
    }

    /**
     * Test-only sizing seam for snapshot fixtures whose retained state is intentionally much
     * larger than the production spike defaults. Production always enters through
     * {@link #initEngine()} and therefore keeps the exact same capacities.
     */
    void initEngine(final int initialPoolSize, final int terminalRetain) {
        this.outputRing = RingBuffer.createSingleProducer(OutputEvent::new, outputRingSize());
        this.outputConsumed = new Sequence(-1);
        this.outputRing.addGatingSequences(outputConsumed);
        this.risk = new BlpRiskState(MAX_ACCOUNTS, MAX_SECURITIES, POOL_SIZE, IDEMPOTENCY_CAPACITY,
            CREDIT_LIMIT_TICKS, MAX_ORDER_QUANTITY, MAX_ORDER_NOTIONAL_TICKS, PRICE_MAX_AGE_MILLIS,
            new RiskMetrics());
        // fillFullThreshold 0: the crossing book has no threshold-driven half-fill policy (YU13).
        this.outputs = new OutputPublisher(outputRing, this::drainOnBackpressure);
        this.engine = new MatchingEngine(outputs, new HotPathMetrics(),
            MAX_SECURITIES, 0, initialPoolSize, POOL_SIZE, terminalRetain, risk);
        this.nextOrderRef = 1;
        this.externalOrderRefs = 0;
        this.highestIssuedRef = 0;
        this.appliedSeq = 0;
        this.snapshotHeaderSeen = false;
        this.recordTypesSeen = 0;
        this.restoredQueueDepth = -1L;
        this.phase = PHASE_OPEN;   // decision (a): a fresh epoch, and a member that restores nothing, is OPEN
        this.queuedOrders.clear();
        this.queuedByClientKey.clear();
        java.util.Arrays.fill(tickerById, null);
        this.nextSymbolId = 0;
        this.contracts.clear();
        java.util.Arrays.fill(fxUsdTicksPerCurrency, 0L);
    }

    @Override
    public void onSessionOpen(final ClientSession session, final long timestamp) {
    }

    @Override
    public void onSessionClose(final ClientSession session, final long timestamp, final CloseReason closeReason) {
    }

    @Override
    public void onSessionMessage(final ClientSession session, final long timestamp,
                                 final DirectBuffer buffer, final int offset, final int length,
                                 final Header header) {
        final int templateId = codec.templateIdOf(buffer, offset, length);
        if (templateId == 7) { // SymbolRegisterMessage
            onSymbolRegister(session, buffer, offset, length);
            return;
        }
        if (templateId == 8) { // RiskExtractMessage (YU15)
            onRiskExtract(session, buffer, offset, length);
            return;
        }
        if (codec.tryDecodeInput(buffer, offset, length, event) != AeronReplicationCodec.OK) {
            return; // fail closed: unknown schema/template/version never reaches the engine (FR-AC04)
        }
        // Ack-correlation fix (option B): the ingress inputSeq slot carries the gateway's request
        // id (0 from any producer that names none). Captured HERE because the sequencer overwrites
        // event.seq with ++appliedSeq below; echoed into bytes 24..31 of every ack this apply emits.
        applyRequestId = event.seq;
        if (event.type == InputEvent.TYPE_FX_RATE) {
            // YU17 FX-rate fix: sequenced like every command, applied here, never handed to the
            // engine — the rate is credit-gate state, not an instrument. See onFxRate.
            onFxRate();
            return;
        }
        if (event.type == InputEvent.TYPE_SESSION_CONTROL) {
            // YU17 (ADR-069): the venue phase is a sequenced command applied HERE, never handed to
            // the engine — same posture as the FX rate above. Its apply may release or cancel the
            // whole queue, so it is the one control that emits order-lifecycle egress.
            onSessionControl(session, timestamp);
            return;
        }
        if (event.type == InputEvent.TYPE_SWAP_BOOK || event.type == InputEvent.TYPE_SWAPTION_BOOK) {
            // YU17 (ADR-062): an OTC swap is sequenced like every other command — it is a committed
            // log entry, so replay and every member reach the same contract store — but it is
            // applied HERE and never handed to the engine. There is nothing for the engine to do
            // with it: no book to rest in, no counterparty to cross, no position to accumulate.
            // Routing it around MatchingEngine is what makes "a swap changes nothing for the
            // instruments that already work" a structural fact rather than a claim.
            onSwapBook(session);
            return;
        }
        // OTEL-01: derive this order's trace identity BEFORE the sequenced generator overwrites
        // orderRef below — at this instant the decoded event holds exactly the field values the
        // gateway held when it made its own sampling decision, so the two derivations agree without
        // a single byte of trace context having crossed the log. Read-only: nothing here is written
        // back to the event, emitted, or branched on by the engine.
        final long traceKey = traces != null && role == Cluster.Role.LEADER
            ? OrderTrace.keyOf(event.priceTicks, event.orderRef) : 0L;
        // OTEL-01 follow-up: the head verdict is now a flag, not the gate — a rejected order is
        // traced whatever it said (OrderTrace.escalate), and that is only knowable after apply. So
        // the apply-start timestamp is taken for every keyed order rather than the sampled fraction:
        // one extra nanoTime, alongside the ingressNanos read this path already does, and only while
        // OTEL_TRACES=1. With tracing off traceKey is 0 and this path is byte-for-byte what the
        // allocation gates and the Epsilon-GC proofs have always measured.
        final boolean traceSampled = OrderTrace.sampled(traceKey, traceMask);
        // Stamp the read-model row ONCE, on the order's own NEW. A CANCEL/REPLACE derives a
        // DIFFERENT key (keyOf falls back to the target orderRef when there is no client key), so
        // letting a later update stamp too would overwrite the accepted order's trace with the
        // cancel's — a real id for the wrong span set. Later updates carry nothing and the read
        // model preserves what it already has.
        //
        // Gated on the HEAD verdict alone, not on escalate(): escalation is only knowable after
        // apply, and the drain has already published by then. So a rejected-but-unsampled order
        // has spans and no id on its row. With OTEL_SAMPLE_MASK=0 (this rig) nothing is unsampled.
        applyTraceKey = event.type == InputEvent.TYPE_ORDER_NEW && traceSampled ? traceKey : 0L;
        if (event.type == InputEvent.TYPE_ORDER_NEW) {
            // The generator is replicated state advanced by the committed message itself
            // (ADR-046). A duplicate retry also consumes a value — deterministic on every
            // member and replay, and never reused; the engine then answers from idempotency.
            event.orderRef = (int) nextOrderRef++;
            highestIssuedRef = Math.max(highestIssuedRef, event.orderRef);
            // YU17 (ADR-072). Counted HERE, beside the increment it shadows, and on exactly the
            // same condition — including the rejected and the retried, because they consume a ref
            // too. Anywhere later would be a second rule that could disagree with this one.
            if (InputEvent.isReplayFlow(event.accountId)) {
                externalOrderRefs++;
            }
        }
        event.seq = ++appliedSeq;
        // The unit must be read HERE, not cached in onStart: the container is told the cluster's time
        // unit when it joins the log, which is after onStart runs, so onStart still sees the default.
        // Caching it there silently left this on the millisecond branch under CLUSTER_CLOCK=nanos and
        // every commit sample went negative and was dropped. It is a field read behind the interface.
        // Null when a harness drives apply directly without a Cluster (the allocation gates do).
        final boolean nanosClusterClock = cluster != null && cluster.timeUnit() == TimeUnit.NANOSECONDS;
        // cluster time, identical on every member and replay (FR-AC06). Under CLUSTER_CLOCK=nanos the
        // cluster clock hands us epoch-NANOS; the divide is deterministic, so state is unchanged.
        event.eventTimeMillis = nanosClusterClock ? timestamp / 1_000_000L : timestamp;
        event.ingressNanos = System.nanoTime(); // telemetry only, never state
        // LATENCY-01 Phase B (leader only, side-channel — never touches replicated state): the
        // consensus commit round-trip = now - the sequencing timestamp, both read on the leader from
        // the SAME clock source as the cluster clock; the apply span is timed around onEvent+
        // drainOutputs below. Only the LEADER's commit-to-apply equals the gateway's black box, so
        // record there alone. LATENCY-02: on the ms clock this is a 1ms quantum per sample — prefer
        // CLUSTER_CLOCK=nanos, which resolves the real distribution.
        final boolean timeThis = latency != null && role == Cluster.Role.LEADER;
        final long applyStartNanos = timeThis || traceKey != 0L ? System.nanoTime() : 0L;
        if (timeThis) {
            if (nanosClusterClock) {
                latency.recordCommitNanos(NanosClusterClock.epochNanos() - timestamp);
            } else {
                latency.recordCommitMillis(System.currentTimeMillis(), timestamp);
            }
        }
        activeSession = session; // backpressure drain target while the engine emits (same thread)
        applyingMarketTrade = event.type == InputEvent.TYPE_TRADE_NEW;
        directAckKind = 0; // OTEL-01 follow-up: set by drainOutputs below (incl. the backpressure drain)
        // YU17 (ADR-069 §1.3): the session gate. It sits HERE — after the sequenced generator has
        // assigned this order's ref and after event time is stamped, and before the engine sees
        // anything. Both halves matter: a queued order must already HOLD its ref (the open releases
        // it without re-sequencing, so cross-epoch ref monotonicity and the client's ack
        // correlation both survive), and a refusal while CLOSED must still consume a ref value
        // deterministically, exactly as a duplicate retry does today.
        if (phaseAdmits()) {
            engine.onEvent(event, appliedSeq, true);
        }
        activeSession = null;
        drainOutputs(session);
        applyingMarketTrade = false;
        if (timeThis) {
            latency.recordApplyNanos(System.nanoTime() - applyStartNanos);
        }
        // OTEL-01: the two member-side spans, both children of the gateway's cluster.consensus span
        // — whose id this member reconstructs from the idempotency key rather than receiving. That
        // reconstruction IS the consensus-boundary crossing. Emitted after apply so nothing here can
        // sit between a decision and its egress; two 64-byte ring writes on the sampled fraction.
        //
        // OTEL-01 follow-up: `directAckKind` is the byte this apply just produced and the gateway is
        // about to read off the egress ack, so `escalate` is the SAME predicate on the SAME input on
        // both sides of consensus. That is what keeps a reject's trace whole: neither tier tells the
        // other it escalated, they both simply see the rejection.
        if (traceKey != 0L && (traceSampled || OrderTrace.escalate(directAckKind))) {
            final long hi = OrderTrace.traceIdHi(traceKey);
            final long lo = OrderTrace.traceIdLo(traceKey);
            final long parent = OrderTrace.clusterSpanId(traceKey);
            // commit = sequenced -> apply-start. Both ends are the LEADER's own reading of the same
            // clock the cluster timestamps with, so the subtraction obeys the single-clock rule; the
            // ms branch carries a 1ms quantum exactly as LeaderApplyLatency documents.
            final long sequencedEpochNanos = nanosClusterClock ? timestamp : timestamp * 1_000_000L;
            final long applyStartEpochNanos = nanosClusterClock
                ? NanosClusterClock.epochNanos() : System.currentTimeMillis() * 1_000_000L;
            traces.span(hi, lo, OrderTrace.spanId(traceKey, 5), parent,
                sequencedEpochNanos, applyStartEpochNanos, SpanSink.NAME_COMMIT, event.orderRef);
            traces.span(hi, lo, OrderTrace.spanId(traceKey, 6), parent,
                OrderTrace.epochNanos(applyStartNanos), OrderTrace.epochNanos(System.nanoTime()),
                SpanSink.NAME_APPLY, event.orderRef);
        }
        if (stampFirstApplyAsLeader) {
            stampFirstApplyAsLeader = false;
            System.out.println("FIRST-APPLY atMs=" + System.currentTimeMillis() + " seq=" + appliedSeq);
        }
    }

    @Override
    public void onTimerEvent(final long correlationId, final long timestamp) {
    }

    /** Symbol registration is idempotent by ticker and sequenced like any other input; the
     *  assigned id is deterministic on every member and replay. Cold path — registration is
     *  first-sighting-rare, so the String allocation here never touches the apply hot loop. */
    private void onSymbolRegister(final ClientSession session, final DirectBuffer buffer,
                                  final int offset, final int length) {
        if (codec.tryDecodeSymbolRegister(buffer, offset, length) != AeronReplicationCodec.OK) {
            return; // fail closed
        }
        appliedSeq++;
        final String ticker = codec.symbolTicker();
        int id = symbolIdFor(ticker);
        if (id < 0) {
            if (nextSymbolId >= MAX_SECURITIES) {
                id = -1; // capacity refused; deterministic everywhere
            } else {
                id = nextSymbolId++;
                tickerById[id] = ticker;
                // YU14 (ADR-052): the multiplier is a pure function of the committed ticker,
                // derived identically on every member and replay. Cold path.
                risk.putContractMultiplier(id, OccSymbol.multiplierFor(ticker));
                // YU16 (ADR-060): same pattern for the bond book grid.
                final long derivedTickPx = derivedBookTickPxFor(ticker);
                if (derivedTickPx != 0L) {
                    engine.overrideBookTickPx(id, derivedTickPx);
                }
            }
        }
        ackBuffer.putLong(0, appliedSeq);
        ackBuffer.putInt(8, id);
        ackBuffer.putByte(12, KIND_SYMBOL_REGISTERED);
        ackBuffer.putLong(13, codec.symbolRequestId());
        ackBuffer.putByte(21, (byte) 0);
        ackBuffer.putByte(22, (byte) 0);
        ackBuffer.putByte(23, (byte) 0);
        ackBuffer.putLong(24, 0L); // symbol acks correlate by their own requestId at 13
        offerEgress(session);
    }

    /**
     * Apply a sequenced FX rate (YU17 FX-rate fix). The credit gate values every swap notional in
     * the limit currency (USD), and the rate it converts with must reach every member as a
     * committed log entry and live in the snapshot — a rate resolved by any member at apply time
     * diverges the members permanently. Same posture as a price mark: an input event, replicated
     * state, nothing looked up.
     *
     * <p>Fails closed and silently on an index or rate this build cannot hold: the gateway
     * validates both before sequencing, so a bad value here is a later build's log entry, and
     * "ignored deterministically on every member" is the only apply that cannot diverge. No
     * egress ack, exactly as the *_CONTROL commands answer nothing.
     *
     * <p>Cold path — rates change a handful of times a day.
     */
    private void onFxRate() {
        appliedSeq++;
        final int currencyIndex = event.fxCurrencyIndex();
        if (currencyIndex > 0 && currencyIndex < fxUsdTicksPerCurrency.length
            && event.fxRateTicks() > 0L) {
            fxUsdTicksPerCurrency[currencyIndex] = event.fxRateTicks();
        }
    }


    // ----- the session machine (YU17, ADR-069) -----------------------------------------------

    /**
     * Apply a sequenced phase command. The target phase rides the {@code side} slot
     * (0=CLOSED, 1=PRE_OPEN, 2=OPEN) and the operator's correlation id rides {@code
     * clientOrderKey}; the command itself is offered with request id 0, so the lifecycle acks the
     * OPEN release emits below (which echo {@code applyRequestId} at bytes 24..31) can complete
     * nothing. See {@link #KIND_SESSION_PHASE}.
     *
     * <p>Two transitions do work beyond setting a byte:
     * <ul>
     *   <li><b>-&gt; OPEN</b> releases the queue, replaying each held order through the engine's
     *       normal path in INSERTION ORDER, inside this one apply;</li>
     *   <li><b>PRE_OPEN -&gt; CLOSED</b> cancels the queue (decision b): a halt that pending client
     *       orders can block is not a halt.</li>
     * </ul>
     *
     * <p>Fails closed and silently on an unknown phase value, the same posture as
     * {@link #onFxRate}: the gateway validates before sequencing, so a bad value here is a later
     * build's log entry, and "ignored identically on every member" is the only apply that cannot
     * diverge.
     */
    private void onSessionControl(final ClientSession session, final long timestamp) {
        // Sequenced and time-stamped exactly as the order path is: the queue's cancel/release acks
        // are ordinary order-lifecycle events and must carry this apply's position and cluster
        // time, not the gateway's zeroed ingress slots.
        event.seq = ++appliedSeq;
        final boolean nanosClusterClock = cluster != null && cluster.timeUnit() == TimeUnit.NANOSECONDS;
        event.eventTimeMillis = nanosClusterClock ? timestamp / 1_000_000L : timestamp;
        event.ingressNanos = System.nanoTime();   // telemetry only, never state
        final byte target = event.side;
        if (target < PHASE_CLOSED || target > PHASE_OPEN) {
            return;
        }
        // The engine emits into the ring on this thread during the release, so the backpressure
        // drain needs its egress target exactly as an ordinary apply does.
        activeSession = session;
        applyRequestId = 0L;   // §1.2: released orders' acks must complete no pending
        if (target == PHASE_OPEN) {
            releaseQueue();
        } else if (target == PHASE_CLOSED) {
            cancelQueue();
        }
        phase = target;
        activeSession = null;
        drainOutputs(session);

        ackBuffer.putLong(0, appliedSeq);
        ackBuffer.putInt(8, phase);
        ackBuffer.putByte(12, KIND_SESSION_PHASE);
        ackBuffer.putLong(13, event.clientOrderKey());
        ackBuffer.putByte(21, (byte) 0);
        ackBuffer.putByte(22, (byte) 0);
        ackBuffer.putByte(23, (byte) 0);
        ackBuffer.putLong(24, 0L); // phase acks correlate by their OWN request id at 13 — never here
        offerEgress(session);
    }

    /**
     * The session gate (§1.3). Returns true when the decoded input should reach the engine.
     *
     * <p>The table, and the two rows that are decisions rather than defaults:
     * <ul>
     *   <li><b>PRICE_TICK passes in EVERY phase</b> (decision 6): the feed never halts, so the band
     *       keeps tracking the market through a halt and the open is judged against a current
     *       reference rather than yesterday's;</li>
     *   <li><b>CANCEL is allowed while CLOSED</b> (decision c, which overrode the recommendation):
     *       a cancel only ever REDUCES exposure — it cannot cross, trade, move a price, or re-open
     *       a halted book — so permitting it during a halt is safer than forbidding it. Forbidding
     *       it would lock a client into a resting order until the open, where it may fill on terms
     *       they never saw.</li>
     * </ul>
     *
     * <p>Controls, FX rates, symbol registration, risk-extract markers and OTC bookings never reach
     * here at all: the first three are routed by template above and the last two by type, before
     * this gate. That routing IS decision (d) — the halt is the VENUE'S BOOK, and bilateral desk
     * business never touches it.
     */
    private boolean phaseAdmits() {
        if (phase == PHASE_OPEN) {
            return true;
        }
        switch (event.type) {
            case InputEvent.TYPE_ORDER_NEW:
                if (phase == PHASE_PRE_OPEN) {
                    queueOrder();
                } else {
                    refuseNewOrder(RiskReason.MARKET_CLOSED);
                }
                return false;
            case InputEvent.TYPE_ORDER_CANCEL:
                // PRE_OPEN: a cancel naming a QUEUED order removes it from the queue and acks
                // CANCELED here; anything else is an ordinary engine cancel of a resting order.
                // CLOSED: decision (c) — always the engine's.
                return !(phase == PHASE_PRE_OPEN && cancelQueued(event.orderRef));
            case InputEvent.TYPE_ORDER_REPLACE:
            case InputEvent.TYPE_FORCE_FILL:
                // v1: refused in both halted phases. A queue-aware replace is the stated upgrade
                // path; a force fill has no meaning against a book nobody may trade into.
                engine.refuseTargetedRequest(event, RiskReason.MARKET_CLOSED);
                return false;
            case InputEvent.TYPE_TRADE_NEW:
                // A market trade is its own correlation path (/trades reads KIND_TRADE_*), so the
                // refusal has to be emitted in that shape or the gateway waits for an ack that
                // never comes.
                outputs.emitTradeDecision(event.seq, (byte) RiskReason.MARKET_CLOSED.ordinal(),
                    false, event.ingressNanos);
                return false;
            default:
                // PRICE_TICK, SNAPSHOT markers and every *_CONTROL: pass, in every phase.
                return true;
        }
    }

    /** Hold a new order for the open (§1.4). The ref is already assigned — the client's ack names
     *  the very order the release will replay. */
    private void queueOrder() {
        final long clientKey = event.clientOrderKey();
        if (clientKey != 0L) {
            final Integer existing = queuedByClientKey.get(clientKey);
            if (existing != null) {
                // Idempotent retry: re-ack the ORIGINAL queued order, never queue a second copy.
                // (The retry still consumed a ref from the generator, deterministically on every
                // member — the same posture a duplicate retry has had since FR-IMRG14.)
                emitQueuedAck(queuedOrders.get(existing));
                return;
            }
        }
        if (queuedOrders.size() >= MAX_QUEUED_ORDERS) {
            refuseNewOrder(RiskReason.CAPACITY);
            return;
        }
        final long[] row = new long[] {
            event.orderRef, event.accountId, event.securityId, event.side,
            event.qty, event.limitPx, clientKey, event.eventTimeMillis };
        if (clientKey != 0L) {
            queuedByClientKey.put(clientKey, queuedOrders.size());
        }
        queuedOrders.add(row);
        emitQueuedAck(row);
    }

    /** Remove a queued order by ref and ack it CANCELED; false when the ref names no queued order
     *  (in which case the cancel belongs to the engine). Linear scan over a cold-path list. */
    private boolean cancelQueued(final int orderRef) {
        for (int i = 0; i < queuedOrders.size(); i++) {
            if ((int) queuedOrders.get(i)[0] == orderRef) {
                final long[] row = queuedOrders.remove(i);
                reindexQueueKeys();
                emitQueuedTerminal(row, RiskReason.ACCEPTED);
                return true;
            }
        }
        return false;
    }

    /**
     * Decision (b): a close with a non-empty queue CANCELS it, one {@code SESSION_CANCELED} per
     * entry. That reason is deliberately NOT {@code MARKET_CLOSED} — "we refused you at the door"
     * and "the order you already hold is gone" are different events calling for different client
     * actions, and one reason for both would make them indistinguishable.
     */
    private void cancelQueue() {
        for (final long[] row : queuedOrders) {
            emitQueuedTerminal(row, RiskReason.SESSION_CANCELED);
        }
        queuedOrders.clear();
        queuedByClientKey.clear();
    }

    /**
     * The open (§1.5): replay every held order through the engine's ordinary ORDER_NEW path, in
     * insertion order, inside this one apply.
     *
     * <p><b>Band and risk are judged HERE, not at queue time.</b> The band must be judged against
     * the open's reference (the feed kept ticking through the halt and the band re-anchors across
     * it), and reservations must not be held against control state that can change while an order
     * is queued. A queue-time decision would also have made the queue-time ack a lie.
     *
     * <p><b>ZERO new order refs are issued.</b> Every released order already holds the ref it was
     * given at sequencing, and the engine's generator is untouched by this loop. Re-sequencing on
     * release would break cross-epoch ref monotonicity and the client's ack correlation both.
     *
     * <p>Determinism is trivial: one apply, one thread, one order — identical on every member and
     * on replay. The cascade is bounded by {@link #MAX_QUEUED_ORDERS} and, structurally, by
     * {@code drainOnBackpressure}.
     */
    private void releaseQueue() {
        if (queuedOrders.isEmpty()) {
            return;
        }
        final java.util.List<long[]> released = new java.util.ArrayList<>(queuedOrders);
        queuedOrders.clear();
        queuedByClientKey.clear();
        for (final long[] row : released) {
            releaseEvent.type = InputEvent.TYPE_ORDER_NEW;
            releaseEvent.orderRef = (int) row[0];
            releaseEvent.accountId = (int) row[1];
            releaseEvent.securityId = (int) row[2];
            releaseEvent.side = (byte) row[3];
            releaseEvent.qty = (int) row[4];
            releaseEvent.limitPx = row[5];
            releaseEvent.setClientOrderKey(row[6]);
            releaseEvent.eventTimeMillis = row[7];
            releaseEvent.seq = appliedSeq;
            releaseEvent.ingressNanos = event.ingressNanos;
            engine.onEvent(releaseEvent, appliedSeq, true);
        }
    }

    /** Rebuild the transient key index after a removal shifts every later entry's position. */
    private void reindexQueueKeys() {
        queuedByClientKey.clear();
        for (int i = 0; i < queuedOrders.size(); i++) {
            final long key = queuedOrders.get(i)[6];
            if (key != 0L) {
                queuedByClientKey.put(key, i);
            }
        }
    }

    /** The client's direct ack for a queued order: ACCEPTED, {@code STATUS_QUEUED} (§1.6). It
     *  completes the pending, names the orderRef, and says QUEUED rather than working — and it
     *  rides the ordinary order-lifecycle egress, so the read model and the console see the order
     *  exist in that state. The REST ack itself is unchanged; QUEUED is read from the read model. */
    private void emitQueuedAck(final long[] row) {
        loadSessionOrder(row);
        sessionOrder.status = finos.traderx.ordermatcher.lmax.RestingOrder.STATUS_QUEUED;
        sessionOrder.riskReason = (byte) RiskReason.ACCEPTED.ordinal();
        outputs.emitOrderUpdate(sessionOrder, event.seq, OutputEvent.FLAG_CREATE, true,
            engine.markPx((int) row[2]), event.ingressNanos);
    }

    /** A queued order leaving the queue without ever reaching a book: CANCELED, carrying the
     *  reason that says WHICH session event removed it. */
    private void emitQueuedTerminal(final long[] row, final RiskReason reason) {
        loadSessionOrder(row);
        sessionOrder.status = finos.traderx.ordermatcher.lmax.RestingOrder.STATUS_CANCELED;
        sessionOrder.remaining = 0;
        sessionOrder.riskReason = (byte) reason.ordinal();
        sessionOrder.updatedAtMillis = event.eventTimeMillis;
        outputs.emitOrderUpdate(sessionOrder, event.seq, OutputEvent.FLAG_CANCEL, true,
            engine.markPx((int) row[2]), event.ingressNanos);
    }

    private void loadSessionOrder(final long[] row) {
        sessionOrder.reset();
        sessionOrder.orderRef = (int) row[0];
        sessionOrder.accountId = (int) row[1];
        sessionOrder.securityId = (int) row[2];
        sessionOrder.side = (byte) row[3];
        sessionOrder.quantity = (int) row[4];
        sessionOrder.remaining = (int) row[4];
        sessionOrder.limitPx = row[5];
        sessionOrder.createdAtMillis = row[7];
        sessionOrder.updatedAtMillis = row[7];
    }

    /** Refuse an ORDER_NEW the session will not admit. Journaled and addressable for audit exactly
     *  as a risk rejection is, and never resting or reserving — the engine never saw it. */
    private void refuseNewOrder(final RiskReason reason) {
        sessionOrder.reset();
        sessionOrder.orderRef = event.orderRef;
        sessionOrder.accountId = event.accountId;
        sessionOrder.securityId = event.securityId;
        sessionOrder.side = event.side;
        sessionOrder.quantity = event.qty;
        sessionOrder.remaining = 0;
        sessionOrder.limitPx = event.limitPx;
        sessionOrder.status = finos.traderx.ordermatcher.lmax.RestingOrder.STATUS_REJECTED;
        sessionOrder.riskReason = (byte) reason.ordinal();
        sessionOrder.createdAtMillis = event.eventTimeMillis;
        sessionOrder.updatedAtMillis = event.eventTimeMillis;
        outputs.emitOrderUpdate(sessionOrder, event.seq, OutputEvent.FLAG_REJECT, true,
            engine.markPx(event.securityId), event.ingressNanos);
    }

    /** The venue phase, for the member health surface (§1.7). */
    public String phaseName() {
        return PHASE_NAMES[phase];
    }

    /** Phase ordinal — package-private test seam. */
    byte phase() {
        return phase;
    }

    /** How many orders the pre-open queue holds (§1.7: "was the market open, and is anything
     *  queued?" must be answerable in ONE request). */
    public int queueDepth() {
        return queuedOrders.size();
    }

    /** The queued rows in insertion order — package-private test seam; the shell proofs read
     *  {@link #queueDepth()} off /health. */
    java.util.List<long[]> queuedOrderTuples() {
        return queuedOrders;
    }

    /** USD ticks per one unit of the currency at {@code currencyIndex}; 0 = no rate available.
     *  Index 0 is the limit currency itself, identity by construction. Package-private: tests. */
    long fxUsdRateTicks(final int currencyIndex) {
        if (currencyIndex == 0) {
            return Px.SCALE;
        }
        return currencyIndex > 0 && currencyIndex < fxUsdTicksPerCurrency.length
            ? fxUsdTicksPerCurrency[currencyIndex] : 0L;
    }

    /**
     * Book an OTC interest-rate swap (YU17, ADR-062). A sequenced command that creates a contract
     * and nothing else: no order, no book entry, no position, no trade, no price.
     *
     * <p>The contract id is the consensus sequence the booking landed at. That needs no generator,
     * no snapshot header field and no restore invariant of its own: one sequence applies one
     * command, so it is unique within the epoch by construction, and it is derivable from the log
     * alone — a replay to N reproduces the identical ids without carrying any extra state. (It is
     * unique WITHIN an epoch, exactly as an orderRef is. Nothing here makes ids unique ACROSS a
     * wiped epoch; the extract's write-once sink refuses a colliding key loudly rather than
     * silently mixing two epochs' contracts, which is the same posture the trade table has.)
     *
     * <p>Order of the two refusals is deliberate. Capacity is a pure function of replicated state,
     * so it is checked BEFORE the risk gate — a booking refused for capacity must not first accrue
     * its notional into the account's credit usage for a contract that will not exist.
     *
     * <p>Cold path — a handful of bookings a day. It allocates and it is nowhere near the order
     * hot loop, which is the point of keeping it out of {@code MatchingEngine} entirely.
     */
    private void onSwapBook(final ClientSession session) {
        appliedSeq++;
        final long clientKey = event.clientOrderKey();
        long contractId = 0L;
        byte reason = (byte) RiskReason.ACCEPTED.ordinal();
        boolean booked = false;

        // A retried booking must answer with the ORIGINAL contract, never create a second one: a
        // duplicated 10mm swap confirmation is precisely the failure the idempotency table exists
        // for. The store index (+1, since 0 is the table's "no ref" sentinel) is what was
        // remembered against the key, so the original id is a direct lookup.
        final int priorRef = clientKey == 0L ? -1 : risk.existingOrderRef(clientKey);
        if (priorRef > 0 && priorRef <= contracts.size()) {
            contractId = contracts.get(priorRef - 1)[0];
            booked = true;
        } else if (contracts.size() >= MAX_CONTRACTS) {
            reason = (byte) RiskReason.CAPACITY.ordinal();
        } else {
            // The FX-rate fix: credit is limited in USD, so the notional is valued in USD BEFORE
            // the gate — notional units x usdRateTicks IS the USD notional in Px ticks (USD's rate
            // is identity, so a USD swap consumes exactly what it always did). No rate for the
            // convention's currency — including a convention this build does not know, whose
            // currency is unknowable — refuses the booking rather than admitting the raw contract
            // notional against a USD limit: under-reserving EUR/GBP by the FX rate and
            // over-reserving JPY ~150x is the measured defect this converts away.
            final int currencyIndex =
                SwapConventions.currencyIndexOfConvention(event.swapConventionIndex());
            final long usdRateTicks = fxUsdRateTicks(currencyIndex);
            long notionalTicks;
            try {
                notionalTicks = usdRateTicks <= 0L ? -1L
                    : Math.multiplyExact((long) event.swapNotional(), usdRateTicks);
            } catch (final ArithmeticException ex) {
                notionalTicks = Long.MAX_VALUE; // overflow: let the gate refuse it as ORDER_NOTIONAL
            }
            final RiskReason decision = notionalTicks < 0L
                ? RiskReason.PRICE_MISSING
                : risk.decideSwapBooking(clientKey, 0L, event.accountId,
                    notionalTicks, contracts.size() + 1);
            reason = (byte) decision.ordinal();
            if (decision == RiskReason.ACCEPTED) {
                contractId = appliedSeq;
                final boolean swaption = event.type == InputEvent.TYPE_SWAPTION_BOOK;
                contracts.add(new long[] {
                    contractId,
                    event.accountId,
                    event.swapPaysFixed() ? 1L : 0L,
                    event.swapNotional(),
                    event.swapFixedRateTicks(),
                    event.swapConventionIndex(),
                    event.swapEffectiveEpochDay(),
                    event.swapMaturityEpochDay(),
                    swaption ? PRODUCT_SWAPTION : PRODUCT_SWAP,
                    swaption ? event.swaptionExpiryEpochDay() : 0L,
                    swaption ? event.swaptionExerciseStyle() : 0L });
                booked = true;
            }
        }

        ackBuffer.putLong(0, contractId);
        ackBuffer.putInt(8, booked ? 1 : 0);
        ackBuffer.putByte(12, KIND_SWAP_BOOKED);
        ackBuffer.putLong(13, clientKey);   // correlation, as the symbol/extract acks use this slot
        ackBuffer.putByte(21, (byte) 0);
        ackBuffer.putByte(22, reason);
        ackBuffer.putByte(23, (byte) 0);
        ackBuffer.putLong(24, applyRequestId); // uniform echo; the swap path still keys on 13
        offerEgress(session);
    }

    /**
     * YU15 EOD risk-extract marker (ADR-055). A sequenced command that mutates nothing: its only
     * effect is to name a consensus sequence N. Because the marker is a committed log entry, N is
     * agreed by consensus rather than sampled by a reader, so the state every member renders at N
     * is a consistent cut by construction — no quiesce, no locks, no read-model lag.
     *
     * <p>Every member renders and hashes the cut; the identical hash across members is the
     * determinism proof (and the same rendering happens again on any replay to N). Only the leader
     * hands it to the NATS bridge, so followers never duplicate.
     *
     * <p>Cold path — once per EOD batch. It allocates freely (the render is inherently O(positions))
     * and is deliberately kept off the order-flow branch above so the hot path is untouched.
     */
    private void onRiskExtract(final ClientSession session, final DirectBuffer buffer,
                               final int offset, final int length) {
        if (codec.tryDecodeRiskExtract(buffer, offset, length) != AeronReplicationCodec.OK) {
            return; // fail closed: a malformed marker never names a sequence
        }
        appliedSeq++;
        final java.util.List<long[]> positions = engine.positionTuples();
        // YU17 (D3): ONE cut, taken at one sequence, carrying both the netted positions and the
        // per-trade OTC contracts. Two artifacts are rendered downstream from these same bytes and
        // therefore share a sequence, a session date and a cutSha256 by construction rather than by
        // the producer being careful.
        final String cut = RiskExtractCut.render(appliedSeq, codec.extractSessionDateEpochDay(),
            codec.extractPriceVersion(), positions, engine.priceTuples(),
            tickerById, risk::contractMultiplier, contracts);
        lastExtractCutSeq = appliedSeq;
        lastExtractCutSha = RiskExtractCut.sha256(cut);
        // Stamped on every member so a cross-member diff needs nothing but the pod logs. Only a
        // real member stamps it: a ClusterRecon shadow replaying the whole log would otherwise
        // re-emit every historical marker line into the pod log the proofs grep, on every reindex.
        if (cluster != null) {
            System.out.println("RISK-EXTRACT-CUT seq=" + appliedSeq + " rows=" + positions.size()
                + " contracts=" + contracts.size()
                + " sha256=" + lastExtractCutSha + " role=" + role);
        }
        if (role == Cluster.Role.LEADER && extractBridge != null) {
            extractBridge.offer(cut);
        }
        ackBuffer.putLong(0, appliedSeq);
        ackBuffer.putInt(8, positions.size());
        ackBuffer.putByte(12, KIND_RISK_EXTRACT_MARKED);
        ackBuffer.putLong(13, codec.extractRequestId());
        ackBuffer.putByte(21, (byte) 0);
        ackBuffer.putByte(22, (byte) 0);
        ackBuffer.putByte(23, (byte) 0);
        ackBuffer.putLong(24, 0L); // extract acks correlate by their own requestId at 13
        offerEgress(session);
    }

    /** Read-side output tap; see {@link #outputSink}. Package-private: only {@code ClusterNodeMain}
     *  (live blotter) and {@code ClusterRecon} (shadow replay) wire one. */
    void outputSink(final java.util.function.Consumer<OutputEvent> sink) {
        this.outputSink = sink;
    }

    /** Committed ticker for a security id, or null if that id has never been registered. */
    public String tickerFor(final int securityId) {
        return securityId < 0 || securityId >= MAX_SECURITIES ? null : tickerById[securityId];
    }

    public int symbolIdFor(final String ticker) {
        for (int i = 0; i < nextSymbolId; i++) {
            if (ticker.equals(tickerById[i])) {
                return i;
            }
        }
        return -1;
    }

    public int symbolCount() {
        return nextSymbolId;
    }

    /** YU17: booked OTC contracts in booking order. Read for the cut render and by tests. */
    public java.util.List<long[]> contractTuples() {
        return contracts;
    }

    public int contractCount() {
        return contracts.size();
    }

    @Override
    public void onTakeSnapshot(final ExclusivePublication snapshotPublication) {
        writeSnapshot((buffer, offset, length) -> {
            idle.reset();
            while (snapshotPublication.offer(buffer, offset, length) < 0) {
                idle.idle();
            }
        });
        snapshotsTaken++;
    }

    @Override
    public void onRoleChange(final Cluster.Role newRole) {
        this.role = newRole;
        // Joint-plan Phase 0: the system-facing SLO clock stops at the first committed apply
        // AS LEADER (a fast role change during a snapshot barrier is not "serving"), so arm the
        // FIRST-APPLY stamp here. Cold branch: one println per election, never in the
        // allocation-gate window (no role changes there).
        if (newRole == Cluster.Role.LEADER) {
            stampFirstApplyAsLeader = true;
        }
        System.out.println("ROLE-CHANGE role=" + newRole + " atMs=" + System.currentTimeMillis());
    }

    @Override
    public void onTerminate(final Cluster cluster) {
        if (tradeBridge != null) {
            tradeBridge.stop();
        }
        if (orderBridge != null) {
            orderBridge.stop();
        }
        if (kdbTap != null) {
            kdbTap.stop();
        }
        if (extractBridge != null) {
            extractBridge.stop();
        }
    }

    // ----- snapshot record codec (package-private: unit-tested without a cluster) --------------

    /** Serialize the complete deterministic state as self-describing records. */
    void writeSnapshot(final SnapshotWriter writer) {
        snapshotBuffer.putInt(0, T_HEADER);
        snapshotBuffer.putInt(4, SNAPSHOT_FORMAT);
        snapshotBuffer.putLong(8, nextOrderRef);
        snapshotBuffer.putLong(16, highestIssuedRef);
        snapshotBuffer.putLong(24, appliedSeq);
        snapshotBuffer.putLong(32, engine.tradeCounter());
        snapshotBuffer.putInt(40, engine.bookLevels());
        snapshotBuffer.putLong(44, engine.bookTickPx());
        // Format 9 (YU17, ADR-072): the two REPLAYED halves. Both are subtracted from a global
        // counter to give an operator-only reading, so a member that restored one of them as zero
        // would answer a different operator count for the same committed log — which is exactly
        // the disagreement the quiesce in lib-consensus-readings.sh exists to refuse.
        snapshotBuffer.putLong(52, externalOrderRefs);
        snapshotBuffer.putLong(60, engine.externalTradeLegs());
        writer.write(snapshotBuffer, 0, 68);

        writeTuple(writer, T_POLICY, risk.policyTuple());
        for (final long[] account : risk.accountTuples()) {
            writeTuple(writer, T_ACCOUNT, account);
        }
        for (final long[] security : risk.securityTuples()) {
            // Format 3: append the contract multiplier as the 6th column. Write the effective
            // value (never 0) so restore can fail closed on anything below 1.
            final long multiplier = risk.contractMultiplier((int) security[0]);
            writeTuple(writer, T_SECURITY, new long[] { security[0], security[1], security[2],
                security[3], security[4], multiplier == 0L ? 1L : multiplier });
        }
        for (int id = 0; id < nextSymbolId; id++) {
            final byte[] ascii = tickerById[id].getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            snapshotBuffer.putInt(0, T_SYMBOL);
            snapshotBuffer.putInt(4, id);
            snapshotBuffer.putInt(8, ascii.length);
            snapshotBuffer.putBytes(12, ascii);
            writer.write(snapshotBuffer, 0, 12 + ascii.length);
        }
        // Retention (insertion) order: restore preserves the eviction frontier (FR-IMRG14).
        for (final long[] entry : risk.idempotencyTuples()) {
            writeTuple(writer, T_IDEMPOTENCY, entry);
        }
        // YU17 contracts in booking order. That order is load-bearing twice over: it is what the
        // rendered cut iterates (so restore must reproduce it exactly, byte for byte), and the
        // idempotency table remembers a contract by its INDEX in this list — restoring them in a
        // different order would silently repoint every retried booking at a different contract.
        // Written AFTER T_IDEMPOTENCY for that reason: both are restored, and neither validates
        // the other, so the invariant lives here in the write order.
        for (final long[] contract : contracts) {
            writeTuple(writer, T_CONTRACT, contract);
        }
        // FX rates (format 7): index 0 (USD) is identity by construction and never written.
        for (int i = 1; i < fxUsdTicksPerCurrency.length; i++) {
            if (fxUsdTicksPerCurrency[i] != 0L) {
                writeTuple(writer, T_FX_RATE, new long[] { i, fxUsdTicksPerCurrency[i] });
            }
        }
        // YU17 format 8: the venue session, ALWAYS written — an absent T_SESSION is a defect, and
        // the restore-side presence check (REQUIRED_RECORD_TYPES) says so.
        //
        // queueDepth is read from the LIVE queue here, deliberately, and never tallied from the
        // loop below. A count the writer derives from its own output is a checksum of its own bug:
        // it would agree with a write loop that emitted nothing and catch exactly the defect it
        // exists to catch, never.
        writeTuple(writer, T_SESSION, new long[] { phase, queuedOrders.size() });
        // Insertion order, which IS the release order at the open (§1.4). Restore refuses rows out
        // of that order, the same rule T_CONTRACT follows and for the same reason: nothing else
        // validates it, so the invariant lives in the write order and the reader's check.
        for (final long[] queued : queuedOrders) {
            writeTuple(writer, T_QUEUED_ORDER, queued);
        }
        for (final long[] position : engine.positionTuples()) {
            writeTuple(writer, T_POSITION, position);
        }
        for (final long[] price : engine.priceTuples()) {
            writeTuple(writer, T_PRICE, price);
        }
        // Book geometry BEFORE order rows: restore must rebuild each book's grid AND anchor first
        // so open rows re-enter their exact original price levels (replica-identical bands).
        // Format 8: three columns — {securityId, baseLevel, tickPx}. The tick rides beside the
        // anchor because the anchor is DENOMINATED in it (design §2.4).
        for (final long[] bookBase : engine.bookBaseTuples()) {
            writeTuple(writer, T_BOOK, bookBase);
        }
        // Open rows first in ascending ref order (arrival order — restore re-appends them into
        // their levels in the exact original FIFO), then retained terminal rows in exact
        // eviction-FIFO order — restore re-marks terminals in write order, keeping eviction
        // replica-identical.
        final int[] terminalFifo = engine.terminalOrderRefsFifo();
        final IntHashSet terminalSet = new IntHashSet(terminalFifo.length * 2);
        final long[] orderTuple = new long[MatchingEngine.SNAPSHOT_ORDER_TUPLE_LENGTH];
        for (final int ref : terminalFifo) {
            terminalSet.add(ref);
        }
        for (final int ref : engine.snapshotOrderRefsAscending()) {
            if (!terminalSet.contains(ref) && engine.copySnapshotOrderTuple(ref, orderTuple)) {
                writeTuple(writer, T_ORDER, orderTuple);
            }
        }
        for (final int ref : terminalFifo) {
            if (engine.copySnapshotOrderTuple(ref, orderTuple)) {
                writeTuple(writer, T_ORDER, orderTuple);
            }
        }
        snapshotBuffer.putInt(0, T_END);
        writer.write(snapshotBuffer, 0, 4);
    }

    /** Apply one snapshot record; returns true on the END record. Fails closed on unknown or
     *  out-of-order records and on any identifier at or beyond the restored generator. */
    boolean onSnapshotRecord(final DirectBuffer buffer, final int offset) {
        final int type = buffer.getInt(offset);
        if (!snapshotHeaderSeen && type != T_HEADER) {
            throw new IllegalStateException("snapshot corrupt: first record type " + type + ", want header");
        }
        // YU17 format 8: remember WHICH record types the stream actually carried, so finishLoad can
        // refuse a stream that terminated cleanly with whole types missing (see
        // REQUIRED_RECORD_TYPES). Restore-side only — nothing about the written format changes.
        if (type > 0 && type < 32) {
            recordTypesSeen |= 1 << type;
        }
        switch (type) {
            case T_HEADER -> {
                final int format = buffer.getInt(offset + 4);
                // Name the DIRECTION of the mismatch. "unknown snapshot format" reads as a damaged
                // file, and the only actionable fact is whether this build is too old or the
                // snapshot is. A too-new snapshot is intact and is NOT a reason to wipe the epoch —
                // rolling the members forward again restores it untouched — so the message says so.
                if (format > SNAPSHOT_FORMAT) {
                    throw new IllegalStateException("snapshot format " + format
                        + " is NEWER than this build (format " + SNAPSHOT_FORMAT + "): it was"
                        + " written by a later build and cannot be restored here. The snapshot is"
                        + " intact — roll the members FORWARD; do not wipe the epoch.");
                }
                if (format < MIN_READABLE_SNAPSHOT_FORMAT) {
                    throw new IllegalStateException("snapshot format " + format
                        + " is older than this build can restore (minimum "
                        + MIN_READABLE_SNAPSHOT_FORMAT + ", current " + SNAPSHOT_FORMAT + ")");
                }
                nextOrderRef = buffer.getLong(offset + 8);
                highestIssuedRef = buffer.getLong(offset + 16);
                appliedSeq = buffer.getLong(offset + 24);
                engine.bootstrapTradeCounter(buffer.getLong(offset + 32));
                engine.adoptBookGeometry(buffer.getInt(offset + 40), buffer.getLong(offset + 44));
                // Format 9 (ADR-072). No width branch is needed and none may be added while
                // MIN_READABLE == SNAPSHOT_FORMAT: a narrower format-8 header cannot reach here,
                // it is refused above.
                externalOrderRefs = buffer.getLong(offset + 52);
                engine.bootstrapExternalTradeLegs(buffer.getLong(offset + 60));
                // Fail closed, the same posture every other restored identifier takes: more
                // replayed refs than refs ISSUED is arithmetic this build cannot have written, and
                // the operator counter derived from it would go NEGATIVE — a proof would then read
                // "no order of mine was sequenced" off a corrupt subtraction. Stated as the
                // subtraction itself rather than as `>= nextOrderRef`, because the generator is
                // 1-based and refs issued is nextOrderRef - 1.
                if (externalOrderRefs < 0 || externalOrderRefs > Math.max(0L, nextOrderRef - 1)) {
                    throw new IllegalStateException("snapshot corrupt: externalOrderRefs "
                        + externalOrderRefs + " exceeds the " + Math.max(0L, nextOrderRef - 1)
                        + " ref(s) nextOrderRef " + nextOrderRef + " says were issued");
                }
                snapshotHeaderSeen = true;
            }
            case T_POLICY -> risk.bootstrapPolicy(new long[] {
                buffer.getLong(offset + 4), buffer.getLong(offset + 12),
                buffer.getLong(offset + 20), buffer.getLong(offset + 28) });
            case T_ACCOUNT -> risk.bootstrapAccount(
                (int) buffer.getLong(offset + 4),
                buffer.getLong(offset + 12) != 0,
                buffer.getLong(offset + 20));
            case T_SECURITY -> {
                final int securityId = (int) buffer.getLong(offset + 4);
                final long multiplier = buffer.getLong(offset + 44);
                if (multiplier < 1L) {
                    // Fail closed (FR-LEO04): a member must never enforce un-multiplied caps.
                    throw new IllegalStateException(
                        "snapshot corrupt: security " + securityId + " multiplier " + multiplier);
                }
                risk.bootstrapSecurity(securityId,
                    buffer.getLong(offset + 12) != 0,
                    buffer.getLong(offset + 20) != 0,
                    buffer.getLong(offset + 28),
                    buffer.getLong(offset + 36));
                risk.putContractMultiplier(securityId, multiplier);
            }
            case T_IDEMPOTENCY -> risk.bootstrapIdempotency(
                buffer.getLong(offset + 4),
                (int) buffer.getLong(offset + 12),
                (byte) buffer.getLong(offset + 20));
            case T_BOOK -> {
                // Format 8 (design §2.4): {securityId, baseLevel, tickPx}. Restore reads the GRID
                // instead of re-deriving it, which is what retires the unit-misreading hazard
                // class — MIN_READABLE 8 guarantees a two-column record can never arrive here, so
                // no dual-shape reader is needed.
                final int bookSecurityId = (int) buffer.getLong(offset + 4);
                final long baseLevel = buffer.getLong(offset + 12);
                final long tickPx = buffer.getLong(offset + 20);
                // Fail closed, the same posture as bootstrapOrder's off-grid/outside-band checks
                // (which remain as the second tripwire): a tick that is non-positive, does not
                // divide a cent, or exceeds the global cap without a ticker CATEGORY that says so,
                // is a grid this build cannot have written.
                final long categoryTickPx = bookSecurityId >= 0 && bookSecurityId < MAX_SECURITIES
                    ? derivedBookTickPxFor(tickerById[bookSecurityId]) : 0L;
                if (tickPx <= 0L || 10_000L % tickPx != 0L
                    || (tickPx > MatchingEngine.DEFAULT_BOOK_TICK_PX && tickPx != categoryTickPx)) {
                    throw new IllegalStateException("snapshot corrupt: book " + bookSecurityId
                        + " tick " + tickPx);
                }
                engine.bootstrapBook(bookSecurityId, baseLevel, tickPx);
            }
            case T_CONTRACT -> {
                // One readable shape: MIN_READABLE 8 means the narrower format-5 record (eight
                // columns, swaps only) can no longer reach this reader at all — it is refused at
                // the header. Until the mint this case dispatched on the restored format, because
                // onSnapshotRecord is handed an offset and no length so a record cannot tell the
                // reader how wide it is.
                final long[] contract = new long[CONTRACT_TUPLE_LENGTH];
                for (int i = 0; i < CONTRACT_TUPLE_LENGTH; i++) {
                    contract[i] = buffer.getLong(offset + 4 + 8 * i);
                }
                final long contractId = contract[0];
                if (contractId <= 0 || contractId > appliedSeq) {
                    // Fail closed, the same rule the order rows follow (FR-AC09): the id IS a
                    // consensus sequence, so one at or beyond the restored applied position is a
                    // contract this member could not have booked. Silently keeping it would put a
                    // contract in the extract that the log cannot account for.
                    throw new IllegalStateException("snapshot incomplete: contract id " + contractId
                        + " is not a sequence at or below appliedSeq " + appliedSeq);
                }
                if (!contracts.isEmpty() && contractId <= contracts.get(contracts.size() - 1)[0]) {
                    // Booking order is ascending by construction; anything else means the records
                    // were reordered, which would repoint the idempotency table's indices.
                    throw new IllegalStateException("snapshot corrupt: contract id " + contractId
                        + " is not after " + contracts.get(contracts.size() - 1)[0]);
                }
                contracts.add(contract);
            }
            case T_FX_RATE -> {
                final int currencyIndex = (int) buffer.getLong(offset + 4);
                final long rateTicks = buffer.getLong(offset + 12);
                if (currencyIndex <= 0 || currencyIndex >= fxUsdTicksPerCurrency.length
                    || rateTicks <= 0L) {
                    // Fail closed: a rate this build cannot hold (or a written USD/non-positive
                    // rate, which the writer can never produce) means the snapshot is not this
                    // build's to restore — silently dropping it would revalue every restored
                    // booking's credit.
                    throw new IllegalStateException("snapshot corrupt: fx rate currency "
                        + currencyIndex + " rate " + rateTicks);
                }
                fxUsdTicksPerCurrency[currencyIndex] = rateTicks;
            }
            case T_ORDER -> {
                final long ref = buffer.getLong(offset + 4);
                if (ref >= nextOrderRef) {
                    // Fail closed (FR-AC09): a generator at or below a restored identifier
                    // would reissue references after recovery.
                    throw new IllegalStateException(
                        "snapshot incomplete: order ref " + ref + " >= nextOrderRef " + nextOrderRef);
                }
                engine.bootstrapOrder((int) ref,
                    (int) buffer.getLong(offset + 12),   // accountId
                    (int) buffer.getLong(offset + 20),   // securityId
                    (byte) buffer.getLong(offset + 28),  // side
                    (int) buffer.getLong(offset + 36),   // quantity
                    (int) buffer.getLong(offset + 44),   // remaining
                    buffer.getLong(offset + 52),         // limitPx
                    (byte) buffer.getLong(offset + 60),  // status
                    (byte) buffer.getLong(offset + 100), // riskReason
                    buffer.getLong(offset + 68),         // lastExecPx
                    (int) buffer.getLong(offset + 76),   // lastFillQty
                    buffer.getLong(offset + 84),         // createdAtMillis
                    buffer.getLong(offset + 92),         // updatedAtMillis
                    buffer.getLong(offset + 108),        // reservedNotional
                    (int) buffer.getLong(offset + 116)); // reservedQty
            }
            case T_POSITION -> engine.bootstrapPosition(
                (int) buffer.getLong(offset + 4),
                (int) buffer.getLong(offset + 12),
                (int) buffer.getLong(offset + 20),
                buffer.getLong(offset + 28));
            case T_PRICE -> engine.bootstrapPrice(
                (int) buffer.getLong(offset + 4),
                buffer.getLong(offset + 12));
            case T_SYMBOL -> {
                final int id = buffer.getInt(offset + 4);
                final int tickerLength = buffer.getInt(offset + 8);
                final byte[] ascii = new byte[tickerLength];
                buffer.getBytes(offset + 12, ascii);
                if (id < 0 || id >= MAX_SECURITIES || tickerById[id] != null) {
                    throw new IllegalStateException("snapshot corrupt: symbol id " + id);
                }
                tickerById[id] = new String(ascii, java.nio.charset.StandardCharsets.US_ASCII);
                nextSymbolId = Math.max(nextSymbolId, id + 1);
                // YU16 (ADR-060): re-derive the bond book grid from the restored ticker; T_SYMBOL
                // precedes T_BOOK, so the book rebuilds on the identical grid it was cut on.
                final long derivedTickPx = derivedBookTickPxFor(tickerById[id]);
                if (derivedTickPx != 0L) {
                    engine.overrideBookTickPx(id, derivedTickPx);
                }
            }
            case T_SESSION -> {
                final long restoredPhase = buffer.getLong(offset + 4);
                if (restoredPhase < PHASE_CLOSED || restoredPhase > PHASE_OPEN) {
                    // Fail closed: a phase this build cannot hold means the snapshot is not this
                    // build's to restore, and defaulting it would reopen a halted venue silently.
                    throw new IllegalStateException("snapshot corrupt: session phase " + restoredPhase);
                }
                phase = (byte) restoredPhase;
                restoredQueueDepth = buffer.getLong(offset + 12);
                if (restoredQueueDepth < 0 || restoredQueueDepth > MAX_QUEUED_ORDERS) {
                    throw new IllegalStateException(
                        "snapshot corrupt: queue depth " + restoredQueueDepth);
                }
            }
            case T_QUEUED_ORDER -> {
                final long[] queued = new long[QUEUED_TUPLE_LENGTH];
                for (int i = 0; i < QUEUED_TUPLE_LENGTH; i++) {
                    queued[i] = buffer.getLong(offset + 4 + 8 * i);
                }
                final long ref = queued[0];
                if (ref <= 0 || ref >= nextOrderRef) {
                    // The T_ORDER rule (FR-AC09): a queued order holds a ref the generator already
                    // issued, so one at or beyond the restored generator is an order this member
                    // could not have accepted.
                    throw new IllegalStateException(
                        "snapshot incomplete: queued ref " + ref + " >= nextOrderRef " + nextOrderRef);
                }
                if (!queuedOrders.isEmpty() && ref <= queuedOrders.get(queuedOrders.size() - 1)[0]) {
                    // The T_CONTRACT rule: insertion order is ascending by construction here,
                    // because the ref generator advances at sequencing and the queue only appends.
                    // Anything else means the rows were reordered — and this order IS the release
                    // order at the open, so a reordered queue is a different opening auction.
                    throw new IllegalStateException("snapshot corrupt: queued ref " + ref
                        + " is not after " + queuedOrders.get(queuedOrders.size() - 1)[0]);
                }
                queuedOrders.add(queued);
                if (queued[6] != 0L) {
                    queuedByClientKey.put(queued[6], queuedOrders.size() - 1);
                }
            }
            case T_END -> {
                finishLoad();
                return true;
            }
            default -> throw new IllegalStateException("unknown snapshot record type: " + type);
        }
        return false;
    }

    /**
     * Final load invariants. Three of them now, and the last two exist because of a measured gap:
     * a restore handed a record stream with WHOLE TYPES MISSING accepted it silently — no throw,
     * clean termination on T_END, state simply absent (SessionSnapshotRestoreTest's false arm).
     *
     * <ol>
     *   <li>(FR-AC09) the generator strictly exceeds every ID ever issued;</li>
     *   <li><b>presence</b>: every record type a format-8 snapshot must contain actually appeared
     *       (see {@link #REQUIRED_RECORD_TYPES});</li>
     *   <li><b>count</b>: exactly as many T_QUEUED_ORDER rows arrived as T_SESSION declared.
     *       Presence alone CANNOT cover the queue — zero rows is legitimate whenever the queue is
     *       empty, and that is precisely the dangerous record: a queue silently restored empty
     *       reads as "no halt was in effect", and a halt pending client orders can walk through is
     *       not a halt.</li>
     * </ol>
     *
     * <p>A general manifest of {type -> count} was considered and rejected as premature: the
     * per-record pattern above is cheaper each time and carries no self-reference risk.
     */
    void finishLoad() {
        if (!snapshotHeaderSeen) {
            throw new IllegalStateException("snapshot corrupt: no header record");
        }
        final int missing = REQUIRED_RECORD_TYPES & ~recordTypesSeen;
        if (missing != 0) {
            throw new IllegalStateException("snapshot incomplete: record types absent, mask "
                + Integer.toBinaryString(missing) + " (required " + Integer.toBinaryString(REQUIRED_RECORD_TYPES)
                + ", seen " + Integer.toBinaryString(recordTypesSeen) + ")");
        }
        if (queuedOrders.size() != restoredQueueDepth) {
            throw new IllegalStateException("snapshot incomplete: session declared queueDepth "
                + restoredQueueDepth + " but " + queuedOrders.size() + " queued rows were read");
        }
        if (nextOrderRef <= highestIssuedRef) {
            throw new IllegalStateException("snapshot incomplete: nextOrderRef " + nextOrderRef
                + " <= highestIssuedRef " + highestIssuedRef);
        }
        lastLoadedNextOrderRef = nextOrderRef;
    }

    private void loadSnapshot(final Image snapshotImage) {
        final boolean[] done = { false };
        final FragmentHandler handler = (buffer, offset, length, header) ->
            done[0] = onSnapshotRecord(buffer, offset);
        while (!done[0]) {
            final int fragments = snapshotImage.poll(handler, 16);
            if (fragments == 0) {
                if (snapshotImage.isEndOfStream()) {
                    throw new IllegalStateException("snapshot truncated: end of stream before END record");
                }
                idle.idle();
            } else {
                idle.reset();
            }
        }
    }

    private void writeTuple(final SnapshotWriter writer, final int type, final long[] tuple) {
        snapshotBuffer.putInt(0, type);
        for (int i = 0; i < tuple.length; i++) {
            snapshotBuffer.putLong(4 + 8 * i, tuple[i]);
        }
        writer.write(snapshotBuffer, 0, 4 + 8 * tuple.length);
    }

    // ----- egress ----------------------------------------------------------------------------

    /** Backpressure hook: the engine (on THIS thread) hit a full output ring mid-apply; drain the
     *  published tail to egress so the claim can retry. Without this a single event's fill cascade
     *  larger than the ring self-deadlocks the state machine — including on replay (poison log). */
    private void drainOnBackpressure() {
        drainOutputs(activeSession);
    }

    /** Drain engine outputs emitted by the just-applied event and echo them to the session. */
    private void drainOutputs(final ClientSession session) {
        final long cursor = outputRing.getCursor();
        for (long seq = outputConsumed.get() + 1; seq <= cursor; seq++) {
            final OutputEvent out = outputRing.get(seq);
            // OTEL-01 follow-up: remember the ack the GATEWAY will treat as this order's outcome, so
            // both tiers escalate a reject off the same byte. The rule is copied from the gateway's
            // egress filter deliberately — first direct (non-resting) order-lifecycle output wins,
            // because that is the one that completes the pending; later fills under the same apply
            // are continuations of the same order and must not overwrite the verdict.
            if (directAckKind == 0 && (out.flags & OutputEvent.FLAG_RESTING_UPDATE) == 0
                && (OutputEvent.isOrderLifecycleKind(out.kind)
                    || out.kind == OutputEvent.KIND_ORDER_NOT_FOUND)) {
                directAckKind = out.kind;
            }
            // Read-side tap (YU05 recon/regulatory). Handed the reusable ring slot, so a consumer
            // copies what it needs synchronously. Placed before the leader-gated bridges on
            // purpose: a shadow replay is never leader, and the recon window must see every
            // committed output regardless of role.
            final java.util.function.Consumer<OutputEvent> tap = outputSink;
            if (tap != null) {
                tap.accept(out);
            }
            // Leader-side trade bridge: every booked trade → NATS /trades → trade-processor → DB +
            // positions + UI. Leader-only so followers never duplicate; offer is non-blocking so the
            // deterministic apply thread is never held up by NATS.
            if (out.kind == OutputEvent.KIND_TRADE_BOOKED && role == Cluster.Role.LEADER
                && tradeBridge != null) {
                tradeBridge.offer(out.tradeSeq, out.accountId, tickerById[out.securityId],
                    out.side, out.tradeQty, out.tradePx, out.orderRef);
            }
            // Leader-side order bridge: every order-state transition → NATS /orders → read model →
            // orderbook projection → REST enumeration. Same leader-only, non-blocking discipline as
            // the trade bridge. Covers both the input's own order and counterparty resting orders
            // hit by an aggressor (FLAG_RESTING_UPDATE) — so an STP/replace cancel of a resting
            // order is observable to its owner via this feed (brief 07).
            if (OutputEvent.isOrderLifecycleKind(out.kind) && role == Cluster.Role.LEADER
                && orderBridge != null) {
                // The trace id goes ONLY on the applying order's own egress. A resting order hit
                // by an aggressor is a DIFFERENT order, and the key we hold is the aggressor's:
                // stamping it would put a real, resolvable trace id on a stranger's row, which
                // renders convincing spans for the wrong order — the worse half of the wrong-id-
                // space error this codebase has already paid for twice.
                orderBridge.offer(out.orderRef, out.accountId, tickerById[out.securityId],
                    out.side, out.quantity, out.remainingQty, out.limitPx, out.status,
                    out.lastExecPx, out.lastFillQty, out.createdAtMillis, out.updatedAtMillis,
                    (out.flags & OutputEvent.FLAG_RESTING_UPDATE) != 0 ? 0L : applyTraceKey);
            }
            // Analytical capture (brief 06), same leader-only non-blocking discipline, separate
            // queue. Deliberately in the same drain loop rather than a new emission point: the tap
            // observes what the deterministic engine already produced and adds nothing to it.
            if (kdbTap != null && role == Cluster.Role.LEADER) {
                if (out.kind == OutputEvent.KIND_TRADE_BOOKED) {
                    kdbTap.offerTrade(out.inputSeq, out.tradeSeq, out.accountId,
                        tickerById[out.securityId], out.securityId, out.side, out.tradeQty,
                        out.tradePx, out.updatedAtMillis);
                } else if (OutputEvent.isOrderLifecycleKind(out.kind)) {
                    kdbTap.offerOrder(out.inputSeq, out.orderRef, out.accountId,
                        tickerById[out.securityId], out.securityId, out.side, out.quantity,
                        out.remainingQty, out.limitPx, out.status, out.lastExecPx, out.lastFillQty,
                        out.createdAtMillis, out.updatedAtMillis);
                }
            }
            ackBuffer.putLong(0, out.inputSeq);
            ackBuffer.putInt(8, out.orderRef);
            ackBuffer.putByte(12, out.kind);
            ackBuffer.putLong(13, out.tradeSeq);
            // Correlation class (FR-LOB07): counterparty resting-order updates never complete an
            // offer's direct ack — the gateway skips them in offer/ack accounting.
            ackBuffer.putByte(21, (out.flags & OutputEvent.FLAG_RESTING_UPDATE) != 0 ? (byte) 1 : (byte) 0);
            ackBuffer.putByte(22, out.riskReason);
            ackBuffer.putByte(23, applyingMarketTrade ? (byte) 1 : (byte) 0);
            // Option B: name the request this egress answers. Every output drained here belongs to
            // the input being applied (the drain runs inside its apply, backpressure drains
            // included), so the CURRENT input's request id is the right stamp for all of them —
            // the gateway still routes on the class bytes first, so a resting update carrying the
            // aggressor's id can never complete the aggressor's pending with counterparty data.
            ackBuffer.putLong(24, applyRequestId);
            offerEgress(session);
        }
        outputConsumed.set(cursor);
    }

    /** Best-effort bounded offer: during replay or after disconnect the session is not
     *  deliverable and the committed log, not egress, is the source of truth. */
    private void offerEgress(final ClientSession session) {
        if (session == null || session.isClosing()) {
            return;
        }
        idle.reset();
        // 20 attempts is sub-millisecond worst case. The old bound (1000 x backoff idle, ~1s
        // per undeliverable ack) let one non-draining client session throttle the whole state
        // machine under load: apply collapsed to ~1 ack/s and the cluster appeared frozen
        // (GKE bench, 2026-07-19). Egress is best-effort BY DESIGN — a slow client gets drops,
        // never the state machine's time; the committed log remains the source of truth.
        for (int i = 0; i < 20; i++) {
            if (session.offer(ackBuffer, 0, EGRESS_ACK_LENGTH) > 0) {
                return;
            }
            idle.idle();
        }
    }

    // ----- test observability (read off-thread; volatile) ------------------------------------

    public int snapshotsTaken() {
        return snapshotsTaken;
    }

    public long lastLoadedNextOrderRef() {
        return lastLoadedNextOrderRef;
    }

    /**
     * Position on the committed consensus log — restored from the snapshot header and advanced by
     * every sequenced input. This, not the engine's {@code blpSeq}, is what "how far has this
     * member caught up" means: a member restored from a snapshot holds state as of this sequence
     * even though its engine has applied no events since (YU15, T-RXT07).
     */
    public long appliedSeq() {
        return appliedSeq;
    }

    /** Consensus sequence the most recent risk-extract marker landed at (YU15), or -1. */
    public long lastExtractCutSeq() {
        return lastExtractCutSeq;
    }

    /** SHA-256 of the most recent rendered cut — equal on every member by construction. */
    public String lastExtractCutSha() {
        return lastExtractCutSha;
    }

    /** OTEL-01 span sink, or null when tracing is off — read by the /metrics handler. */
    public SpanSink spanSink() {
        return traces;
    }

    public Cluster.Role role() {
        return role;
    }

    /** LATENCY-01 Phase B side channel (leader commit/apply split); null unless LATENCY_DECOMP=1. */
    public LeaderApplyLatency leaderLatency() {
        return latency;
    }

    /** Plain read for quiesced cross-member equality checks; ordered by the engine's per-event
     *  release-store (read {@code engine().blpSeq()} first). */
    public long nextOrderRef() {
        return nextOrderRef;
    }

    /**
     * YU17 (ADR-072): order-shaped commands that reached consensus and were NOT replayed tape
     * flow. Exported as {@code traderx_cluster_operator_next_order_ref} and read by
     * `scripts/proofs/lib-consensus-readings.sh` in place of the raw generator.
     *
     * <p>Offset by the generator's 1-based start so it is a COUNT, not a next-value: a fresh epoch
     * reads 0 here and {@code nextOrderRef} 1, which is what makes "no order of mine was
     * sequenced" expressible as an unchanged reading on a rig that has never traded.
     */
    public long operatorOrderRefs() {
        return nextOrderRef - 1 - externalOrderRefs;
    }

    /** YU17 (ADR-072): the replayed half, exported so the library's own admission test — name a
     *  counter the new writer does not advance and show it standing still while that writer runs —
     *  can be demonstrated on a rig rather than argued. */
    public long externalOrderRefs() {
        return externalOrderRefs;
    }

    public MatchingEngine engine() {
        return engine;
    }

    public BlpRiskState risk() {
        return risk;
    }
}
