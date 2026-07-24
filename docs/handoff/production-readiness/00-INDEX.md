# Production-readiness board — the FINOS conference phase

> **The presentation is accepted for the FINOS conference (Dov & Karl, via the professor, 2026-07-24).**
> That changes the mandate: **stop adding features, start proving the system is production-credible.**
> Home: `traderX-YU15-eod-risk-extract/docs/handoff/production-readiness/`.
> Deadline: the November conference. "Floor it."

## The pivot

**STOPPED (do not resume without a decision):**
- The **latency thread** (LATENCY-01/02/03 are done; LATENCY-04 compact placement is NOT being written).
- **Gateway scale-out** past 4 (already parked pending compute credits).
- **New features** generally. FIX cancel/status, the order read model, options, and the risk extract were
  the last feature wave and they all landed.

**The performance story is DONE and sufficient** — do not spend more on it. Banked and slide-ready:
190k/s per-order at 4 gateways (95% linear), consensus commit **185–227 µs at every point measured**,
apply 0.45–0.57 µs, the lowpark config win (4.85 ms → ~2.0 ms, 2.4×), and the insulation result
(*a client seeing 459 ms was served by a cluster committing in 198 µs*). More performance does not buy
credibility now. Testing and supportability do.

## The board

| # | Brief | Priority | Gated on |
|---|---|---|---|
| [01](01-upstream-rebase-spike.md) | **Upstream TraderX rebase — SPIKE first** | **DO FIRST** — it sequences everything else | nothing |
| [02](02-test-coverage-inventory.md) | Test-coverage inventory + coverage map | **DO NOW** (parallel, cheap, produces a slide) | nothing |
| [03](03-baseline-unit-tests.md) | Unit tests for the plain-vanilla TraderX baseline | high | **01** (same code the rebase changes) |
| [04](04-milestone-and-integration-tests.md) | Milestone unit tests across YU states + integration tests | high | 02 (map), partly 01 |
| [05](05-opentelemetry-observability.md) | OpenTelemetry + observability platform, **async** | high | nothing — **start in parallel** |
| [06](06-kdb-journaling-playback.md) | kdb as the time-series / playback store (into YU07) | medium | **unblocked — scope settled 2026-07-24** |
| [07](07-risk-integration-with-alex.md) | Risk-component integration with Alex | high (blocks another person) | a design session with Alex |
| [08](08-github-io-branding.md) | YU-branded github.io site | low ("if you have time") — but Dov wants it | sync with Dov |

## TWO DECISIONS THAT GATE REAL WORK

**1. Rebase vs. baseline tests — they collide.** Dov suggests unit-testing the *plain vanilla* TraderX
we forked (brief 03). Brief 01 rebases *that exact code* onto a newer upstream. Writing those tests
first means rewriting them after. **So: spike 01 first (1–2 days), then decide.** Small delta → rebase,
then test once. Large delta → test our own YU-layer code (upstream can't touch it) while the rebase
runs separately, and accept rework on the baseline tests.

**2. ✅ SETTLED (professor, 2026-07-24) — kdb is the TIME-SERIES STORE.** It becomes our market-data /
trade time-series and replay-analytics store, slotting into **YU07 (historical tick store)**. **The Aeron
Archive consensus journal stays as-is** — it is the deterministic replay source of truth that every
correctness property rests on (byte-identical members, replay reproducibility, cold-follower rejoin, the
sequence-addressed risk extract). Brief 06 is unblocked; build it. **Keep the two senses of "journal"
and "playback" clearly separated in code, docs, and slides** — authoritative (Aeron: consensus, recovery,
on the hot path) vs analytical (kdb: query, analytics, demo playback, off-consensus and best-effort).

## Context the next chat needs (don't rediscover this)

**We are further along on testing than the email assumes.** Existing green suites: **YU13 269 / YU14 283 /
YU15 300**, plus 4 allocation gates, `noGcTest`, 2 Epsilon gates, and byte-identical determinism
verification across all three members after millions of orders. That is a real *engine-layer* testing
story that simply isn't visible. **The gaps are the vanilla baseline services and cross-service
integration** — which is exactly what Dov pointed at.

**Observability we already have:** Prometheus-format `/metrics` on members and gateways
(`traderx_cluster_role` — 1=leader, `traderx_cluster_applied`, `traderx_cluster_next_order_ref` =
ground-truth committed counter, `traderx_cluster_trades`), `/ready`, an env-gated `/latency` endpoint
(`LATENCY_DECOMP=1`), promtail, and per-thread `/proc` CPU profiling. **Missing: OTel traces, a
collector, and a platform.** ⚠️ `kubectl top` is **~70× unreliable** under load — never quote it; use
`/proc`.

**The risk-integration return path already exists** (this makes brief 07 small): YU03 gives a two-tier
in-memory risk gateway with a control plane (`/risk/control/{policy,restriction,security}`, token-gated);
YU04 makes those control feeds **durable** — live JetStream delta stream + transactional outbox +
watermarked-snapshot bootstrap; limits live in memory in `BlpRiskState`. **That IS the "low-latency
component caching limits in memory" the professor describes.** YU15 already delivers the outbound half
(sequence-addressed, byte-identical EOD extract, immutably delivered to `gs://`). Brief 07 is mostly
*wiring the return path*, not new architecture.

**Durability today:** Aeron Archive consensus journal replicated to all 3 members + periodic snapshots;
GCS backup cronjob (`yu12-snapshot-backup`, 5-min RPO, currently **suspended**) and a restore path gated
on `RESTORE_FROM_GCS=1` (default off = empty boot).

**Spec-kit layering — the trap that has bitten most often.** `runtime-overrides` compose cumulatively
(last-wins); `generation/kubernetes` does **NOT** overlay (per-state `cp -R`, every state needs its own
complete set). `render-state-YUxx.sh` overlays **only its own layer**, so an ancestor-layer edit needs
the chain re-run **from that ancestor forward**. **Files re-declared at multiple layers must be changed
at the highest carrier on EVERY branch** — `MatchingEngineClusteredService` (YU12/13/14/15),
`BlpRiskState` (YU14 overrides YU03), `ClusterNodeMain` (YU13+YU15), `PubSubConfig`. A clean copy into a
shadowed layer applies to git and is **inert at generation**. **Verify two ways: spec md5 AND a
re-rendered, marker-grepped generated tree.**

**Numbering note:** the professor's email says "YU01–YU12"; we are actually at **YU02–YU15**. Worth one
line of clarification so the presentation numbering is consistent.

## Standing conventions

- **`git push` goes to yaakov.** Commit freely, never push.
- kubectl/gcloud run directly and unrestricted, EXCEPT `gcloud container clusters resize --num-nodes 0`,
  which the harness classifier blocks → hand to yaakov.
- GKE bring-up traps: **pd-standard boot disks for load pools** (`SSD_TOTAL_GB`=500 is the binding quota,
  not CPU) and **bring an untainted pool up alongside the tainted member pool** or CoreDNS/konnectivity
  starve and members crash-loop on peer DNS. `c2d-load-pool` can't sit below 4 nodes.
- Decks are achievement-focused (no caveats/limitations); **docs stay honest**.
- Quote **ratios and the commit number**, never a single run's absolute client RTT (~1.5–2× run-to-run
  variance at fixed config).
