# Design: the price-derived book grid (format-8 mint, §2.2 mechanism)

**Status: BUILT 2026-08-25 (chip 3) — see §7b for the gate results and the two corrections this build made to the design. Not minted: no epoch has been wiped and nothing is deployed.** Originally written as design only: Written 2026-08-25 against the YU17 tip, after yaakov's
2026-08-24 decision (§7f of `format-8-mint-scope.md`) pulled the mechanism §2.2 had rejected into
the mint. This document is that mechanism, designed. Everything marked *measured* was read off the
live kind rig or the operative sources on 2026-08-25; nothing below is inherited from the brief
unchecked, and §1 lists where the brief was wrong.

Operative layers re-verified before reading (last carrier wins): `MatchingEngineClusteredService`
(MECS) / `InputEvent` / `BlpRiskState` → YU17, `MatchingEngine` → YU16, `LimitBook` → YU13 (sole
carrier), `ClusterNodeMain` (member health surface) → YU15, `OutputEvent` → YU13.

---

## 0. The design in one paragraph

A book's grid is derived, at every moment the book holds **no resting orders**, from the collar's
own replicated reference price, by a pure decade map (`tick = the smallest power of ten strictly
greater than the reference, capped at the global 1000`); fraction-of-par tickers keep their
mandatory ADR-060 tick 1 (that grid is about *quote granularity*, not width, and must beat the
map — §1.3); a book with no reference and no category falls to the global grid **provisionally**,
because the next order that finds it empty re-derives. The tick is **stored in T_BOOK** (format 8
grows the record by one column), so restore reads geometry instead of re-deriving it, which
eliminates the §4 unit-misreading hazard class outright. Nothing about `LimitBook`'s slot
arithmetic, the SBE schema, the wire, or the apply hot path changes. Measured against all 69 live
instruments, the map puts every half-band between 6.6% and 64.9% of price — the collar binds for
everything the feed prices, FNMA included.

---

## 1. Corrections to the brief — each one measured, per the pause-point instruction

### 1.1 "Fixtures create books before any tick" (§2.2's ordering claim) — WRONG

Measured two ways:

- **A `PRICE_TICK` neither creates nor touches a book.** `bookFor` is a private method of
  `MatchingEngine` with exactly four call sites (grep of the sole operative file, complete by
  construction): `bootstrapOrder` (restore), `bootstrapBook` (restore), `onNewOrder`:571,
  `onReplace`:994. `onPriceTick` (MatchingEngine:1084) touches only `risk.onPrice` and the mark.
  No tick path reaches a book.
- **The tick precedes the first order on every supported flow.** `handleSeed`
  (ClusterGatewayMain:2062) sequences *register → enable → PRICE_TICK per ticker inside one
  call*, and `seed-proof-fixtures.sh` runs it for the whole universe before any order flow.
  Independently, the feed adapter is live and continuously sequencing the entire universe —
  measured on the rig: `FEED … sequenced=154121 symbols=69 pendingRegistrations=0`, all 69
  publisher instruments, FNMA included. On a fresh epoch either path puts a reference in
  `BlpRiskState.lastPrice[]` before any order can clear the risk gate.

The seeder's own comment (~line 130, "the price collar band is anchored by the first LIMIT into a
book (`slotFor()`), never by a price tick") is **stale pre-ADR-066 prose** — since ADR-066,
`bandSlot` anchors a new book on the reference. Fold that correction when the seeder is next
touched.

