# Handoff: Overnight VaR / Expected Shortfall Batch on a Compute Grid

> One of 8 idea-handoffs produced from the professor's slide deck
> (`Combined_Financial_Systems_Deck` — deck 05, deck 01 slides 53–57, deck 07 workflow 6). Each
> idea is a service the deck describes that TraderX does not have. Self-contained for a fresh chat.

## What this chat accomplished

- Compared the deck's Risk Management material against TraderX. Deck 05 slide 13 describes
  **two architecture paths**: (1) a real-time event-driven stream processor with approximate,
  pre-computed sensitivities — *TraderX has this half* (the YU03 in-memory risk gateway) — and
  (2) an **overnight massively parallel batch** that snapshots the full portfolio and reprices
  it under thousands of scenarios to produce VaR/ES, stress results, and capital numbers —
  *TraderX has none of this half*.
- Noted the deck's repeated emphasis (deck 01 slide 53: overnight risk is "70% of the firm's
  cloud CPU budget"; deck 07 slide 26: reviewed by the CRO daily) — for a course/internship demo
  about real-world realism, having both halves of the risk architecture is a strong story.
- Dependencies mapped: consumes the `EOD_PRICES_READY` event + versioned EOD snapshot
  (`HANDOFF-idea-eod-price-production.md`) and historical returns
  (`HANDOFF-idea-historical-tick-store-backtesting.md`, or self-accumulated EOD history —
  workable with zero external data after ~30 demo sessions, faster with TAQ).

## Branch / repo state

- Repo: `/Users/yaakov/Desktop/Summer 26/lmax/traderX`, on `YU04-durable-control-feeds`
  (HEAD `5701f38`). Production: `YU02-lmax-kubernetes-blp-ha`. No code changes this session.

## Goal for next chat

Design and scaffold a new YUxx state adding an overnight risk batch:

1. **Historical-simulation VaR/ES** (the simplest defensible method for an equities-only book):
   take each account's EOD positions, apply N historical daily return scenarios per instrument,
   full-reval the portfolio per scenario, sort the P&L vector → VaR = 99th-percentile loss,
   ES = mean loss beyond it. No options/Greeks in TraderX, so no model library needed — this is
   arithmetic at scale, which is exactly the deck's point about the *distributed-systems* shape
   of the problem.
2. **Work-stealing compute grid** (deck 01 slide 65): (account × scenario-batch) tasks that are
   independent and idempotent, fanned out over a k8s Job with parallelism (or a NATS work queue
   consumed by worker pods), re-queued on worker failure (deck 07 fail-safe table: "task
   re-queued to another worker; idempotent tasks guarantee same result").
3. **Stress scenarios**: a handful of named hypothetical shocks ("2008 replay", "rates +200bp /
   equities −20%" — deck 01 slide 56) run through the same grid.
4. **Morning risk report**: per-account and firm-wide VaR/ES/stress results persisted + a
   Grafana (or UI) "CRO morning report" panel, produced before the next demo session opens
   (deck 07 slide 46's dependency chain terminus).
5. **Feedback loop (stretch)**: nightly VaR informs the YU03 gateway's limits — the deck's
   Risk → Trading limit-state flow, closing the loop between the two halves.

## Key files

| Path | Why it matters |
|---|---|
| `position-service/` / MariaDB read model | Source of EOD positions per account |
| `HANDOFF-idea-eod-price-production.md` | The gating event + EOD price snapshot this consumes |
| `HANDOFF-idea-historical-tick-store-backtesting.md` | Historical returns source (optional accelerator) |
| `specs/YU03-in-memory-risk-gateway/` | The intraday half; its limit config is the stretch-goal consumer |
| `cluster-addons/` | Where the batch Job/worker manifests land; GKE autoscaling notes in `CLOUD-ARCHITECTURE.md` |

## Architecture / context the next chat needs

- Positions must come from a **consistent snapshot** — the deck's whole consistency argument.
  Cleanest source: the BLP journal/snapshot at session close, or the MariaDB read model *after*
  the projector has drained for the session; research.md should pick one and justify it (the
  recon logic planned for YU05 compares these two — this state should reuse whichever is deemed
  authoritative).
- Grid sizing reality check: TraderX has a handful of accounts and ~dozens of instruments —
  1,000 scenarios × all positions is trivially small. The realism is in the *architecture*
  (idempotent task fan-out, failure re-queue, dependency gating, SLA monitoring), not the FLOPs.
  Consider deliberately over-partitioning + a kill-a-worker chaos test to demonstrate the
  fault-tolerance property.
- GKE cost note: burst workers fit the existing cluster off-hours; if adding a node pool, spot
  VMs are the realistic pattern (and a nice talking point — banks do exactly this).
- This state has a hard dependency on the EOD idea (the gate event). If that state doesn't
  exist yet, either build a minimal EOD trigger inside this state (and let the EOD state absorb
  it later) or sequence EOD first — decide with the user.
- YU-state conventions: spec pack under `specs/YUxx-<name>/`, same-named branch, parent lineage,
  **commit but never push**; staging CI/CD only with user approval.

## Decisions already made (don't re-litigate)

- Historical simulation, not Monte Carlo / parametric — no derivatives in the book, and the deck
  itself says the engineering interest is the grid, not the math (deck 04 slide 40).
- Tasks must be idempotent and independently re-runnable — that's the deck's fail-safe
  requirement and what makes the chaos-test demo work.
- This complements, not replaces, YU03 — the two-path architecture (deck 05 slide 13) is the
  narrative frame.

## Open questions / known issues

- Scenario source v1: self-accumulated EOD history (slow to accumulate), TAQ-derived daily
  returns (needs the tick store), or a bundled static CSV of real historical returns
  (fastest, fine for v1)? Recommend static CSV first, pluggable later.
- k8s Job with `parallelism` + work queue vs NATS JetStream pull-consumer workers — YU04 built
  JetStream muscle; either is defensible, pick in research.md.
- Where does the morning report render — Grafana only, or a web-front-end page? (User call.)

## Suggested first steps for next chat

1. Read this doc + `HANDOFF-idea-eod-price-production.md` (the gating dependency).
2. Confirm sequencing with the user: EOD state first, or minimal EOD trigger bundled here.
3. Confirm state id/name (e.g. `YUxx-overnight-var-grid`).
4. Write research.md (position snapshot source, work-queue mechanism, scenario source), then
   implement single-worker end-to-end before parallelizing.
