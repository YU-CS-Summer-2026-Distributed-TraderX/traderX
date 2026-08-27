# ADR-069: The session opens where the last one closed

## Status

**Proposed** (2026-08-23). **Not implemented.** Raised by yaakov while reviewing the day's work on
rig bring-up and seeding: *session close already produces a snapshot — what does session **start**
do with it?*

Two decisions are **taken** (yaakov, 2026-08-23) and recorded here rather than left open: the
session **halts in consensus**, not at the gateway (decision 5), and the **feed keeps running
through the halt** (decision 6), which is what makes the overnight gap real rather than fabricated.
Everything else remains proposed.

Sits underneath [ADR-068](adr-068-external-price-sources.md), which decides **where an external
reference comes from**. This ADR is the question beside it: **where the opening price comes from
when there is no external source**, which today is every instrument except Treasuries.

## Context

### Close is real, durable, versioned and quality-checked. Nothing opens from it.

The EOD chain (YU06) persists a genuine close. Measured on the rig 2026-08-23:

| Table | What it holds | Rows |
|---|---|---|
| `eod_price_snapshot` | `(session_date, version, security)` → `closing_price DECIMAL(18,6)`, `quality`, `source_tick_millis`, `override_reason` | **3085** |
| `eod_price_session` | `(session_date, version)` → `status` DRAFT/PUBLISHED, `instrument_count`, `flagged_count` | — |

It is read by three consumers — `RiskExtractMain` (the regulatory cut), the trade-processor's
`EodPriceSnapshotRepository`, and position-service's `EodPriceSnapshotReader` for P&L. It has an HTTP
surface: `GET /eod/prices/{sessionDate}`, plus versioned reads, override and publish.

`price-publisher` reads **none of it**. It has no EOD read path at all. `PRICE_BOOTSTRAP_MODE`
defaults to `snapshot`, which means `price-publisher/data/snapshot-prices.json` — a **static file
committed during the YU16 work** holding a hardcoded `openPrice`/`closePrice` per ticker. So the
lifecycle is:

```
close  ->  persist (versioned, flagged, overridable)  ->  [ GAP ]  ->  static JSON  ->  open
```

The gap is the whole of this ADR. Everything on the left of it already works.

### What the gap costs, measured

Latest published close against the seed the next bring-up would actually use:

| security | EOD close | static seed | drift |
|---|---|---|---|
| AAPL | 246.6360 | 241.8000 | +2.00% |
| AMZN | 190.4160 | 185.5000 | +2.65% |
| META | 482.4230 | 503.2000 | **-4.13%** |
| NVDA | 892.6780 | 910.8000 | -1.99% |
| MSFT | 388.3210 | 388.5000 | -0.05% |

**The magnitude is not the point.** A few percent is unremarkable; it is also unbounded, because the
seed is frozen and the closes are not. The point is the *relationship*: `open_today` has **no causal
connection whatsoever** to `close_yesterday`.

### The consequence that actually matters

This is not a realism complaint. It is a **measurability** defect.

Because the open is a constant unrelated to the prior close, the overnight gap is not merely
inaccurate — it is **undefined**. So is any daily return that spans a restart, any gap-risk figure,
and any P&L attribution across a session boundary. The market teleports on every bring-up, and no
downstream calculation can tell that from a real overnight move.

For a system whose stated direction is a **sell-side OMS — correctness, risk, compliance,
settlement** — a price series with a discontinuity of unknown size at every restart is a defect in
the thing being built, not a cosmetic gap in the demo.

### There is no session boundary in this system, only "cut a close now"

`EodController.close()` takes an optional `sessionDate` and otherwise uses `LocalDate.now()`. It is
an on-demand HTTP call, so a "session" is whatever a caller decided to cut. Observed:

| session_date | versions | published |
|---|---|---|
| 2026-08-24 | 2 | 1 |
| 2026-08-23 | **50** | 30 |
| 2026-08-20 | 6 | 6 |
| 2026-08-18 | 33 | 21 |

