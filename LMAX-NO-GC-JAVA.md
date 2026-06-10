# TraderX — The No-GC Java Approach: How It Works & What a Spec Needs (building on `009`)

> **Status:** Design proposal (cross-cutting companion to `LMAX-SEQUENCER-ARCHITECTURE.md`,
> `LMAX-INPUT-DISRUPTOR.md`, `LMAX-BLP.md`, `LMAX-OUTPUT-DISRUPTOR.md`).
> **Target state base:** `009-order-management-matcher`.
> **Scope of this doc:** the **no-GC discipline** itself — what "zero steady-state allocation" means, the
> techniques that achieve it, the JVM configuration that proves and protects it, and how it is *enforced* as
> a conformance gate across every hot-path node. This is **cross-cutting**: it threads through the input ring,
> the BLP, and the output ring rather than being a single component.
> **Primary reference:** Martin Fowler, *The LMAX Architecture* — https://martinfowler.com/articles/lmax.html
> **Date:** 2026-06-09

This document does two things:

1. **Part A** explains, in detail, **how the no-GC approach works** — what counts as steady-state allocation,
   where `009` allocates today, the technique-by-technique remedies, the GC choices (Epsilon for proof,
   ZGC/Shenandoah as safety net), off-heap flyweights, `long` fixed-point, off-hot-path logging, warm-up, and
   how the contract is *proven* rather than assumed.
2. **Part B** specifies **what a spec building off `009` would need** — not a new component but a
   **conformance profile + quality gate** that the hot-path states (`010`/`011`/`012`) must satisfy: the
   requirement IDs, build/JVM specs, configuration, observability, and acceptance gates.

---

## Table of contents

