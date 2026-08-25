# Scoping: the SNAPSHOT_FORMAT 7 → 8 mint

**Status: MINTED 2026-08-25 (chip 4).** The contents below are implemented on `YU17-otc-rates`, tagged `traderx/cluster-node:yu17-format8`, and **deployed on a fresh epoch** on `kind-traderx-yu12-cluster` — all six cluster-node components (three members, gateway, feed-adapter, risk-extract) repinned, members' PVCs wiped, `SNAPSHOT_FORMAT` 8 / `MIN_READABLE_SNAPSHOT_FORMAT` 8 live. See §9 for the mint's results, including the gate V4 detonator and the two durability proofs that had never been executed. Originally written as scoping only: Written 2026-08-24 against the YU17 tip, after
yaakov's six ADR decisions of the same day. Two deterministic-core changes share one epoch mint
(ADR-069, "Epoch consequence"): the **pre-open phase machine** (ADR-069 decisions 3+4) and the
**collar band-width fix** (`issues/open/the-collar-is-inert-for-every-instrument-priced-below-par.md`).
One wipe means one chance to have the contents right; this document is the contents.

Everything below marked *verified* was read from the operative layers on 2026-08-24:
`LimitBook` → YU13 (sole carrier), `MatchingEngine` → YU16, `MatchingEngineClusteredService`
(MECS) → YU17, `InputEvent` → YU17, `OutputEvent` → YU13, `BlpRiskState` → YU17 — re-confirmed
against the layer listing, not recalled.

---

## 0. Corrections to the record, found while scoping

These contradict the filed issue and/or the working brief, and change what rides the mint.

1. **The issue's STRIP row is wrong the same way its note row is.** STRIP tickers are
   `UST-STRIP-<yyyymmdd>` and bills are `UST-BILL-…` (verified in `cdm-catalog.ts` and the seed
   CSV) — both match the `UST-` prefix, so both already get the ADR-060 grid (tick 1, band
   ±$0.0655). The 30Y STRIP marks 0.215580 on the rig right now (coordinator, 2026-08-24) → the
   band is ~±30% and **binds**. Notes ~0.99 → ±6.6%, binds. Of the issue's four non-equity rows,
   only **listed options** survives — and on live numbers, not a typical-price estimate: `/bbo`
   on member 1 shows premiums spanning **$0.504 (`AAPL260918P00220000`) to $35.177
   (`MSFT261218P00410000`)**, the cheapest two orders of magnitude inside the ±$65.54 band.

2. **A new uncovered instrument nobody named: `FNMA`, an *equity* whose committed bootstrap
   price is $1.12.** Provenance, because the coordinator could not reproduce it and the reason is
   itself a lineage lesson: the price lives in the **publisher's** committed seed —
   `price-publisher/data/snapshot-prices.json` (`"FNMA": {openPrice: 1.12, closePrice: 1.145}`,
   lines 70–73), whose operative carrier is the **YU16** layer (YU15 carries the identical value;
   YU17 has no override) — so a grep confined to `specs/YU17` and `scripts/` misses it. The rig
   currently *marks* FNMA at 200.000000 because `scripts/yu15/seed-proof-fixtures.sh:87` POSTs a
   flat `PRICE:-200` to `/seed` for all 20 fixture tickers — the fixture constant, not the
   instrument. That is ADR-067's opening defect with the sign flipped (NVDA: seeded 200,
   published ~916; FNMA: seeded 200, published ~1.15 — a 174× disagreement), and with the feed
   adapter live since 2026-08-24 the collar's feed-first reference will walk FNMA toward ~$1.15,
   at which point the ±$65.54 global band admits a 50× fat finger on a live equity. **So the
   residual is committed-and-imminent, not hypothetical** — but the argument does not lean on it:
   a ticker convention cannot see a price, so the defective category is **price scale, not
   instrument class**, and any ticker-derived fix has a stated residual (§2.4). (DB at $17 and
   UBS at $29 are marginal: the band binds only above ~4× / ~2×.)

3. **On the cluster tier there is no gateway price screen at all.** `ClusterGatewayMain` (ADR-047)
   "screens nothing away from the authoritative core" — verified: no collar, no
   `GatewayReplicaStore` on the ingress path. The ±50% percentage pre-screen
   (`GatewayReplicaStore.screen`, `risk.price.collar-bps:5000`) belongs to the retired
   single-BLP Spring tier. So on the only rig, **the engine band is the sole price gate for
   orders**: the option/FNMA inertness is fully exposed, and — for proof design — an option
   fat-finger probe cannot be masked by a gateway collar. The only masking gates are the bond
   face-quantity rule (bonds only, `isBondKey` in the gateway) and the risk size/notional caps,
   which are effectively unlimited (`MAX_ORDER_NOTIONAL_TICKS = Long.MAX/4`).

4. **The brief's mechanism claims all verified exactly.** `FRACTION_OF_PAR_TICKER_PREFIXES` /
   `derivedBookTickPxFor` at MECS:262–279; `overrideBookTickPx` installed at symbol registration
   (MECS:670) and on restore at T_SYMBOL (MECS:1170), with T_SYMBOL written before T_BOOK so the
   book rebuilds on the derived grid. Band width = `levels × tick`; tick is the width lever.

5. **The prefix category exists in a second place**: the gateway's `BOND_KEY_PREFIXES`
   (`ClusterGatewayMain`, face-quantity rule). Per `a-prefix-is-not-a-category` step 2 it asks a
   *different* question (is quantity a USD face amount) and stays as-is for this mint — but the
   catalog loop-closer (§2.5) must cover both lists, or the next bond family skips one of them.

---

## 1. The pre-open phase machine (ADR-069 decisions 3 + 4)

### 1.1 Where the phase lives

