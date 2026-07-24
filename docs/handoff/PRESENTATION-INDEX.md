# TraderX / LMAX — Presentation Master Index

> One navigable map for building the November FINOS deck. **Final numbers only — the destination, not
> the journey.** The audience doesn't care what we beat or how we measured it; they want what the system
> *does*. No before/after, no "used to be X," no process. (One deliberate exception: the **rebase
> experience** is a process narrative — the professor asked for that one specifically.)
> Built 2026-07-24, kept current as the remaining work lands.

## The system in one paragraph

A fork of **FINOS TraderX** whose order-matcher is an **LMAX Disruptor** in-memory matching engine, run
as a **replicated state machine over a 3-member Aeron Raft cluster** on **Google Kubernetes Engine**. A
genuine crossing limit order book with a full order lifecycle over **REST, FIX 4.4, and a binary
protocol**; order and trade state project asynchronously into a SQL read model; an end-of-day risk
extract is delivered immutably to cloud storage for an external risk engine.

## THE NUMBERS

| | |
|---|---|
| **Per-order throughput** | **~190,000 orders/sec** (4 gateways, scales linearly) |
| **Batched throughput** | **~438,000 orders/sec** |
| **Matching engine (in-process)** | **1.13 million orders/sec** |
| **Per-order latency** | **p50 < 1.5 ms, p99 ~2 ms** (to 75k orders/sec) |
| **Consensus commit** | **~220 µs** |
| **Match/apply time** | **~0.5 µs** |
| **State consistency** | **byte-identical across all 3 members** (verified every run) |
| **Failover** | automatic, sub-second, zero order loss |
| **Test suite** | **853 tests, running in CI** |

## The capability arcs (deck sections)

**1 — Architecture.** LMAX Disruptor matching engine as a replicated state machine over Aeron Raft
consensus; event-sourced, journaled, snapshotted; MariaDB is an async read-model (CQRS). Built as a
layered lineage of **15 states, YU01 (lmax-sequencer) → YU15** — each composing on all its ancestors.

**2 — Throughput.** ~190k orders/sec per-order, scaling linearly with gateways; ~438k batched; 1.13M in
the engine core. Gateway-bound, with consensus headroom to spare.
*Evidence: `scripts/bench/RESULT-per-order-ceiling-phase1.md`, `RESULT-gateway-scaleout-phase2.md` (YU13 wt).*

**3 — Latency.** Per-order p50 under 1.5 ms, p99 ~2 ms, sustained to 75k/sec. Consensus commit ~220 µs;
match ~0.5 µs. The distributed consensus is a small slice of the round trip — most of it is ordinary
networking.
*Evidence: `scripts/bench/RESULT-latency-*.md` (YU13 wt).*

**4 — Correctness & HA.** All three members stay byte-identical (order-book + position hashes) across
millions of orders and on replay; automatic sub-second failover; a crashed member rejoins from an empty
disk and can later lead; deterministic matching (self-trade prevention, atomic replace).
*Evidence: HA proof docs; memory `[[project_yu13_limit_order_book_state]]`, `[[project_yu12_aeron_cluster_state]]`.*

**5 — OMS surface.** A real sell-side venue: place / cancel / replace / status / mass-status over **FIX
4.4**, REST, and binary; live order blotter from a SQL read model; **listed equity options** with
multiplier-aware risk; an **end-of-day risk extract** delivered write-once and immutable to cloud storage
for the external risk engine.
*Evidence: briefs `07`, `LATENCY-adjacent-FIX-cancel-status`; memory `[[project_yu15_eod_risk_extract_state]]`.*

**6 — Testing & supportability.** 853 machine-verified engine tests running in CI (green badge);
OpenTelemetry observability, kdb time-series store, and risk-engine integration in progress.
*Evidence: `production-readiness/00-INDEX.md`, `02-RESULT-coverage-map.md`, `04-RESULT-ci-bringup.md`.*

**7 — The rebase experience** *(the process narrative — deliberate).* Tracking an upstream open-source
baseline as a layered downstream fork: what it costs, where it silently fails (a CVE patch that lands in
an inert layer never reaches the running code), and the lessons for anyone forking TraderX.
*Evidence: `production-readiness/REBASE-EXPERIENCE.md` (live), `01-upstream-rebase-spike-FINDINGS.md`.*

## Accuracy guardrails (so the deck doesn't over-claim)

- The **~440k consensus ceiling** figure is an **extrapolation**, not a measured number — don't state it
  as measured. The measured per-order figure is ~190k.
- **Per-order and batch throughput are different contracts** — never put them on the same axis.
- Latency: quote the **sustained band** (p50 < 1.5 ms / p99 ~2 ms) and the **~220 µs commit**, not a
  single run's exact figure (there's normal run-to-run variance).
- CPU figures are from per-thread OS sampling, not container-level tooling.

## Where the evidence lives (worktree map)

- **YU15 worktree** (`traderX-YU15-eod-risk-extract`, the tip) — `docs/handoff/production-readiness/`
  (testing + rebase phase, this index).
- **YU13 worktree** (`traderX-YU13-limit-order-book`) — `handoff/7-22/` (briefs) and
  `scripts/bench/RESULT-*.md` (throughput/latency result docs + rigs).
- **Memory** (`~/.claude/.../memory/`) — durable facts; `MEMORY.md` is the index.

## Numbering note

The lineage is **YU01 (lmax-sequencer) → YU15 = 15 states.** This matches the professor's "YU01–…"
framing (his email said "YU01–YU12"; we're now through YU15). All 15 branches are on origin and caught
up on the upstream CVE baseline as of 2026-07-24.