Fifty versions for one date, because every proof-suite run cuts another. That is fine for a *cut*
primitive and fatal for a naive definition of "yesterday's close": there is no single answer unless
one is chosen deliberately. **Defining "the previous close" is a prerequisite of this ADR, not a
detail of it.**

### There is no halt MECHANISM, and the continuous trading on the rig is a testing pattern

Measured 2026-08-23, with a correction attached — the two must not be conflated.

**Verified about the code:** nothing in this system *can* halt trading. There is no market-open or
market-closed state, no trading calendar, no halt primitive. Every `isOpen` in the tree is *order*
state (unfilled), never market state. `price-publisher` has no knowledge that a close occurred.

**Observed on the rig:** today's close was cut at `00:05:12`, **24 trades executed after it** (the
most recent at `00:07:32`), and the feed never paused.

**What that observation does NOT establish**, and an earlier draft of this ADR wrongly inferred:
that the system is *designed* to run continuously. It is not — yaakov is trading past EOD and
re-publishing **because he is testing**, deliberately not following OMS procedure. A real deployment
**halts at EOD and does not continue trading**. So the absence of a halt is a **missing mechanism**,
not a design position, and "what should the open be?" is a real question rather than a malformed one.

The distinction matters for what this ADR is. Today's measured discontinuity is a **restart
artifact** — the publisher rewinding to `snapshot-prices.json` — and that is worth fixing on its own.
But once a halt exists, a *second* and more interesting discontinuity appears, and it is the one a
real OMS has: the market moved while the book could not respond.

### The venue halts; the market does not. That is where the gap comes from.

A real overnight gap is not created at the open. It is the visible residue of price discovery that
continued **somewhere the halted venue was not**: futures, other time zones, ADRs, news repricing a
closed book. The exchange stopped; the market did not.

This system already has that separation, and it is [ADR-067](adr-067-market-data-derived-from-the-book.md)'s
own architecture: `price-publisher` is the **reference/feed**, the book is the **venue**, and they
are completely decoupled — the publisher holds no reference to the gateway, the cluster or the book.

So the feed can keep running while the book is halted, and **that is the gap**. It is endogenous,
real within the system, and needs no external source and no fabricated draw.

The difference from a fabricated gap is not cosmetic. With a draw stamped on the open, nobody can say
what the price *did* overnight — there is a number and nothing behind it. With the feed running
through the halt, the overnight path is reconstructable tick by tick, from the same feed, carrying
the same `source` and `simulated` provenance as every daytime tick. **It is auditable**, which is the
property a drawn gap can never have, and auditability is the whole point of the system this is part of.

### An external overnight source for single-name equities does not exist on our terms

Checked 2026-08-23 against the live FRED API, using this project's own obligation-2 copyright check:

| series | title | our check |
|---|---|---|
| `SP500` | S&P 500 | **copyright-marked → REFUSED** |
| `NASDAQ100` | NASDAQ-100 | **copyright-marked → REFUSED** |
| `DJIA` | Dow Jones Industrial Average | **copyright-marked → REFUSED** |
| `VIXCLS` | CBOE Volatility Index: VIX | **copyright-marked → REFUSED** |
| `NASDAQCOM` | NASDAQ Composite | **copyright-marked → REFUSED** |
| `DTWEXBGS` | Nominal Broad U.S. Dollar Index | ok (the Fed produces it) |

The pattern is clean and it generalises: **series the Federal Reserve produces are usable; series a
private index provider licenses to FRED are not.** S&P, Nasdaq, Dow Jones and CBOE all fall in the
second group, which is the entire equity-index and equity-volatility surface.

That closes the textbook route — decompose an overnight move into a systematic component inherited
from a real index observation plus an idiosyncratic residual. The systematic component is exactly
what we cannot license. Combined with ADR-068's findings (Pyth forbids it; Massive's free tier is
personal/non-commercial), **there is no free, redistributable overnight source for single-name US
equities.**

The halt-the-book approach is what makes that survivable: it produces a gap **without** an external
source. An external source is needed only for a *true* gap, and today only Treasuries have one — and
already use it, since FRED publishes H.15 daily.

