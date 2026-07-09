# Feature Specification: EOD Price Production + Overnight Batch Chain (state YU06)

**State id**: `YU06-eod-price-production`
**Parent state**: `YU05-post-trade-compliance` (chain: `014 → YU02 → YU03 → YU04 → YU05 → YU06`)
**Created**: 2026-07-08
**Status**: Slice 1 — EOD price production (produce → quality-check → override → versioned immutable
snapshot → durable `EOD_PRICES_READY` event) plus one real downstream consumer (EOD position
marks / P&L in position-service) and a Grafana chain-status panel. See
`generation/implementation-status.md` for verification evidence.
**Input**: Backlog item #1 in `issues/HANDOFF-idea-INDEX.md` (recommended YU06). The professor's
deck (deck 02 slide 18/24, deck 07 slides 22–31 & 43–46) makes the `EOD_PRICES_READY` gating event
the single most-emphasized cross-system mechanism the platform is missing.

## Why this state exists

TraderX runs continuously and has **no concept of a trading-session close, official closing
prices, or batch orchestration** — a position's mark is whatever the last tick was. Real
back-office/risk workflows are gated on a formal daily event: a controlled process assembles
official closing prices, runs data-quality checks, publishes a *versioned, immutable* snapshot, and
emits an event (`EOD_PRICES_READY`) that gates every overnight batch job (position marks, P&L, NAV,
VaR, regulatory reports). This state adds exactly that spine.

It is the connective tissue for adjacent work: YU05's settlement/reconciliation gains a natural
session boundary, and the pricing/risk teammate's overnight VaR batch (backlog #2, teammate-owned)
becomes the first *external* consumer of this state's `EOD_PRICES_READY` event. This state owns the
**data/orchestration spine** (closing prices, versioned snapshot, gate event, batch-chain SLA
monitoring); it deliberately does **not** own valuation/risk math — that is the deck's
Data-Infrastructure → Risk boundary and the teammate's track.

## The central consistency argument (do not lose this)

Deck 02 slide 24: *if Risk and Portfolio use different closing prices, VaR and NAV are
irreconcilable.* Therefore every consumer of EOD prices reads the **same** `(session_date, version)`
snapshot — **never live ticks**. The snapshot is immutable once published; a correction produces a
new *version*, never an in-place edit. This is the whole point of the state; the event exists to
tell consumers *which* version to read.

## Fail-safe principle (build in from v1)

Deck 07 slides 43–45: a missing or quality-flagged closing price must **halt and alert** the
dependent job — it must never proceed with a stale/guessed price. Concretely: a snapshot with any
unresolved `FLAGGED`/`MISSING` instrument does **not** publish and emits no event (production-side
fail-safe); and a downstream consumer that finds a held position's security missing/flagged in the
snapshot **halts that account's marking and alerts** rather than marking it wrong (consumer-side
fail-safe).

## Scope (slice 1)

### Production side — `trade-processor` (reuses the existing price feed; no new service)

1. **Session close trigger** — `POST /eod/session/close?sessionDate=YYYY-MM-DD` (admin JWT). One
   code path, two triggers: a k8s CronJob calls it on a (demo-shortened) schedule, and an operator
   can call it on demand. Idempotent per `(sessionDate)` — re-running produces a new draft version,
   never corrupts a published one.