**Part A — How the no-GC approach works**
1. [What "no-GC" actually means](#a1-what-no-gc-actually-means)
2. [Where `009` allocates today](#a2-where-009-allocates-today)
3. [The technique table](#a3-the-technique-table)
4. [Pre-allocation & object pooling](#a4-pre-allocation--object-pooling)
5. [Off-heap flyweights & SBE](#a5-off-heap-flyweights--sbe)
6. [`long` fixed-point instead of `BigDecimal`](#a6-long-fixed-point-instead-of-bigdecimal)
7. [Primitive collections & `int` symbols](#a7-primitive-collections--int-symbols)
8. [Logging off the hot path](#a8-logging-off-the-hot-path)
9. [GC choices: Epsilon to prove, ZGC/Shenandoah to protect](#a9-gc-choices-epsilon-to-prove-zgcshenandoah-to-protect)
10. [Warm-up & the nightly bounce](#a10-warm-up--the-nightly-bounce)
11. [Proving it: the allocation gate](#a11-proving-it-the-allocation-gate)
12. [The code as implemented in state `009b`](#a12-the-code-as-implemented-in-state-009b)

**Part B — What a spec needs (building on `009`)**
13. [Proposed scope: a conformance profile, not a component](#b13-proposed-scope-a-conformance-profile-not-a-component)
14. [Artifacts to author](#b14-artifacts-to-author)
15. [No-GC conformance requirements](#b15-no-gc-conformance-requirements)
16. [Build & dependency specs](#b16-build--dependency-specs)
17. [JVM flags & run profiles](#b17-jvm-flags--run-profiles)
18. [Configuration keys](#b18-configuration-keys)
19. [Observability deltas](#b19-observability-deltas)
20. [Success criteria & validation](#b20-success-criteria--validation)
21. [What `009` already gives you vs. what the gate must add](#b21-what-009-already-gives-you-vs-what-the-gate-must-add)
22. [Risks & trade-offs](#b22-risks--trade-offs)

---

# Part A — How the no-GC approach works

## A1. What "no-GC" actually means

It does **not** mean "tune the garbage collector." It means writing the hot path so that, in the **steady
state**, it **allocates zero bytes** — so the collector simply never has work to do. Allocation is permitted
in two places only:

- **Startup** — pre-allocate every ring slot, pooled object, and buffer once.
- **The outermost edges** — the Gateway converting inbound JSON/`String`/`BigDecimal` to internal binary, and
  the output handlers converting back to JSON/SQL for the legacy UI and DB. These are *deliberately*
  downstream of the latency-critical section.

Between those edges — the input ring, the BLP, the output ring — **nothing is `new`-ed per event**. That is
the contract, and it is *enforced*, not hoped for (see [§A11](#a11-proving-it-the-allocation-gate)).

## A2. Where `009` allocates today

State `009`'s `OrderMatcherService` is a catalogue of hot-path allocation. Each line below allocates on every
tick or message:

| `009` code | Allocates |
| --- | --- |
| `findAllByOrderByUpdatedAtDesc().stream().filter(...).toList()` per tick | stream pipeline objects, lambdas, a new `List` |
| `BigDecimal` math (`roundPrice`, `setScale`, `compareTo`) | `BigDecimal`/`BigInteger` objects per operation |
| `ConcurrentHashMap<String,BigDecimal> lastPrices` + `String` keys | map nodes, boxed values, `String` work |
| `OrderResponse.from(order, ...)` per list/publish | a DTO per row, per publish |
| Jackson `ObjectMapper` (NATS publish/subscribe) | parse/serialize intermediate objects per message |
| `Map.of(...)` payload in `submitTrade` | a map + boxed entries per fill |
| `Instant.now()` on every mutation | a `java.time` object each call |
| `String.format("ord-013-%04d", ...)` for ids | formatter + `String` per create |
| SLF4J parameterized logging | boxes varargs into an `Object[]` |

Under load this is continuous garbage → minor GCs → pauses → tail-latency spikes. The no-GC approach removes
every one of these from the hot path.

## A3. The technique table

| Technique | Replaces in `009` | Why |
| --- | --- | --- |
| Pre-allocated ring of mutable holders | `new` DTO per request/message | Slots allocated once; mutate in place. |
| Flyweight over off-heap (`UnsafeBuffer`) + SBE | Jackson JSON parse/serialize | Zero-copy binary; one format wire **and** journal. |
| `long` fixed-point prices/qty | `BigDecimal` | Integer math: alloc-free, branch-light, cache-friendly. |
| Symbol → `int securityId` | `String security` end-to-end | No string hashing/equality; array-indexed books. |
| Agrona primitive collections | `ConcurrentHashMap`, `HashMap<Integer,…>`, streams | No autoboxing; cache-friendly. |
| Object pooling for domain state | `new OrderRecord()`, `OrderResponse.from(...)` | Long-lived, reused, never re-allocated mid-life. |
| No locks / no atomics on hot path | `ReentrantLock`, `AtomicLong/Integer` | Single-threaded BLP; counters are plain `long`. |
| Binary/async logging off the path | SLF4J parameterized logging | Never `String.format`/box varargs on the BLP thread. |
| Time carried in events | `Instant.now()` per mutation | Determinism + no `java.time` allocation. |
| Busy-spin + core pinning | Spring scheduler threads/pools | Deterministic sub-µs wake-ups; warm caches. |
| `-XX:+AlwaysPreTouch`, `-Xms==-Xmx`, large pages, NUMA | Default heap growth | No page-fault / resize jitter at runtime. |
| Epsilon GC in tests; ZGC/Shenandoah in prod | G1 defaults | Epsilon proves zero-alloc; ZGC/Shenandoah are the safety net. |

## A4. Pre-allocation & object pooling

Two object lifetimes exist on the hot path, and **both** are allocated up front:

- **Ring slots** — the input `InputEvent[]`, the output `OutputEvent[]`: created once by the `EventFactory`
  at Disruptor construction (`InputEvent::newInstance` / `OutputEvent::newInstance` in `LmaxEngine`),
  mutated in place forever.
- **Domain state** — order-book entries are **long-lived and pooled** (`RestingOrder` + the free-list in
  `MatchingEngine`). A resting-order entry is taken from the free-list when an order arrives; nothing is
  `new`-ed mid-life. This also avoids the "mid-life promotion" the article warns about (objects that survive
  long enough to be promoted, then die, are the worst case for a generational GC).

The rule of thumb: **if an object's lifetime is per-event, it must be a reused slot, not a fresh allocation.**
Both halves are shown with the real `009b` code in [§A12](#a12-the-code-as-implemented-in-state-009b).

## A5. Off-heap flyweights & SBE

Instead of decoding bytes into Java objects, the hot path treats a region of an off-heap `UnsafeBuffer`
(Agrona) as the object — a **flyweight**. SBE (Simple Binary Encoding) generates encoders/decoders that read
and write fields directly at byte offsets, with **no intermediate object**. The same encoded bytes are used
on the wire **and** written to the journal **and** carried in the ring slot — one representation, zero
re-serialization. This is the single biggest win over `009`'s per-message Jackson `ObjectMapper`.

> **Implementation status (state `009b`):** SBE codecs and Agrona structures are deferred (task `T09B12`).
> The shipped hot path uses **typed-primitive-field slot holders** (`InputEvent`/`OutputEvent`, shown in
> [§A12](#a12-the-code-as-implemented-in-state-009b)) which already achieve the zero-per-event-allocation
> property; the `Journaler` writes them as fixed 64-byte little-endian records through one reused direct
> `ByteBuffer`. The flyweight/SBE step replaces those holders with offsets over the same bytes.

## A6. `long` fixed-point instead of `BigDecimal`

Prices and quantities are carried as **scaled `long`s** — value × 1,000,000 (6 dp), so `187.250`
becomes `187_250_000L`. Arithmetic is plain integer math: allocation-free, branch-light, cache-friendly, and
deterministic. Conversions to/from `BigDecimal`/`String` happen **only at the edges**. This is the actual
`Px` class from state `009b`
(`specs/009b-lmax-sequencer-architecture/generation/runtime-overrides/order-matcher/.../lmax/Px.java`):

```java
/**
 * Fixed-point price arithmetic for the LMAX hot path (state 009b, FR-09B05 / NGC-03).
 *
 * Prices travel through the rings and the BLP as {@code long} "ticks" (price x 1e6).
 * BigDecimal conversion happens only at the edges (gateway in, read-model/NATS out) and
 * rounds to 3dp HALF_UP, matching state 009's roundPrice() for penny parity (SC-09B04).
 */
public final class Px {
    public static final long SCALE = 1_000_000L;
    /** Sentinel for "no price available". Real prices are strictly positive. */
    public static final long NONE = 0L;

    private Px() {
    }

    /** Edge conversion in: BigDecimal -> ticks, applying 009's 3dp HALF_UP rounding. */
    public static long toTicks(BigDecimal price) {
        if (price == null) {
            return NONE;
        }
        return price.setScale(3, RoundingMode.HALF_UP).movePointRight(6).longValueExact();
    }

    /** Edge conversion out: ticks -> BigDecimal at the external 3dp scale. */
    public static BigDecimal toBigDecimal(long ticks) {
        if (ticks == NONE) {
            return null;
        }
        return BigDecimal.valueOf(ticks, 6).setScale(3, RoundingMode.HALF_UP);
    }
}
```

The discipline here: **fix the scale globally, centralize conversions at the edges, and unit-test rounding
against `009`'s `BigDecimal` behaviour** so there is no penny drift. That unit test exists — `PxTest`
(same module, `src/test/java/.../lmax/PxTest.java`) round-trips a rounding fixture and asserts the
in-the-money comparison semantics survive the integer mapping:

```java
@Test
void roundTripMatches009Rounding() {
    String[] fixtures = {
        "187.250", "187.2499", "187.2495", "412.000", "0.001", "0.0005",
        "905.125", "61.5", "507.88", "99.9994", "99.9995", "123456789.999"
    };
    for (String raw : fixtures) {
        BigDecimal input = new BigDecimal(raw);
        BigDecimal expected = input.setScale(3, RoundingMode.HALF_UP); // 009 roundPrice
        assertEquals(0, expected.compareTo(Px.toBigDecimal(Px.toTicks(input))),
            "penny drift for " + raw);
    }
}

@Test
void inTheMoneyComparesMatchBigDecimal() {
    String[] prices = {"99.999", "100.000", "100.001", "187.250", "0.001", "905.125"};
    String[] limits = {"100.000", "187.250", "0.001", "905.125"};
    for (String p : prices) {
        for (String l : limits) {
            BigDecimal pb = new BigDecimal(p);
            BigDecimal lb = new BigDecimal(l);
            long pt = Px.toTicks(pb);
            long lt = Px.toTicks(lb);
            // Buy in-the-money: market <= limit; Sell: market >= limit (009 semantics)
            assertEquals(pb.compareTo(lb) <= 0, pt <= lt, "buy compare " + p + " vs " + l);
            assertEquals(pb.compareTo(lb) >= 0, pt >= lt, "sell compare " + p + " vs " + l);
        }
    }
}
```

## A7. Primitive collections & `int` symbols

`HashMap<Integer,…>` / `ConcurrentHashMap<String,…>` box keys/values and chase pointers. The no-GC path uses
**primitive collections and plain arrays** — no boxing, no iterators, cache-friendly — the modern equivalent
of the article's hand-written `LongToObjectHashMap`. Because securities are mapped `String → int securityId`
at the Gateway (`SymbolTable`), the BLP's books are plain arrays indexed by `securityId`: an O(1),
allocation-free, branch-predictable lookup that replaces `009`'s per-tick stream-filter over JPA rows keyed
by `String`. The real `009b` structures (`MatchingEngine` fields):

```java
private RestingOrder[] ordersByRef;          // dense index: orderRef -> entry
private final IntList[] openRefsBySecurity;  // per-security open-order index
private final long[] lastPxBySecurity;       // long fixed-point; Px.NONE = unknown
private RestingOrder freeList;               // pre-allocated pool (BLP thread only)
```

`IntList` is the module's minimal growable primitive list (full source in
[§A12](#a12-the-code-as-implemented-in-state-009b)); Agrona's `Int2ObjectHashMap`/`IntArrayList` replace it
when the SBE/Agrona milestone lands (`T09B12`).

## A8. Logging off the hot path

Logging is a sneaky allocator: SLF4J parameterized calls box varargs into an `Object[]`, and `String.format`
builds strings. On the BLP thread, **none** of that is allowed. Instead, log records are written as binary
into a **separate ring consumed by a logging thread**, or deferred entirely; any human-readable formatting
happens off-thread. The BLP thread never blocks on or allocates for logging.

## A9. GC choices: Epsilon to prove, ZGC/Shenandoah to protect

- **Epsilon GC** (`-XX:+UseEpsilonGC`) is a **no-op collector**: it allocates but never reclaims. Run the
  hot-path tests under Epsilon and any steady-state allocation eventually **exhausts the heap and crashes the
  test** — turning "we think it's allocation-free" into a hard, automated assertion. This is the *test*
  configuration.
- **ZGC or Shenandoah** are the **production safety net**: sub-millisecond pause collectors that quietly mop
  up the unavoidable edge allocations without stop-the-world spikes. They are the modern equivalent of LMAX's
  tuned CMS + nightly bounce. The goal is that they have almost nothing to do — but if a stray allocation
  slips through in prod, they keep pauses sub-ms instead of letting garbage pile up.

## A10. Warm-up & the nightly bounce

- **JIT warm-up.** A cold HotSpot interprets bytecode until C2 compiles the hot methods; the first live
  orders would pay that cost. So at startup the node **replays a synthetic workload** (or a slice of the
  journal) to force C2 compilation of the hot path *before* going live.
- **Nightly bounce.** In a quiet window, restart the node, reload the latest snapshot, and replay the journal
  (< 1 minute) — wiping any slowly-accumulated heap state and any fragmentation. Straight from the article;
  it keeps a long-running node as pristine as a freshly-started one.

## A11. Proving it: the allocation gate

The contract is only real if it is enforced. The validation stack:

- **Epsilon-GC allocation gate** (CI) — sustained hot-path run under `-XX:+UseEpsilonGC`; **fails** on any
  steady-state allocation. This is the primary gate.
- **JFR allocation profiling** — `jdk.ObjectAllocationSample` events to attribute any allocation to a stack.
- **async-profiler** (`alloc` mode) — flame graphs of allocation sites.
- **HdrHistogram** — full latency distributions (p50/p99/p99.9/max), proving the *effect* (no GC pauses).
- **jHiccup** — separates application latency from JVM/OS pauses, confirming pauses are gone.
- **Determinism replay** — same journal ⇒ same state/output, which also catches accidental allocation that
  perturbs iteration order.

## A12. The code as implemented in state `009b`

Everything below is **verbatim from the codebase** — the LMAX hot path that ships with state `009b`, under
`specs/009b-lmax-sequencer-architecture/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/lmax/`
(rendered into `order-matcher` by the generation pipeline). Where a class is long, elided sections are
marked with `// …`.

### A12.1 The ring slot: one mutable holder per slot, reused forever (`InputEvent.java`)

```java
/**
 * Input-ring slot holder (one mutable instance per slot, allocated once at startup and
 * reused forever — NGC-01). Producers overwrite every relevant field in place; nothing is
 * allocated per event. Time is carried in the event (eventTimeMillis stamped at the
 * gateway) so the BLP never reads a clock (FR-09B14).
 */
public final class InputEvent {
    public static final byte TYPE_ORDER_NEW = 1;
    public static final byte TYPE_ORDER_CANCEL = 2;
    public static final byte TYPE_FORCE_FILL = 3;
    public static final byte TYPE_PRICE_TICK = 4;

    public static final byte SIDE_BUY = 0;
    public static final byte SIDE_SELL = 1;

    public long seq;              // global sequence number (the ring sequence)
    public byte type;
    public int orderRef;          // numeric part of the external ord-013-%04d id
    public int accountId;
    public int securityId;        // ticker mapped to int at the gateway
    public byte side;
    public int qty;
    public long limitPx;          // long fixed-point (x 1e6)
    public long priceTicks;       // for PRICE_TICK
    public long ingressNanos;     // System.nanoTime() at the gateway, for latency histograms
    public long eventTimeMillis;  // wall-clock stamped at the gateway (event-carried time)

    public static InputEvent newInstance() {
        return new InputEvent();
    }
}
```

### A12.2 Pooled domain state: the free-list (`RestingOrder.java` + `MatchingEngine`)

The order-book entry is a flat primitive-field object, pooled on a free-list owned by the BLP thread:

```java
/**
 * BLP-private resting-order entry. Instances are pooled (free list) and reused: taken when
 * an order arrives, returned when it reaches a terminal state — never allocated mid-life
 * in the steady state (NGC-01, technique table "object pooling for domain state").
 */
public final class RestingOrder {
    public static final byte STATUS_NEW = 0;
    public static final byte STATUS_PARTIALLY_FILLED = 1;
    public static final byte STATUS_FILLED = 2;
    public static final byte STATUS_CANCELED = 3;
    public static final byte STATUS_REJECTED = 4;

    public int orderRef;
    public int accountId;
    public int securityId;
    public byte side;
    public int quantity;
    public int remaining;
    public long limitPx;
    public byte status;
    public long lastExecPx = Px.NONE;
    public int lastFillQty;
    public long createdAtMillis;
    public long updatedAtMillis;

    RestingOrder nextFree;        // free-list link, BLP thread only

    public boolean isOpen() {
        return status == STATUS_NEW || status == STATUS_PARTIALLY_FILLED;
    }
    // … reset() zeroes every field when an entry is recycled
}
```

The pool is filled once in the `MatchingEngine` constructor and drawn from per order — single-threaded, so
the free-list is a plain linked field, no locks, no `Atomic*`:

```java
public MatchingEngine(OutputPublisher out, HotPathMetrics metrics, int maxSecurities,
                      int fillFullThreshold, int initialPoolSize) {
    // …
    this.ordersByRef = new RestingOrder[16_384];
    this.openRefsBySecurity = new IntList[maxSecurities];
    this.lastPxBySecurity = new long[maxSecurities];
    for (int i = 0; i < initialPoolSize; i++) {
        RestingOrder pooled = new RestingOrder();
        pooled.nextFree = freeList;
        freeList = pooled;
    }
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
```

### A12.3 Primitive collections: no boxing, swap-with-last removal (`IntList.java`)

The per-security open-order index — full class, this is the entire implementation:

```java
/**
 * Minimal growable primitive int list for the BLP's per-security open-order index.
 * No boxing, no iterators; removal is unordered swap-with-last so scans stay O(1) per
 * removal (NGC-04). Growth only happens as a security's book first deepens — steady-state
 * operation allocates nothing.
 */
public final class IntList {
    private int[] values;
    private int size;

    public IntList(int initialCapacity) {
        values = new int[Math.max(4, initialCapacity)];
    }

    public int size() {
        return size;
    }

    public int get(int index) {
        return values[index];
    }

    public void add(int value) {
        if (size == values.length) {
            int[] grown = new int[values.length * 2];
            System.arraycopy(values, 0, grown, 0, size);
            values = grown;
        }
        values[size++] = value;
    }

    /** Swap-with-last removal; the caller re-checks the same index after removal. */
    public void removeAtUnordered(int index) {
        values[index] = values[--size];
    }

    public boolean removeValueUnordered(int value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) {
                removeAtUnordered(i);
                return true;
            }
        }
        return false;
    }
}
```

### A12.4 Integer matching: `009`'s policy with zero `BigDecimal` (`MatchingEngine.java`)

`009`'s in-the-money test was `marketPrice.compareTo(order.getLimitPrice()) <= 0` on `BigDecimal`s pulled
from a `ConcurrentHashMap<String,BigDecimal>`. In the BLP it is two integer compares over an array read —
and the price-tick scan that replaces `009`'s per-tick JPA stream-filter walks a primitive index:

```java
private boolean isInTheMoney(RestingOrder o, long px) {
    return o.side == InputEvent.SIDE_BUY ? px <= o.limitPx : px >= o.limitPx;
}

private void autoFill(RestingOrder o, long px, InputEvent e) {
    autoFillAttempts++;
    int remaining = o.remaining;
    int fillQty = remaining < fillFullThreshold ? remaining : Math.max(1, (remaining + 1) / 2);
    applyFill(o, fillQty, px, false, e, px);
    autoFillSuccess++;
}

private void onPriceTick(InputEvent e) {
    lastPxBySecurity[e.securityId] = e.priceTicks;
    IntList refs = openRefsBySecurity[e.securityId];
    if (refs == null) {
        return;
    }
    // Re-evaluate resting orders for this security. Index-based scan; applyFill removes
    // terminal orders from this list (swap-with-last), so the same index is re-checked.
    for (int i = 0; i < refs.size(); ) {
        RestingOrder o = lookup(refs.get(i));
        if (o == null || !o.isOpen() || o.remaining <= 0) {
            refs.removeAtUnordered(i);
            continue;
        }
        if (isInTheMoney(o, e.priceTicks)) {
            autoFill(o, e.priceTicks, e);
            if (!o.isOpen()) {
                continue; // entry at index i was swap-removed by applyFill
            }
        }
        i++;
    }
}
```

### A12.5 Event-carried time, ids from the event, ack-first (`MatchingEngine.onNewOrder`)

No `Instant.now()`, no `String.format("ord-013-%04d", …)`, no clock reads: time comes from
`e.eventTimeMillis` (stamped once at the Gateway) and the order id derives from `e.orderRef`:

```java
private void onNewOrder(InputEvent e) {
    RestingOrder o = takeFromPool();
    o.orderRef = e.orderRef;
    o.accountId = e.accountId;
    o.securityId = e.securityId;
    o.side = e.side;
    o.quantity = e.qty;
    o.remaining = e.qty;
    o.limitPx = e.limitPx;
    o.status = RestingOrder.STATUS_NEW;
    o.lastExecPx = Px.NONE;
    o.lastFillQty = 0;
    o.createdAtMillis = e.eventTimeMillis;
    o.updatedAtMillis = e.eventTimeMillis;
    index(o);
    openRefs(e.securityId).add(e.orderRef);

    long px = lastPxBySecurity[e.securityId];
    // Ack first: the REST create response is the NEW order, exactly as in 009.
    out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CREATE, true, px, e.ingressNanos);

    // Event-driven matching: evaluate immediately instead of waiting for a poll tick.
    if (px != Px.NONE && isInTheMoney(o, px)) {
        autoFill(o, px, e);
    }
}
```

### A12.6 Single-writer counters: plain `volatile long`, no `Atomic*` (`MatchingEngine`)

Only the BLP thread writes these; edge threads (REST `/health`, `/metrics`) read them racily-but-safely.
No CAS, no contention, no allocation:

```java
// Single-writer counters; volatile so edge threads can read them racily-but-safely.
private volatile long eventsProcessed;
private volatile long autoFillAttempts;
private volatile long autoFillSuccess;
private volatile long lastEventTimeMillis;
private volatile long blpSeq;
// …

@Override
public void onEvent(InputEvent e, long sequence, boolean endOfBatch) {
    // …
    switch (e.type) {
        case InputEvent.TYPE_ORDER_NEW -> { ordersNew++; onNewOrder(e); }
        case InputEvent.TYPE_ORDER_CANCEL -> { ordersCancel++; onCancel(e); }
        case InputEvent.TYPE_FORCE_FILL -> { ordersForceFill++; onForceFill(e); }
        case InputEvent.TYPE_PRICE_TICK -> { priceTicks++; onPriceTick(e); }
        default -> { /* ignore unknown event types */ }
    }
    eventsProcessed++;
    lastEventTimeMillis = e.eventTimeMillis;
    blpSeq = sequence;
    metrics.recordBlpEventLatency(System.nanoTime() - e.ingressNanos);
}
```

### A12.7 The edges are where allocation is allowed (`LmaxEngine.executeNewOrder`)

The Gateway converts `String`/`BigDecimal` once, then claim/write/publish into the ring with **zero
allocation past this point** — and `publish` sits in a `finally` so a failed write never strands a slot:

```java
public OrderSnapshot executeNewOrder(int orderRef, int accountId, String ticker, OrderSide side,
                                     int quantity, BigDecimal limitPrice) {
    int securityId = symbols.idFor(ticker);     // String -> int, once, at the edge
    long limitPx = Px.toTicks(limitPrice);      // BigDecimal -> long fixed-point, once
    return execute(InputEvent.TYPE_ORDER_NEW, orderRef, accountId, securityId,
        (byte) side.ordinal(), quantity, limitPx, 0L);
}

private OrderSnapshot execute(byte type, int orderRef, int accountId, int securityId, byte side,
                              int quantity, long limitPx, long priceTicks) {
    long seq = inputRing.next();
    CompletableFuture<OrderSnapshot> ack = readModel.registerAck(seq);
    try {
        InputEvent e = inputRing.get(seq);
        e.seq = seq;
        e.type = type;
        e.orderRef = orderRef;
        e.accountId = accountId;
        e.securityId = securityId;
        e.side = side;
        e.qty = quantity;
        e.limitPx = limitPx;
        e.priceTicks = priceTicks;
        e.ingressNanos = System.nanoTime();
        e.eventTimeMillis = System.currentTimeMillis();
    } finally {
        inputRing.publish(seq);
    }
    try {
        return ack.get(ackTimeoutMs, TimeUnit.MILLISECONDS);
    } // … timeout/interrupt handling maps to 009's REST error semantics
}
```

### A12.8 Batch-amortised durability (`Journaler.onEvent`)

The journaler writes each event into **one reused direct `ByteBuffer`** (allocated once in the
constructor) and fsyncs once per drained batch — `endOfBatch` is the Disruptor's batching hook:

```java
private final ByteBuffer buffer = ByteBuffer.allocateDirect(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);

@Override
public void onEvent(InputEvent e, long sequence, boolean endOfBatch) {
    // …
    buffer.clear();
    buffer.putLong(e.seq);
    buffer.put(e.type);
    buffer.put(e.side);
    buffer.putShort((short) 0);
    buffer.putInt(e.orderRef);
    buffer.putInt(e.accountId);
    buffer.putInt(e.securityId);
    buffer.putInt(e.qty);
    buffer.putLong(e.limitPx);
    buffer.putLong(e.priceTicks);
    buffer.putLong(e.eventTimeMillis);
    while (buffer.position() < RECORD_SIZE) {
        buffer.put((byte) 0);
    }
    buffer.flip();
    while (buffer.hasRemaining()) {
        channel.write(buffer);
    }
    if (endOfBatch) {
        channel.force(false); // durability amortised across the drained batch
    }
    // …
    journaledSeq = sequence;
}
```

### A12.9 Observing the contract today (`LmaxEngine.blpAllocatedBytes`)

Until the Epsilon gate lands (`T09B18`), the BLP thread's allocation is **measured and exported** so any
regression is visible on a dashboard — `traderx_hotpath_alloc_bytes_total{node="blp"}` in `/metrics`:

```java
public long blpAllocatedBytes() {
    long threadId = matchingEngine == null ? 0 : matchingEngine.blpThreadId();
    if (threadId == 0) {
        return 0;
    }
    var threadMx = ManagementFactory.getThreadMXBean();
    if (threadMx instanceof com.sun.management.ThreadMXBean sunThreadMx
        && sunThreadMx.isThreadAllocatedMemorySupported()) {
        long allocated = sunThreadMx.getThreadAllocatedBytes(threadId);
        return Math.max(0, allocated);
    }
    return 0;
}
```

### A12.10 The Epsilon allocation gate — planned shape (task `T09B18`, not yet in the codebase)

```java
// Allocation-gate test: any steady-state allocation exhausts the (small) Epsilon heap → test FAILS.
//   JVM args: -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms64m -Xmx64m -XX:+AlwaysPreTouch
@Test void blpIsAllocationFreeInSteadyState() {
    warmUp(matchingEngine, 100_000);                 // force C2 compilation first
    for (long i = 0; i < 50_000_000L; i++) {         // far more than the heap could hold if we allocated
        InputEvent e = nextSyntheticEvent(i);        // mutates a reused holder — no new objects
        matchingEngine.onEvent(e, i, true);
    }
    // Reaching here without OutOfMemoryError proves zero steady-state allocation.
}
```

---

# Part B — What a spec needs (building on `009`)

This is **cross-cutting**: rather than a new component/state, it is a **conformance profile and quality
gate** that the hot-path states (`010-input-disruptor-sequencer`, `011-fused-blp-matching`,
`012-output-disruptor-readmodel`) must each satisfy. The IDs below use a dedicated `NGC` namespace so they can
be **referenced** from each of those states' NFRs (e.g. `010`'s `NFR-01402`, `011`'s `NFR-01502`,
`012`'s `NFR-01603` all point here).

## B13. Proposed scope: a conformance profile, not a component

**Proposed artifact:** a shared **No-GC Conformance Profile** under `specs/_shared/no-gc-conformance/`
(or folded into each hot-path state's `requirements/nonfunctional-delta.md`), plus a reusable CI gate
(`pipeline/validate-no-gc-conformance.sh`) and a Gradle test profile.

**Intent:** define, once, what "zero steady-state allocation" means for TraderX, the JVM/run profiles that
prove and protect it, and the automated gate that fails any hot-path change that regresses it.

In scope: the allocation contract, JVM profiles, the Epsilon gate, allocation/latency measurement, warm-up,
and the determinism check. Out of scope: the component designs themselves (covered by the input/BLP/output
docs) and any edge code (Gateway/Projector), which are explicitly *allowed* to allocate.

## B14. Artifacts to author

- `specs/_shared/no-gc-conformance/README.md` — the profile, referenced by `010`/`011`/`012`.
- `requirements/no-gc-conformance.md` — the `NGC-*` requirements below.
- `system/adr-017-no-gc-conformance-and-epsilon-gate.md` — ADR recording the allocation contract + gate.
- `pipeline/validate-no-gc-conformance.sh` — the CI gate (Epsilon run + JFR check).
- A Gradle **`noGcTest`** source set / task wired with the Epsilon JVM args.
- `tests/no-gc/README.md` — how to run the gate locally; expected failure modes.

## B15. No-GC conformance requirements

- **NGC-01** — Hot-path packages (input ring, BLP, output ring) SHALL allocate **zero** bytes in the steady
  state; allocation is permitted only at startup and at the outermost edges (Gateway in, output handlers out).
- **NGC-02** — A CI **allocation gate** SHALL run the hot path under `-XX:+UseEpsilonGC` with a small fixed
  heap and **fail** on any steady-state allocation.
- **NGC-03** — Prices/quantities SHALL be `long` fixed-point on the hot path; `BigDecimal`/`String`
  conversions SHALL occur only at the edges, with rounding **unit-tested against `009`** (penny parity).
- **NGC-04** — Hot-path collections SHALL be Agrona primitive collections or arrays; no `HashMap`/
  `ConcurrentHashMap`/boxing/streams on the hot path.
- **NGC-05** — The hot path SHALL make **no `Instant.now()`/clock reads**, no RNG/UUID, and no
  `String.format`/SLF4J parameterized logging; time/ids derive from the event/sequence and logging is
  off-thread.
- **NGC-06** — Each hot-path node SHALL run a **JIT warm-up** before going live and support a **nightly
  bounce** (snapshot + replay restart).
- **NGC-07** — Production run profile SHALL use **ZGC or Shenandoah** as a sub-ms safety net; the **demo/`C2`
  profile** MAY use defaults but MUST still pass the allocation gate in CI.
- **NGC-08** — The allocation contract SHALL be validated by HdrHistogram (latency), JFR/async-profiler
  (allocation attribution), and jHiccup (pause isolation).

## B16. Build & dependency specs

| Concern | Coordinate (illustrative) |
| --- | --- |
| Off-heap + primitive collections | `org.agrona:agrona:1.22.0` |
| Binary codec | `uk.co.real-logic:sbe-tool:1.30.0` (+ build-time codec generation task) |
| Ring buffers | `com.lmax:disruptor:4.0.0` |
| Core pinning (perf profile) | `net.openhft:affinity:3.23.3` |
| Latency / micro-bench | `org.hdrhistogram:HdrHistogram:2.2.2`, `org.openjdk.jmh:jmh-core:1.37` |
| Allocation profiling (test/CI) | `async-profiler` (agent), JFR (built into the JDK) |

Java 21 / Spring Boot 3.5.14 as in `009`. Versions pinned to latest CVE-clean releases; the repo's generated
dependency CVE gate (cf. commit `de58b8f`) applies to every addition.

## B17. JVM flags & run profiles

Three profiles, selected by env/launcher:

| Flag | `demo`/`C2` | `perf` (bare metal) | `noGcTest` (CI) |
| --- | --- | --- | --- |
| `-Xms` == `-Xmx` (fixed heap) | ✅ | ✅ | ✅ (small, e.g. 64–128m) |
| `-XX:+AlwaysPreTouch` | ✅ | ✅ | ✅ |
| `-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC` | ❌ | ❌ | ✅ (the gate) |
| `-XX:+UseZGC` (or `+UseShenandoahGC`) | optional | ✅ | ❌ |
| `-XX:+UseLargePages` / `+UseTransparentHugePages` | ❌ | ✅ | ❌ |
| `-XX:+UseNUMA` | ❌ | ✅ (multi-socket) | ❌ |
| `-XX:StartFlightRecording=settings=profile` | optional | ✅ | ✅ |
| Core pinning (`isolcpus`/Affinity) | ❌ | ✅ | ❌ |

> **Container caveat (`C2`):** large pages, `isolcpus`, NUMA, and core pinning need host privileges a
> containerized demo usually lacks. The `demo`/`C2` profile MUST run correctly **without** them and MUST still
> pass the Epsilon allocation gate in CI — so allocation-freedom is verified even though the perf knobs are
> off.

## B18. Configuration keys

| Key | Default | Purpose |
| --- | --- | --- |
| `runtime.profile` | `demo` | `demo` / `perf` / `noGcTest` selector. |
| `nogc.warmup.events` | `100000` | Synthetic warm-up workload to force C2 before going live. |
| `nogc.bounce.cron` | `0 0 3 * * *` | Nightly bounce window. |
| `nogc.px.scale` | `1000000` | Global fixed-point scale (6 dp). |
| `nogc.logging.async` | `true` | Route hot-path logs to an off-thread ring. |

## B19. Observability deltas

| Metric | Type | Meaning |
| --- | --- | --- |
| `traderx_hotpath_alloc_bytes_total{node=...}` | counter | Steady-state allocation per node (**must stay ~0**). |
| `traderx_jvm_gc_pause_seconds` | histogram | GC pause distribution (should be empty/sub-ms). |
| `traderx_hotpath_latency_seconds{node=...}` | histogram | Per-node latency (HdrHistogram-backed). |
| `traderx_jit_warmup_seconds` | gauge | Warm-up duration at startup. |
| `traderx_nightly_bounce_seconds` | gauge | Last bounce (restart+replay) duration. |

Grafana: an allocation panel that **alerts if `> 0`** in steady state, a GC-pause panel (expect flat), and
per-node latency percentiles. These complement the component dashboards in the input/BLP/output states.

## B20. Success criteria & validation

- **SC-NGC-01 (gate)** — `pipeline/validate-no-gc-conformance.sh` runs each hot-path node under Epsilon and
  **fails** on any steady-state allocation; **passes** when allocation-free.
- **SC-NGC-02 (penny parity)** — `long` fixed-point arithmetic matches `009`'s `BigDecimal` outcomes across a
  rounding fixture.
- **SC-NGC-03 (latency)** — HdrHistogram reports meet the per-stage budgets in
  `LMAX-SEQUENCER-ARCHITECTURE.md` §11 (p50/p99/p99.9/max), with no GC-induced tail spikes (jHiccup).
- **SC-NGC-04 (no banned calls)** — Static/architectural check asserts the hot-path packages contain no
  `BigDecimal`, `Instant.now()`, `HashMap`/`ConcurrentHashMap`, stream pipelines, `String.format`, or SLF4J
  parameterized logging.
- **SC-NGC-05 (determinism)** — Journal replay produces identical state/output (also guards against
  allocation-driven nondeterminism).
- **SC-NGC-06 (profiles)** — `demo`/`C2` image runs without perf knobs and still passes the allocation gate in
  CI; `perf` profile documented for bare-metal benchmarking.

## B21. What `009` already gives you vs. what the gate must add

| Capability | `009` provides | The gate must add |
| --- | --- | --- |
| Working trading logic & tests | ✅ | a hot path that runs that logic allocation-free |
| Observability stack (LGTM/Prometheus) | ✅ | allocation/GC-pause/latency panels + alerts |
| CI build/publish (`C2`/GHCR) | ✅ | the Epsilon allocation gate + `noGcTest` task |
| `BigDecimal`/`String`/`HashMap`/`Instant.now()` everywhere | ✅ (the thing to remove) | `long` fixed-point, `int` symbols, Agrona, event-carried time |
| Allocation contract & proof | ❌ | **build** (Epsilon gate, JFR/async-profiler, HdrHistogram, jHiccup) |
| Warm-up + nightly bounce | ❌ | **build** |
| Run-profile separation (demo/perf/test) | ❌ | **build** |

## B22. Risks & trade-offs

| Risk | Mitigation |
| --- | --- |
| **Epsilon gate is flaky** (allocation from test scaffolding, not the hot path) | Isolate the measured region; warm up first; attribute via JFR; keep the heap small so real leaks fail fast. |
| **`long` fixed-point penny drift** | Fix scale globally (`nogc.px.scale`), centralize edge conversions, penny-parity fixture (`SC-NGC-02`). |
| **Busy-spin/pinning unavailable in containers** | `demo`/`C2` profile runs without them and still passes the allocation gate; perf knobs reserved for bare metal. |
| **Discipline erosion over time** (a `new` creeps back in) | The gate is in CI on every change; static check for banned APIs (`SC-NGC-04`). |
| **Over-engineering for a demo** | This is a faithful teaching implementation; the gate proves the property without requiring production hardware. |
| **Off-heap/flyweight complexity** | Generate SBE codecs at build time; keep buffers behind small flyweight accessors; cover with unit tests. |

---

*Companion documents: `LMAX-SEQUENCER-ARCHITECTURE.md` (full redesign), `LMAX-INPUT-DISRUPTOR.md`,
`LMAX-BLP.md`, and `LMAX-OUTPUT-DISRUPTOR.md` — each of which must satisfy this **no-GC conformance profile**.
This doc defines the cross-cutting allocation discipline and the gate that enforces it on top of state
`009-order-management-matcher`.*