## Decision (proposed)

**A session opens where the last session closed**, when a prior close exists, and the bootstrap
source is reported rather than assumed.

**1. Extend the ADR-068 hierarchy by one rung.** Per instrument class, first source that answers
wins:

```
external source (FRED, for rates)  >  prior published close  >  static seed
```

The static seed stays the floor, so ADR-068 rule 1 survives intact: **synthetic stays the default
and stays sufficient**, and a rig with no database and no network still opens.

This ordering is deliberate for Treasuries. They seed from the prior close and are then *superseded*
by the FRED poll within one interval. That is correct: the close is the best available answer until
a live curve arrives, and a live curve is better than any close.

**2. "The previous close" means exactly one thing**: the **latest `PUBLISHED` version of the most
recent `session_date` strictly earlier than the opening date**. Never a DRAFT — DRAFTs carry
known-bad marks (today's DRAFT reported `flagged_count=1`; the PUBLISHED version that superseded it
reported `0`). Never a same-day cut, or a proof run that cut a close at 23:54 would become the open
for the session already in progress.

**3. Read it over HTTP, not from the database.** `price-publisher` owns no persistence today and
should not acquire a schema dependency to gain a bootstrap. It already fetches over the network in
`fred-curve.js`; this is the same shape. `GET /eod/prices/{sessionDate}` exists but requires a date
the publisher does not know, so the one genuinely new piece of surface is a *previous-session* read
that resolves rule 2 server-side, where the version and status semantics already live.

**4. Report which source won, per instrument class.** `/health` already carries `priceSource` for
FRED. Opening source joins it. See the trap below — this is not optional polish.

**5. The session halts, and it halts IN CONSENSUS** (yaakov, 2026-08-23). Session state becomes a
sequenced transition — a command enters the log, every member applies `OPEN`/`CLOSED` at the same
position, and a halted book rejects order ingress.

The alternative was enforcing the halt at the gateway, which is far cheaper: no deterministic-core
change, no epoch mint. It was rejected on a single argument. **A halt that a restart can bypass is
not a halt.** A gateway-level gate lives in a process that is restarted routinely, is not part of the
replicated state machine, and is invisible to the audit log — so a restarted gateway during a halt
admits orders the members accept, and nothing in the record says the market was supposed to be
closed. For a system claiming OMS correctness that is not a shortcut, it is a defect with a
scheduled arrival date.

**6. The feed does NOT halt.** Only the venue does. This is what makes the gap real (above) rather
than fabricated, and it costs nothing to implement, because `price-publisher` already has no
knowledge of the book's existence.

The mechanism is available because the feed does not send **orders**. `FeedAdapterMain.offerTick()`
emits `InputEvent.TYPE_PRICE_TICK`, a distinct event type from an order. So a halt that gates
*order* events can let price ticks through untouched, `BlpRiskState.lastPrice[]` keeps advancing,
and the ADR-066 band re-anchors across the halt with no special case.

**7. Resting orders are re-validated at the open, and what is stale is cancelled** (yaakov,
2026-08-23). Two checks, and they answer different questions — running only one of them is the
trap:

| check | question | yardstick | status |
|---|---|---|---|
| **grid** | can this order still be *represented*? | the re-anchored band | **already exists** — the re-anchor forces a cancel for orders with no slot |
| **staleness** | would this order be *picked off*? | the opening reference | **new**, and it is the one this decision is about |

**The band cannot serve as the staleness yardstick, and assuming it could was the original error in
this ADR.** It is a fixed absolute window — `BOOK_LEVELS` (1<<17) x `BOOK_TICK_PX` ($0.001) = a
$131.07 span, so ±$65.54 regardless of what the instrument costs. Its *relative* width is therefore
an accident of price level:

| instrument | price | band as % of price |
|---|---|---|
| `UST-STRIP-20560515` | 0.2156 | ±30,400% |
| `UST-20280630` | 0.9993 | ±6,558% |
| `AAPL` | 246.64 | ±26.6% |
| `META` | 482.42 | ±13.6% |
| `NVDA` | 892.68 | ±7.3% |

