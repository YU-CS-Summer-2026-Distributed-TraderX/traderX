# TraderX / LMAX — Presentation Master Index

> One navigable map of the whole project for building the November FINOS deck. Each arc has its
> headline, the **slide-safe** numbers, and where the evidence lives. Built 2026-07-24, maintained as
> the production-readiness phase lands. **Honest doc — the deck stays achievement-focused, but this
> index carries the caveats so the deck quotes the right numbers.**

## The system in one paragraph

A fork of **FINOS TraderX** whose order-matcher is replaced with an **LMAX Disruptor Business Logic
Processor** — a single-threaded, in-memory, event-sourced matching engine — run as a **replicated state
machine over a 3-member Aeron Raft cluster**, on **Google Kubernetes Engine**. Built as a **layered
spec-kit lineage** of states **YU02 → YU15**, each composing on all its ancestors. The engine is a
genuine crossing limit order book with a full order lifecycle, reachable over REST, **FIX 4.4**, and a
binary order-entry protocol; order and trade state project asynchronously (CQRS) into a SQL read model;
an end-of-day risk extract is delivered immutably to cloud storage for an external risk engine.

## The narrative arcs (each is a deck section)

### 1 — The architectural pivot
The order-matcher became the BLP: single-threaded in-memory matching, deterministic event-sourced
replay, MariaDB demoted to an async read-model. Then made HA: Aeron Raft consensus, journaled +
snapshotted, replicated to a quorum before commit.
**Evidence:** `[[project_traderx_state_lineage]]`, `[[project_yu12_aeron_cluster_state]]`, the YU02 deck.

### 2 — Throughput (DONE)
**Headline:** the "12k ceiling" was a harness myth; the real per-order distributed ceiling is
**gateway-bound and scales linearly** — **149,610/s at 3 gateways → 190,300/s at 4 (95% of linear)** —
extrapolating to a **~440k/s consensus ceiling** (matches the measured **438k** batch path). Same engine
does **1.13 M orders/s in-process**.
**Method (this is itself a slide):** a per-hop funnel (offered → decoded → offer-success → committed),
ground truth = the engine's own sequence counter (`nextOrderRef`), never a booked counter; the load
generator's own ceiling proven in isolation first (2.19 M/s vs an echo) so a harness could never be
mistaken for the system.
**Evidence:** `scripts/bench/RESULT-per-order-ceiling-phase1.md`, `RESULT-gateway-scaleout-phase2.md`,
`RESULT-bin-generator-isolation.md` (YU13 worktree); memory `[[project_per_order_ceiling]]`.

### 3 — Latency (DONE, three rounds)
**Headline:** decomposed a byproduct ~4 ms into hops, found it was Aeron's default idle strategy
**parking threads**, and a **one-config-value change (`lowpark`) cut per-order p50 4.85 ms → ~2.0 ms
(2.4×)** with no hardware or architecture change. Consensus commit is **~220 µs and load-invariant**
(185–227 µs across a 6× sweep). The **client↔gateway wire (321 µs / 37%) is the largest single hop** —
bigger than consensus (24%). And the standout method result: **capping in-flight relocates the queue
into clients' send backlogs and is invisible server-side** — *a client seeing 459 ms was served by a
cluster committing in 198 µs*.
**Method:** single-clock intervals only (cross-host clocks differ by ms), coordinated-omission-safe,
unsaturated, side-channel HdrHistograms, `/proc` per-thread CPU (never `kubectl top`).
**Evidence:** `scripts/bench/RESULT-latency-decomposition.md`, `RESULT-latency-02-step0.md`,
`RESULT-latency-03-frontier.md` (YU13 worktree); briefs `LATENCY-01/02/03`; memory
`[[project_latency_thread]]`. **Remaining levers:** `LATENCY-02` (compact placement → RDMA → …).

### 4 — Correctness & HA (DONE, machine-verified)
**Headline:** after every run all three members are **byte-identical** (order-book + position hashes)
across millions of orders; failover, snapshot/replay, and **cold-follower rejoin from an empty disk**
all proven live; **0 ID reuse across 2 failovers**; a deterministic-core change (STP, atomic replace,
idempotency backward-shift fix) proven under the divergence rule.
**Evidence:** memory `[[project_yu13_limit_order_book_state]]`, `[[project_yu12_aeron_cluster_state]]`,
`[[project_yu11_aeron_state]]`; the HA proof docs; `RESULT-idempotency-corefix`.