**In MECS, beside the contract store and FX rates — not in `MatchingEngine`.** The engine stays
clock-free and *untouched* by the phase machine: MECS already owns sequenced-but-not-engine state
(contracts, `fxUsdTicksPerCurrency`) and already routes commands around the engine
(`onSwapBook`, `onFxRate`). The phase gate sits in `onSessionMessage` after decode, before the
type routing. Blast radius: one file plus the snapshot codec it already contains.

### 1.2 The sequenced command

New `InputEvent.TYPE_SESSION_CONTROL = 15` — a new command type on the existing SBE template,
exactly the shape every control since FR-IMRG11 has used; **no schema change**. Payload: the
`side` slot carries the target phase (0=CLOSED, 1=PRE_OPEN, 2=OPEN), everything else unused.
The core never knows what time it is; "6:30 ET" is *when a producer issues the command* (human
via a gateway `POST /session`; a scheduler is an opt-in producer of the identical command,
off by default — ADR-069 decision 3).

**Correlation (the regression trap):** the phase command is offered with request id 0 in the
ingress `inputSeq` slot, and answered by a new egress kind `KIND_SESSION_PHASE = 103` correlating
by its *own* request id at ack byte 13, exactly as symbol/extract/swap acks do. This matters
because the OPEN apply also emits every released order's lifecycle acks (§1.5), which echo
`applyRequestId` at bytes 24..31 — with the phase command's id **0**, those echoes "can complete
nothing" (the documented Inflight invariant), so a released order can never complete the
operator's pending. Route the phase ack any other way and we rebuild the ratcheting-offset bug.

### 1.3 The gate, per input type

| input | OPEN | PRE_OPEN | CLOSED |
|---|---|---|---|
| ORDER_NEW | normal | **queue** (cap-refused CAPACITY) | reject **MARKET_CLOSED** |
| ORDER_CANCEL | normal | queued order: remove from queue, ack CANCELED; else normal engine cancel | reject MARKET_CLOSED *(decision c)* |
| ORDER_REPLACE | normal | reject MARKET_CLOSED *(v1; upgrade path: queue-aware replace)* | reject MARKET_CLOSED |
| FORCE_FILL, TRADE_NEW | normal | reject MARKET_CLOSED | reject MARKET_CLOSED |
| PRICE_TICK | pass | **pass** | **pass** (ADR-069 decision 6: the feed never halts) |
| controls, FX_RATE, SYMBOL, RISK_EXTRACT | pass | pass | pass |
| SWAP_BOOK / SWAPTION_BOOK | pass | pass *(recommended — decision d)* | pass *(recommended)* |

`MARKET_CLOSED` is a new `RiskReason`, **appended** (the enum's own javadoc: ordinals are
serialized in snapshot order rows; never insert). It keeps a session rejection distinguishable
from a collar or risk rejection — required by ADR-069's consequences, and it must not worsen
`the-audit-surface-records-that-an-order-was-refused-not-why`.

The orderRef generator is untouched: refs are assigned at sequencing (MECS:575) *before* the
gate, so a queued order has its ref at queue time and a CLOSED rejection still consumes a value
deterministically — same posture as a duplicate retry today.

### 1.4 The queue and its snapshot

MECS holds `List<long[]>` of `{orderRef, accountId, securityId, side, qty, limitPx,
clientOrderKey, eventTimeMillis}` — the full replicated content of a queued ORDER_NEW.
(`seq`/`ingressNanos`/request id are not state, exactly as `applyRequestId` is not.)

- **Insertion order is log order and is load-bearing** (like the contract store): it is the
  release order at the open. Written to and restored from the snapshot in that order.
- **Bounded: `MAX_QUEUED_ORDERS`** (strawman 4096 — 68 bytes/row ≈ 280 KB of snapshot, inside
  the budget the idempotency table set; the second gradient is the OPEN apply's output cascade,
  which `drainOnBackpressure` already bounds structurally). At cap: reject CAPACITY,
  deterministically, exactly as MAX_CONTRACTS does. Size it by the `size-a-configuration-bound`
  procedure before the mint; both gradients above are the ones to measure.
- **Idempotency at queue time**: a retried `clientOrderKey` must find the queued original, not
  queue twice. Recommended: a transient key→queue-index map, rebuilt from the queue on restore
  (never snapshotted — derived state, the ADR-052/060 pattern). The risk-table entry is written
  at *open* time when the decision actually runs.
- **Snapshot format 8**: two new record types, `T_SESSION = 14` `{phase}` and
  `T_QUEUED_ORDER = 15` (8 columns above). Restore fails closed on a queued ref ≥ `nextOrderRef`
  (the T_ORDER rule) and on out-of-order rows (the T_CONTRACT rule). Header shape unchanged.
  A member that snapshots CLOSED restores CLOSED — the entire argument for consensus over the
  gateway, made mechanical.

### 1.5 What happens at the open

Inside the OPEN command's single apply, each queued entry is replayed through the engine's
normal path (`engine.onEvent`, ORDER_NEW) **in insertion order**. That makes release order a
*decision*, not an accident: insertion order = sequencing order = the same time priority the
book's FIFO already derives from the log. (An opening auction / uncross is explicitly not this —
ADR-069 rejected it; continuous open in arrival order.)