An overnight gap is 0.1–5%. **A band re-validation would cancel nothing, for any instrument, on any
realistic gap** — a check returning one answer for every input, which is the vacuous shape this
project keeps paying for, and it would have been discovered as a proof that never goes green. Same
root cause as `issues/open/the-collar-is-inert-for-every-instrument-priced-below-par.md`: an
absolute window standing in for a relative one.

**The staleness rule, stated precisely: cancel any resting order that is *through* the opening
reference** — an ask below the opening mark, a bid above it. That is exactly the set that would be
swept at a stale price, it needs **no threshold to tune**, and unlike a percentage band it
discriminates identically at $0.21 and $892. Orders on the correct side of the mark are ordinary
resting liquidity and survive; they were never the harm.

Rejected alternatives, and why:

- **Cancel everything at the close.** Safe and trivial, but discards the GTC-survives-overnight
  property a real OMS has, which is part of what this system is meant to demonstrate.
- **Carry them and let the engine sweep.** Cheapest, and it hands free money to whoever arrives
  first at the open. This is the defect the decision exists to prevent.
- **An opening auction.** Correct, and a much larger change — single-price discovery, order
  accumulation during a pre-open phase, an uncrossing algorithm. Worth revisiting if the demo ever
  needs to *show* price discovery rather than just survive a halt.

**Note what this is not**: a gap does **not** cross the book. Resting orders keep their prices, so a
price move cannot put a bid above an ask. The harm is adverse selection against stale liquidity, not
an uncrossed book, and the rule above is aimed at exactly that.

## What this is explicitly NOT

- **Not a change to how close is computed.** ADR-051 (last trade is the mark) and the YU06 chain are
  untouched. This ADR only reads what they already write.
- **Not a fabricated gap.** No draw, no distribution, no `open = close * (1 + noise)`. The gap is
  whatever the feed walked during the halt — a real path, reconstructable tick by tick. A fabricated
  gap is indistinguishable downstream from an observed one, which would make every gap-risk figure a
  measurement of the RNG's variance reported as risk.
- **Not a durability change.** The close is already durable; the seed is already committed. Nothing
  here makes external data durable, which ADR-068's interim position forbids.
- **Not a session scheduler.** The session halts *in consensus* (decision 5), but this ADR does not
  decide **when** — no calendar, no clock, no exchange hours. What commands the transition, and on
  what schedule, is open question 4.
- **Not an opening auction.** Decision 5 halts and resumes a continuous book. It does **not**
  introduce single-price opening-auction mechanics, which is a much larger change — and open
  question 5 is the reason someone will ask for one.

## Consequences

- Overnight gap, daily return and cross-session P&L become **defined quantities**. They are
  currently not wrong so much as meaningless, and nothing downstream can detect that.
- Prices stop rewinding to a 2026-vintage constant on every bring-up, so a long-running rig
  accumulates a continuous series instead of a sawtooth with a discontinuity at each restart.
- **The collar acquires a new dependency.** ADR-066 anchors the band on `BlpRiskState.lastPrice[]`.
  On a fresh epoch the band re-anchors; if the open now jumps to the prior close, the anchor must be
  taken at the **open**, not at the seed, or the first orders of the session are refused for being
  outside a band centred on a stale number. Today's drift (≤4.13%) is comfortably inside the
  ±$65.54 window, but that is **luck, not design** — the seed is frozen and the closes are not, so
  the gap grows without bound.
- One more thing can fail at start-up, on a path that currently cannot. Rule 1's fallback is what
  keeps that from being a liveness risk, and it is why the seed is retained rather than deleted.
- Proof fixtures that assume a known opening price will see a different one on a rig that has ever
  closed a session. Any proof pinning an opening *level* needs the same treatment
  `yu16-treasury-pricing` just received: assert a property, not a number a simulation chose.

### Consequences specific to halting in consensus (decision 5)

