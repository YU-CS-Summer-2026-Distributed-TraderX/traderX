# Handoff: Execution Algo Engine (VWAP / TWAP / POV parent-child orders)

> One of 8 idea-handoffs produced from the professor's slide deck
> (`Combined_Financial_Systems_Deck` — decks 01/03). Each idea is a service the deck describes
> that TraderX does not have. This doc is self-contained for a fresh chat.

## What this chat accomplished

- Extracted and analyzed the full slide deck (7 decks, ~287 slides) against the current TraderX
  system and the YU-state roadmap.
- Identified the **execution algo engine** as a missing deck service: TraderX accepts only
  single, immediate orders — there is no concept of a *parent* order sliced into *child* orders
  over time.
- Confirmed no overlap with planned work: YU04 (durable control feeds, in progress) and the
  planned YU05 bundle (settlement/recon, regulatory reporting, TCA, auth) don't touch execution
  algos. TCA in YU05 is actually a natural *consumer* of this work (algo fills benchmarked vs
  VWAP/arrival price).

## Branch / repo state

- Repo: `/Users/yaakov/Desktop/Summer 26/lmax/traderX`, currently on `YU04-durable-control-feeds`
  (HEAD `5701f38`). Production is `YU02-lmax-kubernetes-blp-ha`, live at `yaakovseif.dev`.
- This doc proposes future work only — no code changes made.

## Goal for next chat

Design and scaffold a new YUxx state (claim the next free id under `specs/` — YU05 is reserved
for the post-trade-controls bundle) adding an **algo execution engine service** that:

1. Accepts a parent order (instrument, side, quantity Q, algo type, time window, max
   participation rate).
2. Slices it into child orders per **TWAP** (equal time buckets) and **VWAP** (historical volume
   profile weighting), with the deck's catch-up/shortfall logic and a participation-rate cap.
3. Submits every child through the existing path: trade-service REST (or the future FIX/binary
   ingress) → YU03 risk gateway → BLP matching engine. Children are ordinary orders to the BLP.
4. Event-sources its own state (parent order schedule, fills received) so a crashed algo engine
   resumes from its log rather than restarting the schedule (deck 03 slide 15: "state
   checkpointing for crash recovery").
5. Surfaces algo progress in the web front end (deck 03 slide 27: algo manager pause/resume).

## Key files

| Path | Why it matters |
|---|---|
| `order-matcher/` | The BLP — children arrive here; no changes needed for a v1 |
| `trade-service/` | Current order-entry REST ingress the algo engine submits through |
| `specs/YU03-in-memory-risk-gateway/` | Spec-pack shape to copy; risk gateway all children must pass |
| `HANDOFF-combined-yu05-state.md` | The YU05 bundle (TCA is the consumer of algo fills) |
| `web-front-end/` | Where algo progress UI goes |

## Architecture / context the next chat needs

- Deck source: deck 03 slides 37–43 contain full pseudocode for `vwap_algo` and `twap_algo`
  (bucketing, shortfall carry-forward, `max_pov` cap, `submit_through_risk_gateway_and_sor`);
  deck 01 slides 39–43 give the business rationale (market impact, POV, Implementation
  Shortfall). Extracted text: ask the user for the pptx path
  (`.../trading systems slides/ppxs/03-Trading-and-Execution.pptx`).
- TraderX has **no historical volume profile** data. TWAP needs none (start there). VWAP can use
  (a) a synthetic profile, or (b) the professor's ~3TB NYSE TAQ dataset once the historical tick
  store idea lands (see `HANDOFF-idea-historical-tick-store-backtesting.md`) — design the profile
  source as pluggable.
- The algo engine is a **warm-path** service (seconds granularity), NOT hot-path. Do not put it
  inside the BLP. A plain Spring Boot service (matching the other services) with a scheduler
  loop is the right altitude; it consumes fill events from NATS to track progress.
- YU-state conventions: spec pack under `specs/YUxx-<name>/` (spec.md, plan.md, research.md,
  data-model.md, contracts, requirement deltas, architecture docs, ADRs, tasks.md), a git branch
  with the same name as the state, parent lineage recorded, **commit but never push**, isolated
  staging CI/CD only with explicit user approval. Generation-pipeline gotcha: some files have
  plausible-looking but dead override locations — verify propagation before relying on
  `runtime-overrides/` (details in `HANDOFF-durable-control-feeds.md`).

## Decisions already made (don't re-litigate)

- Children go through the normal ingress + risk gateway path — the algo engine gets no special
  bypass. (Deck: every child passes the full hot path; also exercises YU03 under sustained load.)
- TWAP first, VWAP second — TWAP has no data dependency.
- Separate service, not a BLP feature — parent-order management is stateful, multi-second work
  that must not touch the single-threaded no-GC hot path.

## Open questions / known issues

- Where does the parent order come from — a new UI panel, or a REST endpoint only for v1?
- Bucket interval (deck uses 5 min; a demo probably wants 5–30 s so runs are watchable).
- Should child fills be tagged with a parent-order id end-to-end (needed by YU05 TCA)? Probably
  yes — decide the field name early since it touches the BLP event schema.
- Benchmark impact: per working conventions, run `bench-compare` after any change near the order
  path.

## Suggested first steps for next chat

1. Read this doc, `HANDOFF-combined-yu05-state.md`, and `specs/YU03-in-memory-risk-gateway/spec.md`
   for the spec-pack shape.
2. Confirm state id + name with the user (e.g. `YU06-execution-algo-engine`).
3. Write the spec-pack with TWAP as slice 1, VWAP (pluggable volume profile) as slice 2.
4. Prototype the TWAP loop against a local run (`start-env.sh`) before writing manifests.