- **Band and risk are judged at the open, not at queue time.** The band must be judged against
  the open's reference — the feed keeps ticking through the halt and the band re-anchors across
  it (ADR-069's stated desired behaviour) — and reservations must not be held overnight against
  control state that can change while queued. A queue-time decision would also make the
  queue-time ack a lie.
- Determinism is trivial: one apply, one thread, one order. The identical replay happens on every
  member and on log replay.
- The cascade is bounded by the queue cap; a release burst larger than the output ring is the
  case `drainOnBackpressure` already exists for.

### 1.6 What the acks say (ADR-069's question, answered)

- **At queue time**: an `OutputEvent` with `KIND_ORDER_ACCEPTED`, **new
  `STATUS_QUEUED = 5`** (appended after STATUS_REJECTED=4; the status byte is snapshot-serialized
  like riskReason, so append-only). This is the client's direct ack — it completes the pending,
  names the orderRef, and says *queued*, not *working*. Emitted by MECS into the output ring it
  already owns (same thread, single producer) so the order bridge / read model / console see the
  order exist in QUEUED state. The console must render the new status (small UI touch —
  decision g).
- **At open time**: the released order's *normal* lifecycle events — ACCEPTED (now STATUS_NEW),
  then fills/rejections exactly as a live order — published to the read model as usual, echoing
  request id 0 (§1.2) so they complete no pending. A rejection at the open (collar, risk) is a
  sequenced, audited REJECTED transition of an order the client already holds a ref for.

### 1.7 Observability (the vacuous-pass countermeasure)

The member `/health` (beside `/bbo`) reports `phase` and `queueDepth`. "Was the market open, and
is anything queued?" must be answerable in one request — the ADR-069 trap section's rule, applied
here from the first commit. The phase-command ack carries the applied sequence, so a halt is a
named log position.

---

## 2. The band-width fix

### 2.1 The predicate that replaces the two-entry allowlist

**Stay a pure function of the committed ticker, and widen by *category function*, not by prefix
count** (`a-prefix-is-not-a-category` step 3; and its boundary exception explicitly blesses this
site: the deterministic core must derive the grid from committed state, and T_SYMBOL's whole
content is the ticker — the instrument-static join would mean carrying static through consensus,
i.e. the SBE schema, far more blast radius than a grid is worth. That rationale is already
written at the constant and survives review.)

```
derivedBookTickPxFor(ticker):
    isFractionOfParTicker(ticker)  ->  BOND_BOOK_TICK_PX   (1;    unchanged, UST-/CORP-)
    OccSymbol.isOption(ticker)     ->  OPTION_BOOK_TICK_PX (new;  strawman 100 = $0.0001)
    else                           ->  0                   (global 1000 grid)
```

`OccSymbol.isOption` **already exists in the core** (YU14, ADR-052) as a pure ticker function,
already proven deterministic at registration and restore — it is the same derivation that installs
the ×100 multiplier today. The category function for options costs zero new convention.

At tick 100 the option band is `131072 × 100` = $13.11 (±$6.55). Against the premiums measured
live on the rig ($0.504 – $35.177, §0.1): the cheapest option is refused at ~13× premium (weak,
but a 20× fat finger is caught — vs 130× inside the band today) and the dearest gets ±19%
relative width, with moves beyond either re-anchoring lazily on the feed-tracked reference
rather than refusing (ADR-066). A cent ($0.01 = 10 000 Px) stays on-grid (10 000 % 100 = 0), so
quoted increments and the UI step survive. **The constant is a strawman until sized** — §2.3;
note the measured 70× premium span is exactly why one constant is a compromise and the
strike-derived upgrade path exists (§2.3).

### 2.2 The alternative considered and rejected: reference-price-derived tick at book creation

Deriving the tick from the collar reference at `bookFor` time (and storing it in T_BOOK) would
cover FNMA too — it is the only rule that can. Rejected for this mint, on three costs:

- **Ordering-sensitive**: a book created by an order that beats the first tick falls back to the
  global grid *permanently for the epoch* (the stored tick freezes the accident). ~~Fixtures do
  exactly this today.~~ **Measured false 2026-08-25** (design doc §1.1): `/seed` sequences
  register→enable→tick per ticker before any order flow, and the feed adapter live-sequences the
  whole universe, so the reference precedes the first order on every supported flow; the real
  window is a book created by a *rejected* order on a never-ticked ticker (`bookFor` runs before
  `decideAndReserve`), and the design's empty-book re-derivation makes it last one occupancy, not
  an epoch. The seeder's ~line-130 "anchored by the first LIMIT, never by a price tick" comment is
  stale pre-ADR-066 prose — delete it in the implementation pass.
- **T_BOOK grows a column** and restore forks on format — more moving parts in the one record
  whose misreading is silent (§3).
- The scale→tick map needs the same sizing work as the option constant anyway.

Record the rejection so it is not re-derived; it is the natural upgrade path if the FNMA residual
ever bites (§2.4).

### 2.3 Sizing `OPTION_BOOK_TICK_PX` (before the mint, offline)

Per `size-a-configuration-bound`: the binding unit is **band as a multiple of premium**, and the
bound has two sides — too coarse and the collar is inert (today), too fine and the band refuses
legitimate re-quotes and strands resting quotes on every re-anchor. The live endpoints are already measured
(coordinator, `/bbo` 2026-08-24): **$0.504 to $35.177**, a 70× span no single constant serves
evenly — at tick 100 that is 13× coverage at the cheap end and ±19% at the dear end, at tick 10
the cheap end tightens to ±1.3× premium but the dear end collapses to ±1.9%, which refuses
ordinary quoting. Complete the procedure by replaying the candidates {10, 100, 1000} against the
full chain (the publisher reprices options from the underlying — `main.js`;
`seed-option-chain.sh` seeds it), and report both gradients at each. If neither endpoint is
acceptable at one constant, the upgrade path is a strike-derived bucket — the strike is in the
ticker (`OccSymbol.strikeThousandths`), so it stays a pure ticker function. Constraints that survive any
answer: the tick must divide 10 000 (cents stay on-grid) and the UI ticket step must be derived
from the same convention (the skill's UI landmine). Deep-ITM premiums (~$100+ on NVDA strikes)
are the coarse-side check: at tick 100 the band is ±6.5% there, and the lazy re-anchor is what
keeps that from refusing a tracking market — which is why §4's proof set includes a re-anchor
case on an option book.

### 2.4 The stated residual (issue direction 3, applied narrowly)

**Sub-$10 equities — FNMA at $1.12 concretely — stay on the global grid.** No ticker signal
exists; the compensating risk gates are effectively off (`MAX_ORDER_NOTIONAL_TICKS = Long.MAX/4`,
memory: cluster collar & capacities). This must be *written into the issue* when it is folded
(§5), not silently accepted: "the collar is a control for instruments whose ticker names their
price scale; for penny-priced equities it does not bind, and nothing else does either." Options
if that residual ever needs closing: the rejected §2.2 mechanism, or a real sized notional cap
(policy control — sequenced, non-core, roll-in-place).

### 2.5 Closing the loop (the skill's step 5/6, both directions)

- `cdm-catalog.spec.ts`: every `securityType === 'Debt'` key matches the fraction-of-par
  prefixes (exists today); **add**: every option instrument key parses under the OCC convention
  (`isOption` mirror), and both assertions run against a deliberately off-convention hypothetical
  to prove they can fire.
- **Negative control**: a plain equity still gets the 1000 grid — `yu16-book-grid.sh` half 2
  already proves exactly this shape (its header says why: widening the grid globally passes every
  bond assertion while silently widening the equity band a thousandfold); extend it with an
  option leg.
- The gateway's `BOND_KEY_PREFIXES` (different question, stays narrow) is covered by the same
  catalog assertion so the two lists cannot drift silently.

---

## 3. The ADR-066 interaction (question 3): confirmed, not assumed

**The re-anchor arithmetic holds under per-security ticks.** Verified in the operative sources:
every quantity in the re-index is denominated in the *book's own* tick — `slotFor` divides by
`tickTicks` (LimitBook:98), `baseCentredOn` takes an absolute tick level, `bandSlot` computes the
new base via `ref / book.tickTicks()` (MatchingEngine:862), and `rebase`'s
`delta = baseLevel − newBaseLevel` is a slot-index shift within one book (LimitBook:141). Books
never share slot math; a fine grid merely makes deltas larger, and `rebase` already fails closed
past the int slot range (LimitBook:145). Verdict: **holds; no code change needed** — but the
proof set includes one re-anchor-on-an-option-book case so this stays a measured fact, not a
reading.

---

## 4. Book geometry across the mint (question 4) — and the finding that makes the mint mandatory

Fresh epoch → empty books → changing a security's derived tick is free: nothing rests, no slot
moves. That part is as the brief said. The hiding question is **old epochs**:

**T_BOOK's `baseLevel` is denominated in the book's tick, and the tick is derived, not stored.**
Change the derivation and a format-7 snapshot's anchor for a reclassified ticker (any option) is
silently reinterpreted in the wrong unit on restore — and a from-scratch log replay
(ClusterRecon, or a member with no snapshot) re-derives geometry that disagrees with what the
epoch's history actually did. Whether any option book with resting orders exists in a given old
epoch is exactly the **data-dependent compatibility** the format-4 postmortem (MECS:167) exists
to forbid: the hazard must be unconditional and legible at the header.

**Therefore: `MIN_READABLE_SNAPSHOT_FORMAT` goes 3 → 8** — its first raise ever. The new build
refuses to restore any pre-8 snapshot, which makes the fresh epoch *mandatory*, not merely
budgeted: the standing kind-rig epoch cannot roll forward onto this build. (The queue/phase
records alone would have been MIN_READABLE-preserving additions; it is the width fix that closes
the door, and the same reasoning applies to *any* future change of the grid derivation — worth a
sentence at the constant.)

**Status of this finding: REASONED, not measured** (coordinator, 2026-08-24) — it was read from
the restore path, not demonstrated. Demonstrate it before anyone relies on it, off-rig, via the
unit seams MECS already exposes (`writeSnapshot` / `onSnapshotRecord`, no cluster needed):

- write a snapshot under the *current* derivation containing an option-ticker book
  (e.g. `AAPL260918C00260000`, equity grid, `baseLevel` in 1000-Px-tick units) with one resting
  order, restore it under the widened derivation, and observe either the fail-closed
  `"restore incomplete: open order outside restored book band"` or — the worse case the raise
  exists for — an *empty* book restoring silently with an absurd anchor;
- replay a synthetic epoch's full log from scratch under the new build and diff the resulting
  book geometry against the epoch's own snapshot (the ClusterRecon shape).

If both come back clean, the MIN_READABLE raise is unnecessary and this section is wrong; either
way the mint proceeds on a demonstration, not an inference.

Roll discipline is the standing one, now with no shortcut: scale to zero, wipe PVCs, mint fresh,
all members + gateway off one build (`prove-cluster-engine-change` §1 mixed-version and log-tail
rules; the snapshot barrier is moot on a wiped epoch but the no-mixed-window rule is not).

---

## 5. The proof set (question 5) — must exist before the mint, each with its red half

An inert guard accepts everything, which is indistinguishable from a working one — so every new
proof must be shown to **fail against the current build first** (`prove-cluster-engine-change`
§2: keep the pre-change image tagged, run the proof red against it, then green on the mint).
The red halves are non-destructive on the shared rig: probe with a *non-crossing resting* limit
far off the reference — accepted-and-resting is the defect made visible, then cancel it. No
garbage trade enters the epoch.

| proof | claim | red half (current build) |
|---|---|---|
| `yu17-option-collar` | an option limit ~20× premium is refused PRICE_COLLAR **by the engine** (ack byte 22), at a qty clearing every other gate | same probe is ACCEPTED and rests — the defect, live; proof asserts refusal → red. §0.3 means no gateway collar can mask it; only options — the bond lot rule can't fire |
| `yu16-book-grid` extension | option leg: cent-grid limit rests, sub-tick limit refused INVALID; equity negative control unchanged | option sub-tick limit accepted today (global grid) → red |
| `yu17-band-reanchor-option` | lazy re-anchor on a fine-grid option book: far-but-market-backed limit re-anchors, stranded quote cancels PRICE_COLLAR | n/a on current build (no fine grid to re-anchor) — its guard is the ADR-066 counters moving |
| `yu17-session-closed-rejects` | CLOSED: order → 422 MARKET_CLOSED, reason distinct from collar/risk | current build accepts → red |
| `yu17-preopen-queue-open` | PRE_OPEN: crossing orders ack QUEUED, book digest and trade counters *unchanged*; OPEN: released in insertion order, fills byte-identical on all three members (engine counters + digest per `prove-cluster-engine-change` §3, never the read model) | current build fills immediately → red on the "nothing traded while queued" assertion |
| `yu17-halt-survives-failover` | PRE_OPEN with non-empty queue → snapshot barrier → kill leader → new leader still PRE_OPEN, queueDepth intact, OPEN releases identically | the entire consensus-over-gateway argument, made a measured fact; red half: a build with the queue left out of the snapshot (or the current build) reopens/loses it |
| `yu17-closed-survives-restart` | member restart while CLOSED comes back CLOSED and still rejects | the ADR's headline sentence ("a halt a restart can bypass is not a halt") |
| existing collar proofs | `yu03-risk-proof`, `yu10-fix-session`, `yu13-cancel-ingress`, `yu13-stp-and-replace`, `yu17-band-follows-market` stay green | regression guard: every one is equity-priced (verified — IBM/BAC/MSFT), so the width fix must not move them |

Each proof names **which gate answered** — the issue's own "another gate may refuse it first"
caveat, resolved by asserting the engine ack's reason byte, not an HTTP code.

Re-anchor-through-the-halt (feed ticks while CLOSED, band centred at open) is a behaviour proof
with no format dependency; it can land with the staleness work post-mint (§6).

---

## 6. Ordering and risk (question 6)

**Rides the mint (must be right before the wipe):**
format 8 + MIN_READABLE 8; phase enum + gate + queue + T_SESSION/T_QUEUED_ORDER;
TYPE_SESSION_CONTROL + KIND_SESSION_PHASE + MARKET_CLOSED + STATUS_QUEUED (all appends,
ids verified non-colliding: input types end at 14, egress kinds at 102, statuses at 4, T_ records
at 13); the widened grid derivation + OPTION_BOOK_TICK_PX sized; catalog loop-closers; the proof
set above with red halves banked.

**Explicitly NOT riding (follows, roll-in-place or off-core):**
opening-price-from-prior-close (ADR-069 rules 1–4 — publisher/trade-processor, no core);
staleness cancel at the open (rule 7 — core behaviour but format-stable; its cancel needs its own
appended reason and its own proof, a later roll); `/bbo` consumers; the scheduler producer;
console polish beyond rendering QUEUED.

**Order of work:** (1) decisions a–g below; (2) off-rig: predicate + constants + catalog tests +
option-premium sizing capture, phase machine + snapshot round-trip unit tests (MECS's
`writeSnapshot`/`onSnapshotRecord` seams need no cluster); (3) proof scripts written, red halves
run against the current image (ordinary read-and-cancel traffic — needs rig access but mutates
nothing durable); (4) the mint: one scale-to-zero + PVC wipe + fresh epoch, green halves, full
`run-proofs.sh`; (5) the follow-ons.

**Biggest risks, in order:** (i) something else discovered to belong in format 8 after the wipe —
mitigated by `traderx-snapshot-completeness-audit` over the new state before minting; (ii) the
open-release correlation regression (§1.2) — mitigated by request-id-0 + own-kind ack, and the
queue proof asserts the operator's pending completes with KIND_SESSION_PHASE, never an order ack;
(iii) OPTION tick mis-sized — mitigated by doing the §2.3 measurement first and by the re-anchor
proof; (iv) a proof that passes vacuously — mitigated by every red half being mandatory.

---

## 8. BUILT 2026-08-25 (chip 3) — what landed, what it corrected, what the mint still owes

**Landed on `YU17-otc-rates`, full unfiltered `order-matcher` suite green (471 tests / 91 classes,
up from 429 / 87), plus all six composed service modules (209 tests).**

Everything §6 lists as riding the mint is built: `SNAPSHOT_FORMAT` 7→8 and
`MIN_READABLE_SNAPSHOT_FORMAT` 3→8; `TYPE_SESSION_CONTROL = 15`, `KIND_SESSION_PHASE = 103`,
`MARKET_CLOSED`, `STATUS_QUEUED = 5`; the phase machine, its gate table and the bounded queue with
`T_SESSION`/`T_QUEUED_ORDER`; the price-derived grid with `T_BOOK`'s tick column; `/session`,
`/health` `phase`+`queueDepth`, `/bbo` `tickPx`+`tickDrift`, `traderx_book_reticks`; the console's
QUEUED rendering and the read-model/DB widening it needs.

**Decisions taken while building, each recorded at the code:**

1. **Decision (b)'s queue-cancel got its OWN appended reason, `SESSION_CANCELED`** — ruled by the
   coordinator, built as ruled. A client must be able to tell "refused because we were closed"
   (`MARKET_CLOSED`) from "the order you already hold was cancelled when the session halted": those
   are different events calling for different client actions.
2. **`MAX_QUEUED_ORDERS = 4096`, sized by measurement** (`QueuedOrderSizingTest` prints the table on
   every run): 68 B/row measured on the wire → 272 KB at the cap, 26× inside the ~7 MB the
   idempotency table already spends per snapshot; 1 output per released rest → 4096 outputs = 6.3%
   of the 65,536-slot output ring, where 65536 would be exactly 100% and make
   `drainOnBackpressure` the release's normal path on the most important apply of the day.
3. **Two completeness guards ride the mint** (yaakov's decision): a restore-side presence bitmask
   over the record types a format-8 snapshot must contain, and `T_SESSION`'s `queueDepth` column
   with a count assertion at `finishLoad`. `queueDepth` is written from the LIVE queue, never
   tallied from the write loop's output. A general `T_MANIFEST` was rejected as premature and is
   not built.
4. **The per-format record-width dispatch was DELETED, not kept as a dead branch.** With
   `MIN_READABLE == SNAPSHOT_FORMAT` exactly one format is readable, so the format-5 T_CONTRACT
   reader could never take its other arm; keeping it would have been a build claiming a
   compatibility it does not have. `SwapBookingTest`'s three forward-roll arms are inverted to
   refusals, and each says in place what coverage that cost.

**Corrections this build made to the documents above:** see the design doc §7b — V3's expectation
about un-anchored books is false in this code (they ARE snapshotted), which means §2.3 job 3's
determinism premise is subsumed by the storage decision rather than provided by the re-derivation;
and `decadeTickPx` takes its cap as a parameter.

**What chip 4 (the mint) owed, and how each was discharged — see §9 for the measurements:**

- ~~the read-model DATABASE must be recreated with the epoch~~ — **resolved differently, and the
  recreation must NOT happen.** `orderbook` and `eod_price_snapshot` share one schema, and that
  table holds ~3,000 rows of genuine EOD closes ADR-069 is built on; dropping the database to
  re-run the init configmap would take them with it. yaakov applied the widened CHECK constraint
  in place instead. `rebuild_fresh_epoch` deletes only `pvc -l app=order-matcher-cluster`, so the
  read-model DB is never in the blast radius. Verified live before and after both wipes;
- **the on-rig half of gate V4** — discharged, see the design doc §7b V4 and §9 below;
- the destructive proofs and the deliberate-red retirement — discharged, §9;
- moving `issues/open/the-collar-is-inert-for-every-instrument-priced-below-par.md` to
  `issues/resolved/` — done.

## 9. MINTED 2026-08-25 (chip 4) — the epoch, the gates, and five arms that could never pass

**The mint.** Manifest pins moved `:yu17-bbo` → `:yu17-format8` (statefulset, gateway, feed-adapter,
risk-extract — the four YU17 cluster-node declarations), so `run-proofs.sh`'s image guard was
satisfied by moving the pins rather than defeated; `ALLOW_IMAGE_CHANGE=1` covered the one deliberate
build change. Members scaled to zero, `pvc -l app=order-matcher-cluster` deleted, fresh epoch, all
six cluster-node components repinned. `SNAPSHOT_FORMAT=8`, `MIN_READABLE_SNAPSHOT_FORMAT=8`,
`MAX_QUEUED_ORDERS=4096`, `KIND_SESSION_PHASE=103`, `STATUS_QUEUED=5` confirmed in the shipped tree.

Three-member agreement immediately after, one capture per member:

```
applied     10983 / 10983 / 10983
orderhash   -2949526529971486893   (identical)
poshash      5017019969314474605   (identical)
snapshots   5 / 5 / 5
phase       OPEN / OPEN / OPEN      <- decision (a), on a fresh epoch
queueDepth  0 / 0 / 0
```

**The read-model database was NOT recreated, and must not be.** §8's first bullet was wrong about
the remedy. `orderbook` and `eod_price_snapshot` share one schema and that table holds ~3,000 rows
of genuine EOD closes; yaakov widened the CHECK constraint in place instead. Verified before the
first wipe and after both: `status in ('NEW','PARTIALLY_FILLED','FILLED','CANCELED','REJECTED',
'QUEUED')`, 3022 snapshot rows unchanged, `eod-price-db-data` PVC untouched (age 6d13h against the
members' 2m58s). `yu17-preopen-queue-open` passes, so the QUEUED write path works end to end.

### Gate V4, on-rig half — DISCHARGED

`traderx/cluster-node:yu17-format8-detonator` = today's tree minus the single line
`rederiveIfEmpty(book, e.securityId);` in `onNewOrder`, built through `build-cluster-image.sh` with
`OM_DIR` on a copy so the shared generated tree was never patched, and confirmed a different binary.
Deployed on its own fresh epoch. `yu17-book-retick EXPECT=after` goes red at **step 5**, the
re-derivation discriminator — `SELL @22.00 -> kind=1`, the 20× probe ACCEPTED where the minted build
refuses it PRICE_COLLAR — with `traderx_book_reticks{member="0"} 0` (exported, never moved) beside
`traderx_band_reanchors{member="0"} 1` (the old mechanism answering). The red is the missing
mechanism, not a missing counter and not a seeding failure.

### What the mint actually found: FIVE arms that could not pass, in four proofs

Every one had been written before the build and never executed against it, because the pre-mint
build failed each proof earlier for a *true* reason. This is the argument for gate V4's on-rig half
having been owed at all — and for running a deliberately-red proof suite green at least once.

| where | the arm | why it could not pass |
|---|---|---|
| `yu17-book-retick` step 2 | `${ROW} == *'"tickPx":1000'*` | `bbo_json` uses `json.dumps` default separators — the row says `"tickPx": 1000`, **with a space** |
| `yu17-book-retick` step 6 | `${ROW} == *'"tickPx":10'*` | `"tickPx":10` is a **prefix of** `"tickPx":1000` — satisfied by the book whose grid never moved (could never *fail*) |
| `yu17-session-closed-rejects` decision (d) | swap booked while CLOSED | posted `{"payFixed":true,"currency":"USD","tenorMonths":60}`; `/swaps` requires `payReceive`/`effectiveDate`/`maturityDate`/`conventions` and 400s first. Its failure text blamed the **session** for a field-name 400 |
| `yu17-retick-determinism` step 4 | ack `kind` 3 or 4 on the cross | the gateway completes a pipelined `/orders` on the **first** ack for the request id; a crossing order emits ACCEPTED *then* its fills under one id, so `kind=1` is the designed answer. Measured on a re-ticked *and* an ordinary book: both `kind=1`, both +2 trade legs |
| `yu17-retick-determinism` step 6 | post-failover BUY must rest | `clientOrderId` was `"${TICKER}-<side>-<price>"`, identical to step 3's — the gateway dedups on it, so step 6 replayed step 3's ref and its *terminal* verdict (FILLED). The arm never submitted an order at all |

A sixth, of the already-known class: `yu17-retick-determinism` step 3 compared `traderx_book_reticks`
**absolutes** across members. That counter is a per-process field, never snapshotted, so a member
restarted at any point since the epoch began reads lower for ever — measured `[4] [1] [4]` on a
cluster in perfect digest agreement. This proof's own step 6 warns against exactly that comparison.
Now a per-member delta, asserted as **exactly one** re-derivation each:
`deltas [1] [1] [1]; absolutes [5] [2] [5] differ legitimately by restart history`.

All fixed by parsing the value rather than the rendering, by matching the API the gateway actually
serves, and by reading the counters the README already prescribes. None was fixed by loosening.

### The two durability proofs, executed for the first time

Both had `DESTRUCTIVE=0` defaults and had never run live. Both failed their first run for the same
reason and it was not the claim: `kubectl get pod -o jsonpath='{...containerStatuses[0].ready}'`
**still reports the pod you just deleted as ready**, for about 6 seconds, so the wait loop broke
instantly and `exec`'d into a dying container — rendering as `phase=<absent>`, which reads like the
phase was lost and means nobody answered. And once the new pod does answer, `phase` is readable
before it is *correct*: a member that has not replayed the session command serves the fresh-epoch
default OPEN. Measured, venue CLOSED throughout: `14s phase=OPEN engineApplied=-1` … 24 seconds of
it … `38s phase=CLOSED`.

Retrying until it answers makes the assertion a coin flip; retrying until it says CLOSED deletes it.
The gate that is neither is `await_member_restored` (new `lib-consensus-readings.sh`): wait for a
**different pod uid** and for the member to have **applied the sequence the halt landed at**, which
`POST /session` returns. A member past that sequence has replayed the command, so if it still reads
OPEN the proof must go red. Results:

- `yu17-halt-survives-failover DESTRUCTIVE=1` — PRE_OPEN and `queueDepth` 3 on **all three** members
  after a snapshot barrier and a leader kill; the open then released the restored queue in insertion
  order (A1 filled, A2 resting).
- `yu17-closed-survives-restart DESTRUCTIVE=1` — CLOSED on all three after a **follower** restart
  (`member-2 restored: applied 3124 >= halt sequence 2852`), and again after a **leader** restart
  (`applied 3403 >= 3335`), which exercises the election as well as the restore. Same probe, same
  MARKET_CLOSED, nothing traded.

*A halt a restart can bypass is not a halt* — measured, on both paths.

### The suite's other two reds, neither of them format 8

Both were found by running the suite green for the first time, and neither is a mint defect.

**`yu13-otel-reject-trace-log-join`** — the proof rolls the gateway *and* all three members to set
`OTEL_SAMPLE_MASK`, then seeds a fixture ticker immediately after `rollout status` returns. That is
a race, and it is now measured: members rolled and nothing else, seeding at fixed offsets after
`rollout status` returned — `+0s {"error":"TimeoutException"}`, `+10s` onwards `{"seeded":true}`.
The window is under ten seconds and the proof lands inside it every time (three consecutive
reproductions, always the same line). Pods being Ready is a statement about HTTP servers listening,
not about members having rejoined consensus — the same gap `await_member_restored` exists to close
for the phase. The fixture seed now retries for 60s; the assertions are untouched. Filed as
`issues/resolved/rollout-status-returns-before-the-cluster-can-sequence-a-write.md`, which also records
that the gateway's 503 catch-all reports `e.getClass().getSimpleName()` and logs nothing, so a
log capture across all three reproductions caught no exception at all.

**Two more, found only because the suite was run twice on one epoch.** Both passed on the first
run and failed on the second, which is the signature of state a proof leaves behind rather than of
anything the mint changed.

- **`yu15-risk-extract`** reported `accounts=3 halted=2` — "an unpriced holding blocks its P&L" —
  at suite position 13, while standalone on a clean projection it reports `accounts=4 halted=0`.
  The cause is a full suite-length away from the symptom: `seed-proof-fixtures.sh` clears positions
  in generated throwaway instruments, but its prefix list had fallen behind the proofs by nine
  (`DUP|RM|STP|Z|BND` against a suite also minting CSR, HSF, KAC, PQO, RJT, RPL, RTD, RTK, SES).
  Three of those book trades — `yu17-halt-survives-failover` fills a queued order at the open,
  `yu17-retick-determinism` crosses its resting bid, `yu17-preopen-queue-open` books one match — so
  the format-8 proof set is itself what began leaking positions the cleaner did not know about. The
  list now tracks the proofs, still scoped to generated prefixes followed by a digit so
  `yu06-consumer-halt`'s genuine halt condition is untouched.
- **`yu05-recon`**'s negative control failed: "a PLANTED persistent mismatch was NOT caught by a
  fresh classification". `fresh_classification()` returned on the first poll where `matched > 0` —
  mid-sweep. On a 42-trade epoch it read `matched 22` against `42/42` rows, and 3b plants its
  mismatch on the OLDEST projection row, which the sweep reaches last. So the control read
  `fieldMismatch=0`, which is exactly what a clean projection reads: it could not tell "no mismatch"
  from "not looked yet", and the epoch only had to grow past one poll's worth of sweep for the
  negative control to stop being one. Now gated on quiescence of the whole
  matched/missing/fieldMismatch triple, the same rule the cross-member readings use.

**`yu13-stp-and-replace`** — its own preflight refused, correctly: `traderx/cluster-node:
stp-boundary-pre` was built 2026-08-23 and the generated source has moved since, so the proof would
have crossed a version boundary that is no longer the system's. Pre-existing drift, unrelated to the
mint. The rebuild then had to be made honest before it took: `stp-boundary-fix` is today's tree
unmodified, so its jar was byte-identical to `:yu17-format8` and Docker handed back that image
**with its original `Created` of `06:17:54Z`** — older than the generated sources at `06:47Z`, so
the preflight refused the pair it had just asked for, and would have done so for ever. Both sides
now build with `--no-cache` (`DOCKER_NO_CACHE`), because `Created` is load-bearing for that guard
and a cache hit makes it a lie. The guard was right every time; the build was not. Worth noting what that rebuild
now means: both sides of the synthesized pair are built from today's tree, so the boundary it
crosses is format 8 → format 8 with STP and `/replace` removed on the `pre` side. It is the
BEHAVIOURAL boundary and nothing else — a real format-and-capacity gap is still uncovered
(`issues/open/nothing-proves-recovery-across-a-real-format-and-capacity-gap.md`), and format 8's
`MIN_READABLE_SNAPSHOT_FORMAT = 8` widens that gap rather than narrowing it: no build before this
one can read a format-8 snapshot, by design.

## 7. Decisions — SETTLED 2026-08-24 (yaakov)

Six decided; (e) is a measurement, not a decision. **Two went against the recommendation and both
widened the work — record the reasoning, not just the verdict.**

**a. Fresh-epoch default phase: OPEN.** As recommended. Every proof and fixture assumes a trading
book; CLOSED-until-commanded is the faithful posture and remains available by issuing the command
during bring-up. Nothing is foreclosed.

**b. PRE_OPEN → CLOSED with a non-empty queue: CANCEL THE QUEUE.** As recommended. Each queued order
CANCELED with a session reason (one appended `RiskReason`). This is ADR-069 decision 5's principle
applied one level out — *a halt a restart can bypass is not a halt*, and a halt that pending client
orders can block is not one either.

**c. Cancels during CLOSED: ALLOWED. ⚠ This overrides the recommendation.**
The recommendation was reject `MARKET_CLOSED`, on the grounds that GTC-cancel-overnight is an OMS
courtesy this tier does not owe. The deciding argument the other way: **a cancel only ever REDUCES
exposure.** It cannot cross, cannot trade, cannot move a price, and cannot re-open a halted book — so
permitting it during a halt is *safer* than forbidding it, not laxer. Forbidding it locks a client
into a resting order until the open, where ADR-069 decision 7 re-validates against the mark and may
fill or cancel it on terms the client never saw and could not act on. The machinery is already being
built for (b).
PRE_OPEN is unchanged: cancel allowed (queued and resting), replace rejected.

**d. OTC swap/swaption bookings do NOT halt with the session.** As recommended. The halt is the
*venue's book*; bilateral desk business never touches it, and one session concept spanning both would
conflate two unrelated things.

**e. `OPTION_BOOK_TICK_PX`: NOT DECIDED — do the §2.3 sizing replay first.** Picking the number before
measuring is precisely what `size-a-configuration-bound` exists to prevent. It is a pre-mint task, not
a decision, and it now composes with (f) below. **DISCHARGED 2026-08-25: the sizing replay is the
69-instrument table in `format-8-price-derived-grid-design.md` §3, and its answer is that no constant
ships at all — the reference-derived map covers options off their live premiums (settled by yaakov
2026-08-24; design doc §8).**

**f. Price-derived ticks are PULLED INTO SCOPE. ⚠ This overrides the recommendation and is the
largest change to this document.**
The recommendation was accept-and-state. Rejected because the residual is **not hypothetical**: FNMA
is committed at `openPrice 1.12 / closePrice 1.145` in the publisher's bootstrap seed (YU16 layer,
`snapshot-prices.json:70-73`), the feed adapter has been carrying ticks since 2026-08-24, and the
collar's reference is feed-first — so **FNMA walks to ~$1.15 without anyone deciding to**, at which
point it holds the inert equity grid. Accept-and-state would mean knowingly shipping a dead guard for
a live instrument on a system whose stated direction is a sell-side OMS.

*Design constraints, derived and checked 2026-08-24 — these are what make it tractable:*

- **Derive the tick at book construction** (`bookFor()`, the cold path) from the replicated reference
  price, falling back to the category function (options / fraction-of-par), falling back to the global
  default. Static per book, so nothing about `LimitBook`'s slot arithmetic changes.
  **Corrected by the design (2026-08-25, `format-8-price-derived-grid-design.md` §1.3): the category
  must OUTRANK the reference for fraction-of-par tickers** — the bond grid is about six-decimal quote
  granularity, not width, and CORP-JPM-20310601 trades above par live, where a reference-derived tick
  would refuse legal bond quotes as off-grid. The design is the operative statement of this mechanism.
- **Determinism is already argued**: `BlpRiskState.lastPrice[]` is replicated (arrives through the
  consensus log) and snapshotted, which is exactly the property ADR-066 relies on for the band anchor.
  A tick derived from the same reference is identical on every member and on replay for the same
  reason.
- **VERIFIED: `T_PRICE` is already emitted BEFORE `T_BOOK`** in the snapshot writer (lines 63 and 68),
  so restore can re-derive the tick before reconstructing the book. **No snapshot reordering is
  needed** — the constraint this decision depends on is already satisfied.
- **The genuinely open part**: a security whose price later moves orders of magnitude outside the grid
  its tick implied. ADR-066 re-anchors *position*, never *width*; a re-tick is a re-index at a
  different scale and is materially harder than a rebase. Scope call for whoever builds — accept it
  (an instrument that moves 100x needs an epoch) or design the re-tick. **Say which; do not leave it
  discovered at the mint.**

**g. `STATUS_QUEUED` console rendering: IN SCOPE.** Without it a pre-open order reads as either missing
or indistinguishable from a live resting order to anyone watching — and this rig's audience is people
watching.

## Related

- ADR-069 (the decisions this scopes), ADR-066/-067, ADR-050/-060.
- `issues/open/the-collar-is-inert-for-every-instrument-priced-below-par.md` — to be corrected
  when touched: STRIP/BILL/note rows all bind (ADR-060, `UST-` match); options row stands; add
  FNMA as the uncovered class and the §2.4 residual statement.
- Skills applied: `a-prefix-is-not-a-category`, `size-a-configuration-bound`,
  `vacuous-pass-audit`, `prove-cluster-engine-change`.