- **This is a deterministic-core change, with everything that implies.** Session state enters the
  replicated state machine, so it **cannot be rolled gradually** — a mixed-version window diverges
  the members permanently. Safe roll is the standing one: scale to zero, wipe the PVCs, mint a fresh
  epoch. Budget an epoch mint, not a rolling update.
- **The snapshot format must carry session state, or recovery reopens a closed market.** The
  operative `SNAPSHOT_FORMAT` is **7** (YU17); this makes it **8**. A member that snapshots while
  `CLOSED` and recovers without the flag comes back `OPEN` and starts accepting orders into a halted
  market — a silent correctness failure of exactly the kind
  `traderx-snapshot-completeness-audit` exists to catch. The format bump is not bookkeeping; it is
  the mechanism that makes the halt survive a restart, which was the entire argument for choosing
  consensus over the gateway.
- **A halt is now auditable.** The transition is in the log at a known position, so "was the market
  open when this order arrived?" is answerable from the record rather than from a gateway's memory.
  That is the property the gateway alternative could not provide at any price.
- **Rejects need a reason that is true.** An order refused because the market is closed must say so,
  and must not be conflated with a risk refusal or a band refusal — the audit surface already cannot
  say *why* an order was refused (`issues/open/the-audit-surface-records-that-an-order-was-refused-not-why.md`),
  and adding a third refusal reason to an undifferentiated counter makes that issue worse rather
  than merely unchanged.
- **The open now cancels orders, so the open is an event with effects.** Re-validation at the open
  emits cancels into the log, which means the opening transition is not a flag flip — it produces
  order-lifecycle events that the read model, the audit surface and any client watching an order
  will see. A cancel issued by the session transition must be distinguishable from a user cancel and
  from a collar cancel, or the audit surface inherits a third indistinguishable reason (see above).
- **The collar keeps re-anchoring through the halt**, because the feed keeps ticking and ADR-066
  follows `lastPrice[]`. That is the desired behaviour — the band tracks the overnight move so the
  session opens with a band centred where the market actually is, rather than where it was at the
  close. It also means the band moves while no order can possibly test it, so any proof of
  re-anchoring must not assume a trade is available to trigger it.

## The trap to build in from the first commit

**A failed close-read is indistinguishable from a successful one.** If the HTTP call fails, times
out, or resolves to an empty session, the publisher falls back to the seed and every price is still
completely plausible. There is no wrong number to notice, no error a human sees, and the feature is
silently absent — for weeks, if nobody thinks to check.

This is the exact shape `vacuous-pass-audit` exists for, and this project has now paid for it four
times in a week (the feed adapter's swallowed parse, the STP freshness guard, the outbox republish
that reads identically whether or not it ran, and the Treasury proof that could never pass). The
countermeasure is already built and proven for FRED: **`/health` names the source that actually won,
per instrument class**, so "did the session open from the prior close?" is answerable in one request
without reading logs or this file.

Corollary for whoever implements it: the **absence** of a continuity signal must be loud. An opening
price that silently came from the seed looks exactly like one that came from a close.

## Answered while drafting

**Does the open equal the close exactly, or gap? — ANSWERED: it gaps, and the gap is endogenous.**
An earlier draft closed this as malformed, on the inference that the system has no closed period.
That inference was wrong: the continuous trading measured on the rig is yaakov **testing**, not a
design position, and a real deployment halts at EOD. With decisions 5 and 6 the answer falls out —
the venue halts, the feed does not, and the open is wherever the feed walked to. Reconstructable
tick by tick, carrying the same provenance as any daytime tick, and requiring no external source.

The general rule is worth keeping, because it is what rules out the cheap alternative: **a gap
cannot be *detected*; it is *inherited* from something that kept moving while your venue could not.**
Three ways to obtain one, and only two are honest:

- **Inherit it externally.** Close at T, poll at T+1, and the difference already contains whatever
  the world did. **Live today for Treasuries**, since FRED publishes H.15 daily. Unavailable for
  everything else — see the licensing table above, which closes it for equities specifically.
- **Inherit it internally** — the feed runs through the halt. This is decision 6, it is free, and it
  is the only route available for instruments with no licensable source.
