# Consolidated ideas backlog (single source of truth as of 2026-07-07)

This doc merges ALL open idea sources into one deduplicated backlog:
- `HANDOFF-production-realism.md` (original 6-item roadmap)
- `HANDOFF-market-data-realism.md` (6 data-driven candidates for the professor's 3TB TAQ dataset)
- The 11-item scored necessity table (produced in the YU05-planning chat; superset of the two above)
- The 8 deck-idea handoffs from the professor's slide-deck analysis (`HANDOFF-idea-*.md`, 2026-07-07)
- `HANDOFF-idea-blp-ha-hardening.md` (CLOUD-ARCHITECTURE.md §7's pre-existing BLP HA/throughput
  backlog — not a deck idea, predates YU03-05, added 2026-07-07)
- `possible_improvements.md` (perf-engineering backlog — stays its own ongoing track, not states)

**If an older doc disagrees with this one, this one wins.** The two older handoffs' candidate
menus are superseded; their architecture/gotcha sections (generation-pipeline dead overrides,
GCP/CI-CD context, two-tier gateway design rationale) remain valid reference.

## Done / in flight — retired from the backlog

| Item | Where | Status |
|---|---|---|
| Pre-trade risk gateway (SEC 15c3-5, two-tier) | YU03 | Done |
| Durable control feeds (outbox → JetStream) | YU04 | Done |
| Settlement + recon, regulatory reporting, TCA, auth/entitlements | YU05-post-trade-compliance | Done |
| EOD price production + overnight batch chain | YU06-eod-price-production | Done (2026-07-08). Producer in trade-processor, consumer (EOD P&L) in position-service, gated by durable `EOD_PRICES_READY` JetStream event. See `HANDOFF-execution-algo-engine-yu07.md` |
| Historical tick store + backtesting | YU07-historical-tick-store | Done (2026-07-09). See `HANDOFF-idea-historical-tick-store-backtesting.md` |
| Execution algo engine (TWAP/VWAP) | YU08-execution-algo-engine | Done (2026-07-10/12). See `HANDOFF-idea-execution-algo-engine.md` |
| Ops hardening (secrets, journal archival→GCS, DR runbook, pipeline stale-jar fix) | YU09-ops-hardening | Done (2026-07-12/13). Deployed to prod. |
| **BLP HA hardening — lease-starvation false-demote + `blp-role` label bug** (item #12 items 1–2) | on `YU02-lmax-kubernetes-blp-ha`, propagated to all 8 states | **Done (2026-07-14).** Deployed to prod + validated. See `HANDOFF-idea-blp-ha-hardening.md` status block. Item #12's remaining pieces (beyond-42k throughput, broader durability) stay open below. |

## Open backlog (deduplicated, in recommended order)

| # | Idea | Handoff / source | Absorbs / overlaps | Depends on |
|---|------|------------------|--------------------|-----------|
| 1 | **Historical tick store + backtesting + replay** | `HANDOFF-idea-historical-tick-store-backtesting.md` | **Merges** table #9 (market data warehouse) + table #10 (realistic price replay) + market-data-realism candidates #1/#4 — replay is a feature of the store, not a state | **Unblocked (2026-07-09): user has Feb (364GB) + March (286GB) TAQ data in OneDrive** — likely full-market, not just the pilot slice. Parallel-trackable — fully off hot path. **Recommended YU07** — see `HANDOFF-historical-tick-store-yu07.md` |
| 2 | **Overnight VaR/ES batch grid** | `HANDOFF-idea-overnight-var-batch.md` | Completes YU03's two-path risk architecture | **Not started (2026-07-09): the pricing/risk teammate's VaR work is a separate project and may not be incorporated here — do not assume it as a dependency or a future consumer of YU06's inputs. If/when we build our own VaR/ES, use OpenSourceRisk Engine (ORE) — https://opensourcerisk.org / github.com/OpenSourceRisk/Engine — rather than building risk analytics from scratch** |
| 3 | **Ops hardening** (secrets mgmt, DR, journal archival) | Table #6 / roadmap #6 — no dedicated handoff yet | Journal archival shares GCS plumbing with #1 | Nothing. Secrets slice is small — can ride alongside anything |
| 4 | **Execution algo engine (TWAP/VWAP/POV)** | `HANDOFF-idea-execution-algo-engine.md` | YU05 TCA consumes its parent-tagged fills | **Recommended YU08** — with #1 built, v1 can ship real VWAP, not just TWAP |
| 5 | **Advanced order types + time-in-force (BLP)** | `HANDOFF-idea-advanced-order-types.md` | Pegged orders excluded (need NBBO from #7) | Nothing (tick feed exists from YU03). Hot-path work — bench-compare mandatory |
| 6 | **FIX protocol gateway** | `HANDOFF-idea-fix-protocol-gateway.md` | **Merges** `possible_improvements.md` §4–5 (ingress transport overhead) | Nothing |
| 7 | **Multi-venue + SOR + NBBO** | `HANDOFF-idea-multi-venue-sor-nbbo.md` | **Absorbs the L1 slice** of table #5 (market data dissemination); unblocks #8 and pegged orders | Feasibility call first (stub venue vs full second BLP) |
| 8 | **L2 dissemination + market surveillance** (pair) | Table #4 + #5 remainder / market-data-realism #3 | Explicitly deferred from YU05; surveillance needs L2, L2 needs a consumer | #7 |
| 9 | **Market data quality engine** (gaps/spikes/staleness) | `HANDOFF-idea-market-data-quality-engine.md` | Feeds YU03 price-reasonability fail-safe | Nothing — small; good gap-filler state |
| 10 | Reference-data / corporate-actions backfill | Table #8 / market-data-realism #6 | — | Low priority; mechanical |
| 11 | ML-based dynamic risk calibration | Table #11 / market-data-realism #5 | Extension of YU03; could consume #2's volatility data | Lowest priority |
| 12 | **BLP HA/throughput hardening** — ~~lease starvation, `blp-role` label bug~~ **(both FIXED 2026-07-14)**; *remaining:* beyond-42k gateway bottleneck, replication durability testing, + 2 new gaps found on deploy (NATS broker memory limit / JetStream file storage; replicator stream re-create on NATS reconnect) | `HANDOFF-idea-blp-ha-hardening.md`, `HANDOFF-ha-throughput-improvements.md` | `CLOUD-ARCHITECTURE.md` §7's pre-existing backlog | Throughput levers detailed in `HANDOFF-ha-throughput-improvements.md`. **Re-measured 2026-07-16** (see `scripts/bench/README.md` "measured tiers"): the ceiling is REST *per-order* ingress (thread-per-request + per-order ack-future ≈ 9.2k/s on kind; engine watermarks in lockstep, rings empty — NOT projector→DB, that was a bench credit-limit misread); `/orders/batch` already does ~74k/s booked, where the output ring becomes the next constraint. Remaining levers: batch/async ingress as the default path, then binary/SBE ingress, teammate-adjacent on the snapshot/journal path only |

## Tracked issues (non-state — fixes/chores, not YUxx candidates)

| Issue | Doc | Status |
|---|---|---|
| Spec-layer forward-propagation gaps (pattern + open instances) | `HANDOFF-issue-spec-layer-propagation-gaps.md` | All instances fixed 2026-07-14 (YU04/YU05 manifests; YU02 kind database manifests now tracked on all 8 branches) |
| order-matcher `ReplicaBootstrap` logs INFO every ~1s | `HANDOFF-issue-replica-bootstrap-log-noise.md` | Open, cosmetic; check the 1s loop isn't re-running snapshot work |
| Back YU05 TCA with YU07's tick store (arrival-price + VWAP gaps) | `HANDOFF-issue-tca-tickstore-retrofit.md` | Open — new-state candidate (≈YU10); NOT YU05 (lineage) and prefer not editing the finished YU08 |
| **Prod database Deployment uses `emptyDir`** — any pod reschedule (node upgrade, eviction, crash) loses ALL read-model data; only the order-matcher journal has a PVC. Fix: PVC-backed MariaDB volume (kind + GKE; init-SQL must tolerate an already-populated volume) | found 2026-07-16 during GKE throughput work; no doc yet | Open — real durability gap for a prod deploy. Mitigant: the read model is journal-rebuildable, but rebuild is manual today |
| **order-matcher liveness probe kills the pod during long journal replay** — observed on kind 2026-07-16: 2 liveness restarts while replaying a ~4M-event journal (readiness-gating is correct; liveness doesn't allow for replay time). Fix: startupProbe with a failureThreshold sized to journal length (or liveness keyed on replay progress); YU09 journal rotation bounds replay but only when archival is enabled | found 2026-07-16; no doc yet | Open — becomes a crash-loop under a big enough journal. Related: execution-algo-engine ships NO probes at all (dead JVM stays "Ready" — bit us live 2026-07-15) |
| **AllocationGateTest: 72-byte steady-state allocation on the producer claim/write/publish path (NGC-01)** — `hotPathIsAllocationFreeInSteadyStateWithRiskGating` fails reproducibly on the generated YU09 order-matcher. Verified 2026-07-16 to be PRE-EXISTING (reproduces with the 2026-07-15 test-coverage changes reverted); surfaced by codeX's YU06–YU09 test run, not caused by it. Suspects: JDK/JIT profile on this machine vs. the gate's TLAB accounting, or a real allocation introduced by an earlier hot-path change — bisect against older generated trees | found 2026-07-15 (codeX run), verified pre-existing 2026-07-16; no doc yet | Open — hot-path no-allocation guarantee (NGC-01) currently unproven on this machine |
| **Local start wrapper doesn't build JVM jars → `COPY build/libs/*.jar` / `lstat build/libs: no such file`** — every JVM service uses a single-stage `COPY build/libs/*.jar` Dockerfile, but only the *publish* pipeline runs `gradlew bootJar` (YU09 FR-OH30); the local `start-state-*-generated.sh` does not. So a fresh `generate` + local bring-up fails building the first JVM image (execution-algo-engine hit first), or `--skip-build` reuses a stale jar that boots old code and looks healthy. Fix: teach the local harness to `gradlew bootJar` each JVM context before `docker build`, OR make those Dockerfiles multi-stage. Workaround in `run-state-kind` skill §2b | hit 2026-07-15 (our YU08 bring-up) + 2026-07-16 (codeX ingress run) — recurs every fresh generate | Open — turns any clean-slate local bring-up into a hang until jars are hand-built |

`HANDOFF-ha-throughput-improvements.md` (the perf track referenced by item #12) now lives in this
`issues/` directory as of 2026-07-14 (previously a root-level untracked file on the YU02 worktree).

## Sequencing rationale (agreed 2026-07-07)

- **YU06 = EOD/batch chain** because it needs no external data, is medium-sized, and has the
  highest fan-out (serves YU05 recon/reporting, and would gate our own VaR work if/when it's built).
- **VaR (item #2) is not being built right now.** The pricing/risk teammate has his own separate
  project touching pricing + risk (VaR included, currently NPV-focused) — but it's a separate
  project and may not be incorporated into this one, so it is not tracked here as a dependency or
  planned consumer of anything this project builds. **Our own future VaR/ES work, whenever it's
  prioritized, should be built on OpenSourceRisk Engine (ORE)** — https://opensourcerisk.org /
  github.com/OpenSourceRisk/Engine — rather than hand-rolled risk analytics, consistent with this
  project's OSS-first policy (see the OSS framework audit in `status-through-YU06.md`).
- **YU07 = historical tick store** (reordered 2026-07-09): the professor's TAQ data arrived
  (Feb+March, ~650GB, OneDrive) — enough to make this real work instead of speculative
  scaffolding. Promoted ahead of the execution algo engine because it has higher fan-out (real
  VWAP volume profiles for the algo engine, real historical return data for our own eventual
  ORE-based VaR work) and because building it first lets the algo engine ship VWAP, not just
  TWAP, in its first cut. See `HANDOFF-historical-tick-store-yu07.md`.
- **YU08 = execution algo engine** (TWAP+VWAP, unblocked by YU07) — TCA (built in YU05) is its
  natural consumer. ML risk calibration (now item #11) may or may not overlap with the teammate's
  separate work — check before anyone picks it up, but don't assume coordination is required.
- **Teammate base-state note (2026-07-07, now understood as informational only):** the
  pricing/risk teammate has been working off **YU03-in-memory-risk-gateway**, locally only, no
  pushes to the cloud. Since his project is separate and may not merge into this one, this is
  background context, not a coordination requirement — no action needed on our side.
- **Tick store data has arrived (2026-07-09)**: Feb (364GB) + March (286GB) in OneDrive — ~650GB,
  almost certainly full-market rather than the originally-scoped 5–10-symbol pilot slice. File
  format not yet inspected (fixed-width/CSV/vendor binary all imply different normalizer code —
  get a sample before writing one). Full 3TB (if ever pursued) remains gated on the pipeline being
  proven against this slice first.
- Quality engine (#10) is deliberately parked mid-list as a small gap-filler between larger states.

## Standing conventions (apply to every item)

Spec pack under `specs/YUxx-<name>/` mirroring YU03's file list; same-named branch; parent
lineage recorded; **commit, never push**; isolated staging CI/CD only with explicit user
go-ahead; bench-compare after anything near the order/tick path; verify generation-override
propagation empirically (dead-override gotcha — see `HANDOFF-market-data-realism.md`
"Generation pipeline gotcha"); never commit HANDOFF-*/scratch docs.
