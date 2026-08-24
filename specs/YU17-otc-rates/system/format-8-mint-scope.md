# Scoping: the SNAPSHOT_FORMAT 7 → 8 mint

**Status: DRAFT — scoping only, nothing built.** Written 2026-08-24 against the YU17 tip, after
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
  global grid *permanently for the epoch* (the stored tick freezes the accident). Fixtures do
  exactly this today.
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

## 7. Decisions needed from yaakov before build

a. **Fresh-epoch default phase**: recommend **OPEN** (every existing proof and fixture assumes a
   trading book; CLOSED-until-commanded is the "real OMS" posture but breaks the entire suite on
   day one — opt into it later by issuing the command in bring-up).
b. **PRE_OPEN → CLOSED with a non-empty queue**: recommend the transition *cancels the queue*
   (each order CANCELED with a session reason — needs one more appended RiskReason) rather than
   refusing the transition; a halt that can be blocked by pending orders is not a halt.
c. **Cancels during CLOSED**: recommend reject MARKET_CLOSED (v1; venues' GTC-cancel-overnight is
   an OMS courtesy this tier doesn't owe yet). PRE_OPEN allows cancel (queued and resting),
   rejects replace.
d. **Do swap/swaption bookings halt with the session?** Recommend **no** — the halt is the
   venue's book; OTC bookings are bilateral desk business and never touch the book. But it is a
   product question, not an engineering one.
e. **OPTION_BOOK_TICK_PX** — confirm 100 after the §2.3 measurement, or direct otherwise.
f. **FNMA-class residual** — accept-and-state (recommended), or pull §2.2 into scope now.
g. **STATUS_QUEUED rendering** — console shows queued orders as their own state; confirm the
   small UI touch is in scope for the mint window.

## Related

- ADR-069 (the decisions this scopes), ADR-066/-067, ADR-050/-060.
- `issues/open/the-collar-is-inert-for-every-instrument-priced-below-par.md` — to be corrected
  when touched: STRIP/BILL/note rows all bind (ADR-060, `UST-` match); options row stands; add
  FNMA as the uncovered class and the §2.4 residual statement.
- Skills applied: `a-prefix-is-not-a-category`, `size-a-configuration-bound`,
  `vacuous-pass-audit`, `prove-cluster-engine-change`.