- **Fabricate it** (`open = close * (1 + draw)`). One line, and a correctness regression: a
  fabricated gap is **indistinguishable downstream from a real one**, so every gap-risk figure would
  measure the RNG's variance and report it as risk. Rejected.

**Which instruments does this cover? — ANSWERED for options.** Verified in `main.js`: an option is
"*derived: re-price from the underlying's current tick. No band, no drift of its own.*" So options
**inherit any underlying gap automatically, through the model they already use** — they need no
opening price of their own and must not be given one from a stored close, which would fight the
re-price. Equities and ETFs are covered by the hierarchy. Treasuries are covered and then superseded
by FRED, as intended.

## Decisions — settled 2026-08-24 (yaakov)

All four were "decide before building". They are decided; the reasoning is kept because the shape of
the argument is what a later reader needs, not the verdict alone.

**1. No staleness bound on an old close.** The last published close is the open regardless of age.
The open carries the close's *date*, so staleness is visible to anyone reading it, but nothing
refuses to use it. This ADR argued that a bound would need a stated reason and that a bound with no
reason is a knob — that argument holds, and no reason exists yet. If a stale open ever causes harm,
that incident is the evidence from which to size a bound; inventing one first would be picking a
number to feel safe.

**2. The read-side belongs to `trade-processor`.** The close already lives behind its `/eod` surface,
together with the version and DRAFT/PUBLISHED semantics the resolution depends on, so the logic sits
next to the state it reads. Decisive against `reference-data`: it would add a second upstream to a
**bootstrap** path — a second thing that can be down at start-up, on the one path with no fallback.

**3. Session transition is a SEQUENCED COMMAND, issued by a human by default.** A clock may be an
opt-in *producer* of that command, off on the demo rig. The ADR's own note settles the default: a rig
that halts itself overnight looks broken every morning.

**4. A halted book's behaviour is PHASE-GATED — `PRE_OPEN` queues, `CLOSED` rejects.** Real venues
accept pre-open orders for the auction, and this now does too, but only inside a phase the log has
entered.

### The constraint that shapes 3 and 4 together, and it is not negotiable

The original phrasing of 4 was "queue if after 6:30am EST, reject otherwise". **A wall-clock
comparison must never be evaluated per member.** `MatchingEngine` is clock-free today — verified
2026-08-24, no `System.currentTimeMillis`, no `Instant.now` anywhere in the cluster apply path — and
that is load-bearing, not incidental. Two members evaluating `now() > 06:30` against their own clocks
will disagree for one order at the boundary and **diverge permanently**; a deterministic-core
divergence cannot be rolled back gradually (`CLAUDE.md`), it is repaired only by a wipe and a fresh
epoch.

So the engine holds a phase enum — `CLOSED` / `PRE_OPEN` / `OPEN` — and **never knows what time it
is**. The 6:30 ET boundary is expressed as *when the `PRE_OPEN` command is issued*, upstream and
outside the core. Every member applies that command at the same log position and therefore agrees by
construction.

This is why 3 and 4 are one mechanism, not two: a single sequenced command stream drives the phase,
and "human or clock" is only a question about what *produces* those commands. A scheduler and an
operator emit the identical command; the core cannot tell them apart and does not need to.

### Epoch consequence — one mint, and what actually rides it

The queue is **sequenced state**: it must survive snapshot and failover, so it bumps
`SNAPSHOT_FORMAT` (7 → 8 on this branch). Yaakov's call is **one epoch mint, not two** — one PVC
wipe, one proof run, one mixed-version window to avoid. The cost is that everything riding it must be
right before minting.

**What rides this epoch, corrected 2026-08-24 after ADR-067 Q1 was re-decided:**

| change | core? | why it is here |
|---|---|---|
| Pre-open queue + phase machine (this ADR, 3 and 4) | **yes** | queued orders are sequenced state |
| Band **width** fix — the below-par inertness | **yes** | `LimitBook`'s slot arithmetic and layout |
| Collar **reference** re-point | **NO — dropped** | ADR-067 Q1 now splits: the collar keeps ADR-066's exogenous reference, so nothing changes here |
| Market data published from the book | no | egress-side, beside consensus, `/bbo` already ships |

