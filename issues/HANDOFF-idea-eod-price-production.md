# Handoff: EOD Price Production + Overnight Batch Dependency Chain

> One of 8 idea-handoffs produced from the professor's slide deck
> (`Combined_Financial_Systems_Deck` — deck 02 slide 18, deck 07 slides 22–31 & 46). Each idea is
> a service the deck describes that TraderX does not have. Self-contained for a fresh chat.

## What this chat accomplished

- Compared the deck's overnight-workflow material against TraderX. The deck's single most
  emphasized cross-system mechanism is the **`EOD_PRICES_READY` gating event**: a formal, gated
  process assembles official closing prices, runs quality checks, publishes a *versioned,
  immutable* EOD snapshot, and emits an event that gates ALL overnight batch jobs (VaR, NAV,
  reconciliation, regulatory reports). Deck 07 slide 46 draws the full dependency DAG ending in
  a "CRO morning report" that must exist before next open.
- TraderX has **no concept of a trading session close, official closing prices, or batch
  orchestration**. The system runs continuously; marks are whatever the last tick was.
- This idea is the *connective tissue* for other planned work: YU05's NAV/reconciliation and the
  overnight-VaR idea (`HANDOFF-idea-overnight-var-batch.md`) both need an authoritative EOD
  price snapshot and a trigger event — exactly what the deck says gates them.

## Branch / repo state

- Repo: `/Users/yaakov/Desktop/Summer 26/lmax/traderX`, on `YU04-durable-control-feeds`
  (HEAD `5701f38`). Production: `YU02-lmax-kubernetes-blp-ha`. No code changes this session.

## Goal for next chat

Design and scaffold a new YUxx state adding:

1. **Trading-session semantics**: a scheduled market close (k8s CronJob or a session service)
   that triggers the EOD process. (A demo "day" can be shortened — e.g. hourly closes — so the
   workflow is observable.)
2. **EOD price production service**: assemble closing prices per instrument from the last
   trade/tick (later: a real closing auction is a stretch goal), run quality checks (staleness /
   spike / missing-instrument — reuse or coordinate with the market-data-quality idea), support a
   manual override path for flagged prices (deck 02 slide 18: "data operations team reviews and
   overrides"), then write a **versioned, immutable snapshot** (MariaDB table keyed by
   `(session_date, version)` is fine).
3. **`EOD_PRICES_READY` event** on NATS JetStream (durable — late/restarted consumers must see
   it; YU04's JetStream patterns apply).
4. **A minimal batch orchestrator**: a dependency-ordered chain of jobs consuming the event —
   start with one or two real consumers (position marking / EOD P&L per account; YU05's NAV +
   recon and the VaR batch slot in later). Track per-job SLA + status in Grafana (deck: "a delay
   at any stage compresses all downstream processing time; ops monitors the chain").

## Key files

| Path | Why it matters |
|---|---|
| `position-service/`, `account-service/` | First consumers: EOD position marks / P&L per account |
| `database/` | Home of the versioned EOD price snapshot table |
| `HANDOFF-idea-overnight-var-batch.md` | Downstream consumer #2 of `EOD_PRICES_READY` |
| `HANDOFF-combined-yu05-state.md` | YU05 NAV/recon — downstream consumer #3 |
| `HANDOFF-durable-control-feeds.md` | YU04 JetStream durability patterns to reuse |

## Architecture / context the next chat needs

- Deck rationale for versioned-immutable + single source of truth (deck 02 slide 24): "if Risk
  and Portfolio use different closing prices, VaR and NAV are irreconcilable." Every consumer
  reads the same `(session_date, version)` snapshot — never live ticks.
- Fail-safe principle (deck 07 slides 43–45): missing closing price for an instrument → the
  dependent job **halts and alerts**, never proceeds with a stale price. Build that in from v1.
- Orchestration altitude: the deck names Airflow/Control-M, but for TraderX a small
  NATS-event-driven chain (each job publishes `<JOB>_DONE`, next job subscribes) or plain k8s
  Jobs with an ordering controller is the right size. Do NOT bring in Airflow — it would dwarf
  the system it orchestrates.
- Shortened demo sessions (e.g. close every hour, or on-demand via admin endpoint) make this
  demonstrable in a meeting — recommend on-demand trigger + daily schedule both.
- YU-state conventions: spec pack under `specs/YUxx-<name>/`, same-named branch, parent lineage,
  **commit but never push**; staging CI/CD only with explicit user approval; doc-sync per
  working conventions.

## Decisions already made (don't re-litigate)

- Versioned immutable snapshot in the DB + durable JetStream event — not "read latest price at
  job start" (the deck's consistency argument is the whole point of the idea).
- Lightweight event-chained orchestration, not Airflow.
- Fail-safe: halt-and-alert on missing/flagged prices, with a manual override path.

## Open questions / known issues

- Session calendar: fixed daily close, shortened demo cycle, or admin-triggered? (Ask user;
  recommend supporting both a schedule and an on-demand trigger.)
- Where does the "data operations override" UI live — web front end panel vs REST-only for v1?
- Closing price definition v1: last trade vs last mid vs mini closing auction in the BLP
  (auction = real matching-engine work; keep as stretch).
- Coordinate with YU05 scaffolding so NAV/recon consumes this event rather than inventing its
  own trigger — if YU05 lands first, this state should retrofit it.

## Suggested first steps for next chat

1. Read this doc + `HANDOFF-combined-yu05-state.md` (NAV/recon consumer) +
   `HANDOFF-idea-overnight-var-batch.md` (VaR consumer).
2. Confirm state id/name with the user (e.g. `YUxx-eod-batch-chain`).
3. Write research.md deciding the orchestration mechanism (NATS event chain vs k8s Jobs) and the
   closing-price definition.
4. Implement: snapshot table → EOD service → JetStream event → one consumer (EOD P&L) →
   Grafana chain-status panel.