**Where the fallback genuinely fires** (the brief's "first deliverable"): `bookFor` runs at
MatchingEngine:571 **before** `risk.decideAndReserve` at :590 — deliberate, per the comment at
:567 (price rejections must precede reservation). So an order naming a registered-but-never-ticked
ticker creates *and anchors* a book even when risk then rejects it UNKNOWN_SECURITY. Population:
proof-minted tickers in the window between registration and their own `/seed` tick, and stray
orders on garbage tickers. Rare, self-inflicted — and self-healing under this design (§2.3).

### 1.2 The coordinator's snapshot-ordering verification — right conclusion, wrong details, and moot

T_PRICE is written before T_BOOK at MECS:989/994 (not "lines 63 and 68"). More substantively: the
collar's *first-choice* reference is the **feed** price (`risk.lastPrice`), which is snapshotted in
**T_SECURITY** rows (MECS:951–957, written even earlier); T_PRICE carries the *mark*, the
fallback. Both precede T_BOOK, so the verified conclusion stood — but under this design it is
**moot**: the tick is stored in T_BOOK and restore re-derives nothing (§2.4).

### 1.3 §7f's "reference-first, category-fallback" priority — WRONG for bonds, with a live counterexample

The bond grid exists for **quote granularity**, not band width: a fraction-of-par price needs six
decimals (ADR-060's whole context). The map at a reference of 1.010420 — **CORP-JPM-20310601,
live above par on the rig right now** — yields tick 10, and a tick-10 grid refuses most legal
six-decimal bond quotes as off-grid INVALID. The category must therefore outrank the map for
fraction-of-par tickers. (At or below par the map happens to reproduce tick 1 exactly — the map
*generalizes* ADR-060 — but "happens to" is not a rule; above-par breaks it.)

### 1.4 The brief's "bootstrap price at registration" hope (its option 4) — dissolved, negatively

At the symbol-registration apply, the id was just born: **no price for it can exist in replicated
state**, on any flow. ADR-060's registration-time derivation works because it is ticker→tick.
A registration-time *price* would have to ride `SymbolRegisterMessage` — an SBE schema change,
exactly the blast radius §2.1's rationale rejects. The empty-book re-derivation (§2.3) reaches the
same end with no wire change.

### 1.5 FNMA is not "walking toward" ~$1.15 — it has arrived

Measured on the publisher: `FNMA price 1.111` (bootstrap 1.12/1.145), and the adapter is
sequencing all 69 symbols, so the members' collar reference for FNMA is already ~$1.11. The
engine's *mark* still reads 200.000000 on `/bbo` — that is the fixture seed constant, visible
because a tick seeds the mark only while no trade has printed (ADR-051); the collar is feed-first
and does not read it. The residual the decision closes is live today, not imminent.

---

## 2. The mechanism

### 2.1 The derivation, in priority order

Evaluated wherever a book's grid is (re)established — creation cold path and the empty-book
re-derivation — from replicated state only:

```
tickPxForBook(securityId):
    1. bookTickPxBySecurity[id] != 0   ->  the ADR-060 category override (bonds: 1)
                                           # granularity-mandatory, beats the map (§1.3)
    2. ref = collarReferencePx(id) > 0 ->  decadeTickPx(ref)
    3. else                            ->  BOOK_TICK_PX (global 1000) — provisional (§2.3)
```

`decadeTickPx` is exact integer arithmetic on Px units (1e-6 dollars), allocation-free:

```java
// smallest power of ten strictly greater than the reference price in dollars, capped at the
// global grid so nothing is ever COARSER than today; floor 1 by construction.
static long decadeTickPx(final long refPx) {
    long t = 1;
    while (t < 1000L && t * 1_000_000L <= refPx) {
        t *= 10;
    }
    return t;
}
```

Band half-width is `65536 × tick` Px, so the map pins every priced instrument's half-band into
(6.55%, 65.5%] of its reference — never inert, never refusing ordinary quoting. Every producible
tick (1, 10, 100, 1000) divides 10 000, so **cent prices are always on-grid** and the UI ticket
step can derive from the same value (§2.6). The cap at 1000 means no instrument gets a coarser
grid than the current global — the change is monotone: bands only ever tighten.

**No `OPTION_BOOK_TICK_PX` constant ships.** The map prices each option book off its own live
premium — which is §2.3-of-the-scope's "strike-derived bucket" upgrade path, obtained for free and
better (premium beats strike as a scale proxy). An option book created with no reference is
exactly the provisional case §2.3 heals. The §2.1-of-the-scope category widening for options is
thereby superseded; `OccSymbol.isOption` stays where it is (multiplier derivation) and the
fraction-of-par predicate stays untouched. *This supersedes a settled decision's shape (scope
§2.1) while delivering its intent — flagged for yaakov in §8.*

### 2.2 Where it runs: `bookFor`'s cold path

`MatchingEngine.bookFor` creates the book with `tickPxForBook` instead of the current
override-else-global pick. Static per book while occupied, so `LimitBook` slot arithmetic,
`bandSlot`, `rebase`, and the ADR-066 re-anchor are untouched — §3 of the scope ("re-anchor
arithmetic holds under per-security ticks") applies verbatim.

### 2.3 The empty-book re-derivation — the ordering answer, and the §5 answer

**Rule: an order admitted to a book that currently holds no resting orders first re-derives the
book's grid; if the tick changes, the book resets (tick + un-anchor) before the on-grid check
runs.** Placed in `onNewOrder` between `bookFor` (:571) and the `onGrid` check (:574). Cost on the
occupied path: one `openOrders() == 0` int compare (`LimitBook.openOrders()` already exists,
O(1)). On the empty path: a few compares and two field writes — no allocation, no array work
(an empty book's levels, occupancy and heads are already clear; verified as an implementation
gate, §7 item V2).

**This rule is doing FOUR jobs at once — it is the design's load-bearing wall. A reader who sees
only one of them will "simplify" it away and break the other three:**

1. **The frozen-accident problem is gone.** A book created before its first tick (§1.1's window)
   holds the provisional global grid only until the next order finds it empty — and it *is* empty,
   because the order that created it was rejected (UNKNOWN_SECURITY) or rests alone and its cancel
   or fill empties the book. The accident lasts one occupancy, not an epoch.
2. **Scale drift self-heals — the scope's question 5 answered without an epoch and without a
   re-index.** A security that moves orders of magnitude re-derives at its next empty moment. The
   only book that keeps a stale-scale grid is one *continuously occupied* across the whole move —
   and ADR-066 itself empties it: an order at the new price re-anchors the band there and
   strandeds-cancels the old-scale resting orders (`PRICE_COLLAR`, the existing path), after which
   the book turns over and re-derives. No re-tick-with-orders-in-flight mechanism is needed; the
   materially-harder re-index at a different scale is **not built, by design, because no state
   that needs it can persist**. Answer to "say which": *accept, with self-healing and
   observability (§2.6)* — but "accept" here costs a window, not an epoch.
3. **It is load-bearing for determinism, not just hygiene.** Un-anchored books are absent from the
   snapshot; a member that restores (no book) and one that never restarted (book exists, empty)
   must agree on the next order's grid. With re-derivation-when-empty both compute
   `tickPxForBook` from the same replicated reference at the same apply — identical. Without it,
   the survivor would keep a creation-time tick the restorer cannot reconstruct.
4. **It is what makes the unpriced fallback PROVISIONAL rather than permanent** — and therefore
   what lets the design ship with no option category constant at all (§2.1, §8): an unpriced book
   on the global grid is one tick-then-empty-admission away from its real grid, so the fallback
   never needs to be *right*, only safe.

Determinism generally: emptiness is book state (replicated), the reference is `lastPrice`/mark
(replicated, snapshotted — the same property ADR-066 stands on), and the map is pure. Same log →
same tick history on every member, on replay, and on ClusterRecon's from-scratch rebuild.

`LimitBook` change: `tickTicks` loses `final`; one method
`retick(long newTick)` asserting `openOrders == 0` (throw — a retick of an occupied book is a bug
by definition), setting `tickTicks` and `baseLevel = -1`. `onReplace` never re-ticks: its own
order rests, so the book is non-empty by construction.

### 2.4 Storage and restore: T_BOOK grows the tick column

`T_BOOK` becomes `{securityId, baseLevel, tickPx}` in format 8;
`bootstrapBook(securityId, baseLevel, tickPx)` rebuilds the book on the **stored** grid, ignoring
derivation entirely. Restore fails closed on `tickPx <= 0`, `tickPx > 1000` when no category
override exists for the id, or `10_000 % tickPx != 0` — the same fail-closed posture as the
existing off-grid/outside-band checks in `bootstrapOrder`, which remain as the second tripwire.

Consequences:

- **The §4 hazard class dies here.** `baseLevel`'s unit rides beside it forever; no future change
  to the derivation rule can silently reinterpret an old anchor. The one-time cost is the
  `MIN_READABLE_SNAPSHOT_FORMAT` 3→8 raise, which is already riding the mint — and because of it,
  restore never sees a two-column T_BOOK, so **no dual-shape reader is needed**.
- The snapshot write order (T_SECURITY/T_PRICE before T_BOOK) stops being a correctness
  dependency of this feature. It stays as-is.
- Stored tick is *required* exactly for occupied books (baseLevel must be interpretable to
  re-enter T_ORDER rows); for empty anchored books it is carried uniformly but the next admission
  re-derives anyway.

### 2.5 What does NOT change

The SBE schema and every wire format. `InputEvent`/`OutputEvent`. The gateway (its
`BOND_KEY_PREFIXES` face-quantity rule is a different question — scope §0.5 — and stays). The
apply hot path (derivation runs on the cold creation path and the rare empty-admission branch;
no allocation anywhere — noGC gates stay green, verified by the existing allocation gates, §7 V3).
`bandSlot`/`rebase`/stranded-cancel mechanics. The category predicate and its catalog
loop-closers.

### 2.6 Observability (the vacuous-pass countermeasure) and the UI step

- Engine counters `bookReticks` (re-derivations that changed a tick) beside `bandReanchors()` —
  an accident window that fires is a *counted* event, not a silent state.
- `/bbo` gains `"tickPx"` per row (ClusterNodeMain, read side). One request answers "what grid is
  this book actually on" — and the **console ticket step derives from it**, closing
  `a-prefix-is-not-a-category`'s UI landmine (the UI must never offer precision the engine
  refuses). Read-side, roll-in-place, may follow the mint.
- Scale-drift signal, computed read-side in the same handler: a row whose
  `decadeTickPx(currentRef) != tickPx` while the book is occupied renders `"tickDrift":true`.
  "Is any book on a stale grid, and which" is answerable in one request; the operator remedy is
  to cancel the residents (or wait for turnover), never an epoch.

---

## 3. Sizing — the map measured against the live universe (skill: `size-a-configuration-bound`)

The knob is the map's structure (decade buckets, cap 1000, floor 1). Binding unit, coarse side:
**half-band as a multiple of price** (an inert collar is the defect being fixed). Binding unit,
fine side: **half-band vs. legitimate short-horizon movement** (a too-tight band refuses real
quotes and strands residents on every re-anchor).

Captured 2026-08-25 from the publisher's live `/prices` (69 instruments, real traffic, not
synthetic) and replayed through the map:

| segment | n | tick | half-band | half-band / price |
|---|---|---|---|---|
| bonds & strips (category, ≤ par) | 20 | 1 | $0.0655 | 6.6% – 30.8% |
| CORP-JPM-20310601 (above par — category wins, §1.3) | 1 | 1 | $0.0655 | 6.5% |
| sub-$1 (cheap options, deep STRIP overlap) | 4 | 1 | $0.0655 | 7.1% – 30.8% |
| $1–$10 (FNMA 1.122 → **58.4%**, options) | 8 | 10 | $0.655 | 7.5% – 64.9% |
| $10–$100 (DB, UBS, BAC, C, FNF, FIS, MS, mid options) | 22 | 100 | $6.55 | 7.2% – 60.4% |
| $100–$1000 (all majors; = today's grid) | 17 | 1000 | $65.54 | 7.1% – 51.7% |

**Every instrument lands in [6.6%, 64.9%]** — none outside [5%, 70%]. Coarse-side gradient: the
worst fat-finger multiple the band admits is 1.65× (vs. 130× today on the cheapest option, 58×
on FNMA). Fine-side gradient: the tightest band is ±6.6%, against a publisher walk measured in
tenths of a percent per flush (NVDA drifted ~0.5% over a day) — two orders of magnitude of
headroom, and the re-anchor is lazy, so a band the market sits inside pays nothing. Both
gradients reported per the skill; the fine side is the one that was invisible until computed.

Edges evaluated (the skill's "at least one value each side"): floor — a $0.726 option at tick 1
gets ±9.0% (the rejected single-constant 100 gave it ±13× premium; the rejected 10 gave ±90%);
cap — NVDA at 920 keeps today's exact grid; a hypothetical $1,500 instrument would hold tick 1000
(±4.4%, tight but re-anchoring; raising the cap to 10 000 stays cents-on-grid and is the recorded
upgrade path if a four-digit instrument ever lists). Above-par bonds — category-mandatory (§1.3).

Residual, to be written into the folded issue: **an instrument the feed never prices and that
never trades keeps the provisional global grid — for a sub-$66 instrument that collar is inert.**
This is far narrower than scope §2.4's "all sub-$10 equities" (FNMA is priced and covered): it is
now exactly "unpriced", the condition the risk gate already names (`PRICE_MISSING` staleness
machinery), and the compensating statement stands: nothing else binds there either.

---

## 4. What changes where (lineage-correct)

| file | operative carrier | change | lands as |
|---|---|---|---|
| `MatchingEngine` | YU16 | `bookFor` derivation; empty-admission re-derivation in `onNewOrder`; `bootstrapBook` signature; `bookBaseTuples` grows tick; `bookReticks` counter; `decadeTickPx` | **new YU17 override** (copy-up; YU16's file untouched — its state must keep describing itself) |
| `LimitBook` | YU13 (sole carrier) | de-final `tickTicks`; `retick()` | **new YU17 override** |
| `MECS` | YU17 | SNAPSHOT_FORMAT 8, MIN_READABLE 8 (already riding); T_BOOK 3-column write/restore + fail-closed validation; cross-reference comment at the category predicate ("the scale convention lives in MatchingEngine.decadeTickPx — two conventions, two homes, each names the other") | edit in place |
| `ClusterNodeMain` | YU15 | `/bbo` tickPx + tickDrift | **new YU17 override** |
| console ticket | web tree | step from `/bbo` tickPx | follows mint (read-side) |
| `seed-proof-fixtures.sh` | scripts | fix the stale pre-ADR-066 comment (§1.1) | with the mint commit |

Before editing, re-verify each carrier per the standing rule (grep every `specs/*/` layer for
same-named files). Roll discipline: nothing new — the change rides the format-8 mint's mandatory
fresh epoch; all members + gateway off one build; no mixed window (`prove-cluster-engine-change`
§1 applies unchanged).

---

## 5. Proof set — each with a red half against the current image

Red halves are non-destructive on the shared rig (non-crossing resting probes, cancelled after;
the standing scope-§5 discipline). Every proof asserts **which gate answered** via the engine ack
reason byte 22, never an HTTP code.

| proof | claim | red half (current build) |
|---|---|---|
| `yu17-fnma-collar` | a FNMA resting limit at ~20× the live reference (~$22) is refused PRICE_COLLAR by the engine | same probe ACCEPTED and rests today — the §7f defect, live |
| `yu17-option-collar` (scope §5, unchanged) | option limit ~20× premium refused PRICE_COLLAR | accepted today |
| `yu17-fine-grid` | FNMA limit at $1.12001 (1 120 010 Px: `%10=0` new grid ✓, `%1000≠0` old ✗) **rests**; a sub-tick price refused INVALID; negative control: a $200 equity's grid unchanged both sides | the discriminating probe is refused INVALID today — a red half that *inverts*, proving the grid itself moved, not just the band |
| `yu17-book-retick` | **the decade-crossing case, end to end**: enable a minted ticker via the control path (no tick) → order rests on provisional grid (`/bbo` tickPx 1000) → cancel (book empties) → seed tick at 1.15 (reference has crossed decades) → next admission shows tickPx 10, `bookReticks` +1 — and the book **admits at the new scale while refusing at the old**: a limit inside ±$0.655 of 1.15 rests, the 20× probe ($22) is refused PRICE_COLLAR | current build: no tickPx surface, the old grid frozen for the epoch, and the 20× probe accepted. **Detonator**: the green half must fail against a build with the re-derivation deliberately omitted — the counter assertion is what cannot pass vacuously |
| `yu17-grid-restore` (off-rig, unit seams) | snapshot a tick-10 occupied FNMA book via `writeSnapshot`, restore via `onSnapshotRecord`, byte-identical digest; a format-7 snapshot refused loudly at the header; the scope-§4 demonstration case (old-derivation option book under new build) refused, never silently misread | runs against both builds' codecs; the red half is the current build accepting the format-7 restore that format 8 must refuse |
| `yu17-retick-determinism` | the full sequence (create-before-tick → reject → tick → retick → trade → re-anchor) leaves all three members digest-identical; snapshot barrier + leader kill mid-sequence changes nothing | divergence here is the finding; per `prove-cluster-engine-change` §3 the assertion end is member counters + digest, never the read model |
| existing suite | every scope-§5 row unchanged; bonds byte-identical (category-first); equities ≥ $100 byte-identical (cap) | regression guard |

**Pre-mint sweep (prove-cluster-engine-change §1b — grep the producers):** equities $10–$100
(DB, UBS, BAC, C, FNF, FIS, MS) *change behavior* — band tightens from ±$65.54 to ±$6.55. Sweep
every `/orders` producer in `scripts/` for resting prices further than the new band from the live
reference on those tickers; the §3 table gives the per-ticker band. The seeder's `hold()` crossings
are already at the live price and safe.

---

## 6. The scope document, updated by this design

- §2.2's three costs, resolved: column cost inverted by the mint (as §7f argued); sizing work
  unified — the map's measurement (§3 above) *is* the option sizing, and the separate
  `OPTION_BOOK_TICK_PX` constant is dissolved; ordering cost closed by the empty-book
  re-derivation, measured rather than assumed.
- Scope §2.3 (option constant sizing): superseded — no constant to size. The deep-ITM coarse-side
  check lands in the map table instead ($19–$36 premiums → ±18–34%).
- Scope §2.4 residual: narrows from "sub-$10 equities" to "never-priced instruments" (§3).
- Scope decision (e): discharged by §3's measurement.
- Scope §4: the MIN_READABLE 3→8 raise stays (old snapshots lack the tick column — the raise is
  now *doubly* required), but the future-hazard argument is retired by storage (§2.4).

---

## 7. Implementation gates (not design questions — must be checked while building)

- **V1**: `LimitBook.remove`/drain leaves a fully-clean empty book (levels, occupancy, heads,
  best pointers) such that `retick` needs no array work. Unit test: fill, drain, retick, assert
  full placement behavior on the new grid.
- **V2**: the empty-admission branch allocates nothing (the three allocation gates stay green).
- **V3**: `bookBaseTuples` includes anchored-empty books today — confirm, and confirm unanchored
  books are excluded (the §2.3.3 determinism argument depends on re-derivation, which V4 detonates).
- **V4**: the detonator for `yu17-book-retick` (build minus the re-derivation) fails exactly that
  proof and no other — proving no existing proof covered the accident window.
- **V5**: catalog loop-closer additions: every producible map tick divides 10 000 (unit test over
  the map's range); every Debt key still matches the category prefixes (exists); both run against
  a deliberately off-convention hypothetical.

## 7b. Gate results — BUILT 2026-08-25 (chip 3), measured

| gate | result |
|---|---|
| **V1** — an emptied `LimitBook` is clean enough that `retick` needs no array work | **PASS, measured.** `LimitBookRetickTest` fills three bid levels and one ask level with two orders each, drains, and asserts every level's head/tail/aggregate/occupancy bit and both best pointers are clear BEFORE the re-tick, then that placement, `priceAt` and best-price maintenance all behave on the new grid. `retick` is two field writes. |
| **V2** — the empty-admission branch allocates nothing | **PASS.** All four allocation gates green with the branch executing on every order in the measured window. The retick arm itself is JIT-warmed on an unpinned scratch security in `AllocationGateTest`'s warm-up, so an allocation on either path would be caught. The measured securities are pinned to the global grid through the ADR-060 category channel — the gate's `$50` deep bids and `$100` crossing level are load-bearing geometry that a ±$6.55 derived band cannot hold, and the gate's own "self-trade-prevention branch did not run" sanity assertion is what surfaced it. |
| **V3** — `bookBaseTuples` includes anchored-empty books; unanchored ones excluded | **HALF FALSE, and the correction is better than the expectation.** `bookBaseTuples` emits EVERY created book, un-anchored ones included, carrying `baseLevel` -1 (and, from format 8, its tick); `bootstrapBook` restores it as created-but-unanchored. So un-anchored books are NOT absent from the snapshot — which means **§2.3 job 3's premise is false for this build**: a survivor and a restorer cannot disagree about such a book's grid, because the grid rides the record. Storage (§2.4) subsumes job 3. Jobs 1, 2 and 4 remain entirely load-bearing (V4 below). Pinned by `PriceDerivedGridTest.gateV3_…` and by that class's `jobThree` arm, both of which say so in place so nobody "fixes" the code to match this document. |
| **V4** — the detonator fails exactly `yu17-book-retick` and no other proof | **PASS off-rig, precisely.** A build with `rederiveIfEmpty` deleted from `onNewOrder` fails **exactly five arms, all in `PriceDerivedGridTest`** — jobs 1, 2 and 4, the counter's semantics, and jobThree's tick assertion — out of 467 tests. Nothing else moves: not `GridRestoreFormatTest`, not `BookGridDerivationTest`, not `LimitBookRetickTest`, not the snapshot round trips. That is the claim: no pre-existing proof covered the accident window. The on-rig half (`yu17-book-retick` red against a deployed detonator image) is **owed by the mint chip** — it needs a deployment, which chip 3 was scoped out of. |
| **V5** — every producible map tick divides 10 000 | **PASS, swept.** `BookGridDerivationTest` walks the map across its whole producible range (0 → $2,000 at a prime-ish stride) and asserts `10_000 % tick == 0` and `1_000_000 % tick == 0` at every point, plus monotonicity in price, plus the cap and floor edges. The category loop-closers it already carried are unchanged and now sit beside the map, deliberately in one class. |

**One mechanism change against §2.1 as written:** `decadeTickPx` takes the cap as a PARAMETER and
`tickPxForBook` passes the configured global grid (`BOOK_TICK_PX`), rather than hard-coding the
literal 1000. Identical in production (nothing sets `BOOK_TICK_PX`; it is 1000 everywhere), and it
is the faithful reading of the cap's stated job — "the top decade keeps exactly today's geometry",
where today's geometry is whatever the global is configured to be. It also keeps the change monotone
for any configured value.

## 8. The §2.1-shape decision — SETTLED 2026-08-24 (yaakov, via the coordinator): map only, no constant

`OPTION_BOOK_TICK_PX` is not kept even as a fallback. An unpriced option book falls to the global
grid provisionally and self-heals on the next empty admission, same as everything else (§2.3 job
4). This supersedes the *shape* of scope §2.1 (options via a category constant) with a strictly
stronger mechanism that delivers its intent — §2.1's own text anticipated the strike-derived
upgrade, and the map's premium-derived grid is that upgrade, better. Scope decision (e) is
discharged by measurement: the sizing replay it deferred to is §3's 69-instrument table.

## Related

- `format-8-mint-scope.md` §2, §4, §7f — the frame this fills.
- ADR-066 (band follows the reference — the re-anchor this design leans on), ADR-060 (the
  category grid this generalizes but must not override, §1.3), ADR-050, ADR-051 (mark semantics),
  ADR-057.
- `issues/open/the-collar-is-inert-for-every-instrument-priced-below-par.md` — folded per §3's
  residual when this lands.
- Skills applied: `size-a-configuration-bound` (§3), `vacuous-pass-audit` (§2.6, §5 detonators),
  `prove-cluster-engine-change` (§4 roll, §5 assertion ends, §1b producer sweep),
  `a-prefix-is-not-a-category` (§1.3, §2.6 UI step, V5).