The band-width fix (`issues/open/the-collar-is-inert-for-every-instrument-priced-below-par.md`) was
folded in by decision rather than drifting in: it is a deterministic-core edit and would otherwise
force a second wipe weeks later.

**Its blast radius is SMALLER than first written — corrected 2026-08-24 by measurement.** The first
version of this section said the width fix re-architects `LimitBook`. It does not: **the per-security
tick mechanism already exists and works**, shipped in YU16 under ADR-060. `LimitBook(levels,
tickTicks)` takes tick size per instance, `MatchingEngine.bookTickPxBySecurity[]` /
`overrideBookTickPx()` install it, and `MatchingEngineClusteredService` derives it both at symbol
registration and on snapshot restore, so it is already deterministic across members and replay.

Band width is `levels x tick`, so tick size already *is* the width lever:

| grid | tick | window | applies to |
|---|---|---|---|
| global | 1000 (=$0.001) | $131.07, ±$65.54 | equities, **listed options**, anything unmatched |
| bond (ADR-060) | 1 (=$0.000001) | $0.13, ±$0.0655 | tickers matching `UST-` or `CORP-` |

**The entire defect is the derivation's gate**, a two-entry prefix allowlist:

```java
private static final String[] FRACTION_OF_PAR_TICKER_PREFIXES = { "UST-", "CORP-" };
private static long derivedBookTickPxFor(String ticker) {
    return isFractionOfParTicker(ticker) ? BOND_BOOK_TICK_PX : 0L;  // 0 = fall back to the equity grid
}
```

A `startsWith` allowlist standing in for the category *"priced as a fraction of par, or in single
digits"* — the `a-prefix-is-not-a-category` pattern exactly. Every class outside those two prefixes
silently inherits the equity grid.

**This also corrects the filed issue**
(`issues/open/the-collar-is-inert-for-every-instrument-priced-below-par.md`): its table says the
collar does not bind for a Treasury note. It does — `UST-` matches, so a note at 0.99 gets ±$0.0655,
about ±6.6%. Every row in that table was computed from the global constant without accounting for
ADR-060, which shipped on the same branch. The row that is unambiguously right is **listed options**,
which match no prefix and get the equity grid. The strip row depends on what strip tickers actually
look like and is unverified.

What still makes this core, and therefore epoch work: changing a live security's tick changes its book
geometry and every resting order's slot. A fresh epoch starts with empty books, which is what makes
the change free here and expensive later.

**The proof gap is the real cost, not the code.** Every existing collar proof is equity-priced
(`yu03-risk-proof`, `yu10-fix-session`, `yu13-cancel-ingress`, `yu13-stp-and-replace`) — the classes
where the collar does not bind are exactly the ones no proof covers, which is why this survived. An
inert guard accepts everything, which is indistinguishable from a guard that works. New proofs must
exist before the mint, and must be shown to FAIL on the current build.

## Related

- [ADR-051](../../YU13-limit-order-book/system/adr-051-last-trade-price-output.md) — last trade is
  the mark. Defines what a close *is*; this ADR reads the result.
- [ADR-066](adr-066-price-band-follows-the-market.md) — the band follows the reference. The
  re-anchor-at-open consequence above is the interaction between the two.
- [ADR-067](adr-067-market-data-derived-from-the-book.md) — market data derived from the book. Its
  open question 4 (does `price-publisher` keep driving the demo?) is upstream of this one: if the
  publisher is replaced, the opening-price question moves with it rather than disappearing.
- [ADR-068](adr-068-external-price-sources.md) — pluggable price sources. This ADR adds one rung to
  that hierarchy and inherits rule 1 unchanged.
- [ADR-070](adr-070-the-tape-is-the-reference.md) — a replayed tape supplies the **real** opens,
  closes and overnight gaps this ADR's rule is written against, making it checkable rather than
  merely asserted.