2. **EOD price production** — assemble the closing price per instrument as the **last trade price**
   (most recent sample in the existing `PriceHistoryStore`, which already subscribes to
   price-publisher's `pricing.*` NATS feed — zero new data source, zero BLP hot-path involvement).
3. **Quality checks** — per instrument: `STALE` (last sample older than
   `eod.quality.staleness-seconds`), `SPIKE` (move vs. prior published close exceeds
   `eod.quality.max-move-pct`), `MISSING` (an instrument in the expected universe with no sample).
   Clean instruments are `OK`.
4. **Manual override (REST-only for v1)** — `POST /eod/prices/{sessionDate}/override` (admin) with
   `{security, price, reason}` supplies a corrected price for a flagged/missing instrument. Because
   the snapshot is immutable, an override **creates a new version** copying the prior one with the
   corrected instrument marked `OVERRIDDEN`.
5. **Versioned immutable snapshot** — MariaDB, keyed by `(session_date, version, security)` with a
   per-`(session_date, version)` session header carrying status (`DRAFT`/`PUBLISHED`). Append-only:
   published rows are never updated.
6. **Publish + gate event** — `POST /eod/prices/{sessionDate}/publish` (admin) publishes the latest
   version **only if** it has zero unresolved `FLAGGED`/`MISSING` instruments (else 409, fail-safe);
   a session close with an all-clean snapshot auto-publishes. Publishing sets status `PUBLISHED` and
   emits `EOD_PRICES_READY` on **durable** NATS JetStream (stream `TRADERX_EOD`, subject
   `eod.prices.ready`), reusing YU04's `JetStreamControlFeedPublisher` durability pattern so a
   late/restarted consumer still receives it.

### Consumption side — `position-service` (first real downstream job)

7. **EOD position marks / P&L** — a new **durable** JetStream consumer on `eod.prices.ready`. On the
   event it reads *only* the `(session_date, version)` snapshot named in the event, loads positions
   per account, computes `marketValue = quantity × closingPrice` per holding and the account total,
   and writes an immutable `eod_position_pnl` result keyed by `(session_date, version, account_id,
   security)`. Consumer-side fail-safe: a held security that is missing/flagged in the snapshot
   halts that account's marking and increments an alert metric. On completion it emits
   `eod.pnl.done` — the next link in the chain (which the teammate's VaR batch or YU05 NAV can later
   subscribe to).

### Observability

8. **Grafana chain-status panel** — closing-price quality-flag counts, sessions produced/published,
   the `EOD_PRICES_READY` publish rate, consumer accounts-marked/halted, and end-to-end chain
   latency (session-close → prices-ready → pnl-done) as the deck's SLA view: *a delay at any stage
   compresses all downstream processing time; ops monitors the chain.*

## Requirements

New requirement namespace `EOD` (`FR-EODxx`, `NFR-EODxx`), grouped by sub-capability. Full
per-requirement status: `requirements/functional-delta.md` / `requirements/nonfunctional-delta.md`.

- **Session + production** (`FR-EOD01`–`FR-EOD06`)
- **Quality + override** (`FR-EOD10`–`FR-EOD13`)
- **Snapshot + gate event** (`FR-EOD20`–`FR-EOD23`)
- **Consumer / batch chain** (`FR-EOD30`–`FR-EOD33`)
- **Observability** (`FR-EOD40`)

## Design baseline (ADRs)

- **ADR-026** — closing price = last trade price from the existing feed (vs. last-mid / closing
  auction); versioned-immutable snapshot as the single source of truth.
- **ADR-027** — orchestration is a lightweight NATS-JetStream event chain (`eod.prices.ready` →
  `eod.pnl.done`), not a workflow engine (Airflow/Control-M would dwarf the system).
- **ADR-028** — production in trade-processor, consumption in position-service; fail-safe
  halt-and-alert on missing/flagged prices at both ends.

## Out of scope (deliberately deferred)

- **Overnight VaR/ES batch** — teammate-owned (backlog #2). This state supplies its inputs
  (`EOD_PRICES_READY`, the versioned price snapshot, consistent position snapshot); it does not
  compute VaR.
- **Closing auction** — a real mini-auction in the BLP (matching-engine work) is a stretch goal;
  v1 uses last trade price. See ADR-026.
- **Web front-end data-ops override panel** — v1 override path is REST-only; a UI can follow.
- **Wiring YU05 NAV/recon to consume `eod.pnl.done`** — the chain link is published and documented;
  retrofitting YU05's jobs onto it is a later slice.
