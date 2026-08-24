# ADR-069: The session opens where the last one closed

## Status

**Proposed** (2026-08-23). **Not implemented.** Raised by yaakov while reviewing the day's work on
rig bring-up and seeding: *session close already produces a snapshot — what does session **start**
do with it?*

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

## What this is explicitly NOT

- **Not a change to how close is computed.** ADR-051 (last trade is the mark) and the YU06 chain are
  untouched. This ADR only reads what they already write.
- **Not a claim that open should equal close.** Real markets gap overnight on news. Opening *at* the
  prior close builds a market that never gaps, which is a different fiction from the current one and
  should be chosen knowingly — see open question 2.
- **Not a durability change.** The close is already durable; the seed is already committed. Nothing
  here makes external data durable, which ADR-068's interim position forbids.
- **Not a session scheduler.** This ADR does not decide *when* a session starts or ends. It decides
  what the opening price is, given that one has started.

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

## Open questions — decide before building

1. **Does the open equal the close exactly, or gap?** Opening at the close is the simplest rule and
   the easiest to verify. A modelled overnight gap is more faithful and immediately raises "gap
   against what?", since the thing that would justify a gap (news, an external open) does not exist
   in this system. Recommend equality, named as a choice, with the gap deferred.
2. **What happens on the first session ever, and after a gap in sessions?** First-ever start has no
   prior close and must use the seed — that is rule 1 and is settled. Less settled: if the last
   published close is *weeks* old, is it still the right open, or is a stale close worse than a
   fresh seed? A staleness bound would need a stated reason, and a bound with no reason is a knob.
3. **Which instruments does this cover?** Equities and ETFs, clearly. Options are *derived* from
   their underlying rather than walked independently, so opening them from a stored close may
   conflict with the model recomputing them — needs checking against `option-quotes.js` before it is
   assumed. Treasuries are covered but immediately superseded by FRED, which is intended.
4. **Does the read-side belong to trade-processor or reference-data?** The close lives behind
   trade-processor's `/eod` surface, which is where version and DRAFT/PUBLISHED semantics already
   are — an argument for putting the previous-session resolution there. But `price-publisher`
   already talks to reference-data for instrument static, and adding a second upstream to a
   bootstrap path adds a second thing that can be down at start-up.

## Related

- [ADR-051](../../YU06-eod-price-production/system/adr-051-last-trade-is-the-mark.md) — last trade is
  the mark. Defines what a close *is*; this ADR reads the result.
- [ADR-066](adr-066-price-band-follows-the-market.md) — the band follows the reference. The
  re-anchor-at-open consequence above is the interaction between the two.
- [ADR-067](adr-067-market-data-derived-from-the-book.md) — market data derived from the book. Its
  open question 4 (does `price-publisher` keep driving the demo?) is upstream of this one: if the
  publisher is replaced, the opening-price question moves with it rather than disappearing.
- [ADR-068](adr-068-external-price-sources.md) — pluggable price sources. This ADR adds one rung to
  that hierarchy and inherits rule 1 unchanged.