### 5 — OMS realness (DONE)
**Headline:** order state is now real outside the engine — a leader-side tap projects the full lifecycle
into a SQL read model with a REST blotter, giving order-level changes their **first SQL effect-end**
(NEW→CANCEL proven live). A **real sell-side FIX 4.4 venue surface**: place (D), cancel (F), replace (G),
status (H), mass-status (AF), status served from the same read model. **Listed equity options** on the
same book with multiplier-aware risk. **EOD risk extract** addressed by consensus sequence, byte-identical
across members and replay, delivered **write-once/immutable** to `gs://` (overwrite → 403) for the
external risk engine.
**Evidence:** briefs `07`, `LATENCY-adjacent-FIX-cancel-status`; memory
`[[project_yu14_listed_equity_options_state]]`, `[[project_yu15_eod_risk_extract_state]]`.

### 6 — Testing & supportability (IN FLIGHT — the production-readiness phase)
**Headline:** **853 machine-verified engine tests (YU13 270 / YU14 283 / YU15 300)** + allocation/GC
gates + 4-layer determinism checks — and as of this phase they **run in CI** (`engine-tests` green
badge). Honest before/after: *"853 machine-verified tests, and not one ran in CI — the whole story
lived on a laptop. It now runs on every push and PR."*
**In progress:** baseline/integration tests, OpenTelemetry observability (async, off the hot path),
kdb as the time-series/playback store, risk integration with Alex.
**Evidence:** `production-readiness/00-INDEX.md` (the board), `02-RESULT-coverage-map.md`,
`04-RESULT-ci-bringup.md`; memory `[[project_production_readiness_phase]]`.

### 7 — The rebase experience (IN FLIGHT — the professor's headline ask)
**Headline:** tracking an upstream open-source baseline as a *layered downstream fork* — **62 commits
behind, 0 lines of service code changed, yet ~150 hand edits required** (the shadow-copy cost curve:
8 at YU03 → 16 at YU15). The transferable lesson: **in a generation model, git reports on the merge
inputs, not the generated outputs that actually run** — a clean merge into a shadowed layer is inert and
dangerous. 29 of the 62 commits were **CVE fixes** — the real reason downstreams must track upstream.
**Evidence:** `production-readiness/REBASE-EXPERIENCE.md` (the step-by-step narrative, maintained live),
`01-upstream-rebase-spike-FINDINGS.md`.

## Slide-safe numbers (the honest set)

| metric | value | note |
|---|---|---|
| Per-order throughput, 4 gateways | **190,300 /s** | 149,610 at 3 gw, 95% linear |
| Batched single-feed throughput | **~438,000 /s** | different contract — never blend with per-order |
| Consensus ceiling | **~440,000 /s** | ⚠️ **extrapolated**, not measured directly |
| In-process matching engine | **1.13 M /s** | engine figure, not a cluster figure |
| Per-order latency | **p50 < 1.5 ms to 75k/s, p99 ~2 ms** | at a correctly-sized in-flight window |
| lowpark latency win | **4.85 ms → ~2.0 ms (2.4×)** | one config value, no hardware |
| Consensus commit round-trip | **~220 µs** (185–227) | **load-invariant** over a 6× sweep |
| Apply / match | **0.45–0.57 µs** | in-process |
| RTT hop split | wire 37% · Aeron transport 33% · **consensus 24%** | consensus is NOT the bottleneck |
| Determinism | **byte-identical across 3 members** | after millions of orders, + on replay |
| Engine tests | **853 green, now in CI** | YU13 270 / YU14 283 / YU15 300 |
| Rebase cost | **62 commits / 0 code / ~150 edits** | 29 were CVE fixes |

## The "do NOT put on a slide" list

- **The "12k ceiling"** — retired; it was a quota-capped generator coasting with zero backpressure.
- **~440k stated as measured** — it's an *extrapolation* from the consensus leader's 43% CPU headroom.
  Say "extrapolates to ~440k."
- **Single-run client-RTT absolutes to two significant figures** — ~1.5–2× run-to-run variance at fixed
  config. **Quote ratios and the ~220 µs commit, never one run's absolute RTT.**
- **`kubectl top` numbers** — proven ~70× unreliable under load; all CPU figures are from `/proc`.
- Per-order and batch throughput on the same axis — different contracts.

## Where the evidence lives (worktree map)

- **YU15 worktree** (`traderX-YU15-eod-risk-extract`) — tip; `docs/handoff/production-readiness/` (the
  testing/rebase phase + this index), `docs/handoff/` (YU11/YU12 handoffs).
- **YU13 worktree** (`traderX-YU13-limit-order-book`) — `handoff/7-22/` (throughput/latency/order-lifecycle
  briefs + board) and `scripts/bench/RESULT-*.md` (the campaign result docs + rigs).
- **Memory** (`~/.claude/.../memory/`) — durable cross-session facts; `MEMORY.md` is the index.

## Numbering note for the deck

The professor's email says "YU01–YU12"; the states are actually **YU02 → YU15**. Confirm the numbering
with him so the presentation is internally consistent.
