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
| [01](01-upstream-rebase-spike.md) → [FINDINGS](01-upstream-rebase-spike-FINDINGS.md) | **Upstream rebase spike — ✅ DONE 2026-07-24** | Sized: 2–3 days, low risk, **structural bucket empty**. **Brief 03 = GO now** | done |
| [02](02-test-coverage-inventory.md) → [MAP](02-RESULT-coverage-map.md) | Test-coverage inventory — **✅ DONE 2026-07-24** (`e3225c4f`) | **853 tests, all operator-driven, ZERO in CI** | done |
| [03](03-baseline-unit-tests.md) | Unit tests for the plain-vanilla TraderX baseline | high — **GO now** (01 says the code is byte-identical to upstream) | ready |
| [04](04-milestone-and-integration-tests.md) | Milestone unit tests across YU states + integration tests + **CI wiring FIRST** | high | ready (02 map done) |
| [05](05-opentelemetry-observability.md) | OpenTelemetry + observability platform, **async** | high | nothing — **start in parallel** |
| [06](06-kdb-journaling-playback.md) | kdb as the time-series / playback store (into YU07) | medium | **unblocked — scope settled 2026-07-24** |
| [07](07-risk-integration-with-alex.md) | Risk-component integration with Alex | high (blocks another person) | a design session with Alex |
| [08](08-github-io-branding.md) | YU-branded github.io site | low ("if you have time") — but Dov wants it | sync with Dov |

## TWO DECISIONS THAT GATE REAL WORK

**1. ✅ RESOLVED (spike 01, 2026-07-24) — they do NOT collide. Start brief 03 now.** The spike rendered the
shared baseline from our fork point and from `finos/main` today and diffed the trees: **upstream changed
zero lines of service source** in seven weeks (0 differing `.java`, 0 differing service `.ts`; only dep
versions and config moved). The "rebase changes the exact code brief 03 tests" premise is false — the code
brief 03 tests is byte-identical to upstream's. Baseline tests cannot be invalidated by the rebase. Two
cheap guards: brief 03 must not assert on dependency versions, and should start with the Java/NestJS
services and defer front-end unit tests (the one area with additive upstream churn) until after the merge.
The rebase itself is a **2–3 day dependency/CVE catch-up** (there is no code to rebase), runnable in
parallel with 03 as long as they're not on the same branch at once. Full sizing + strategy in the
[FINDINGS doc](01-upstream-rebase-spike-FINDINGS.md).

**2. ✅ SETTLED (professor, 2026-07-24) — kdb is the TIME-SERIES STORE.** It becomes our market-data /
trade time-series and replay-analytics store, slotting into **YU07 (historical tick store)**. **The Aeron
Archive consensus journal stays as-is** — it is the deterministic replay source of truth that every
correctness property rests on (byte-identical members, replay reproducibility, cold-follower rejoin, the
sequence-addressed risk extract). Brief 06 is unblocked; build it. **Keep the two senses of "journal"
and "playback" clearly separated in code, docs, and slides** — authoritative (Aeron: consensus, recovery,
on the hot path) vs analytical (kdb: query, analytics, demo playback, off-consensus and best-effort).

## Context the next chat needs (don't rediscover this)

**Testing posture, now MEASURED (spike 02, `e3225c4f`; map `02-RESULT-coverage-map.md`):**
**853 green tests — YU13 270 / YU14 283 / YU15 300** (note: 270, not 269), + 4 allocation gates + 2
Epsilon-GC gates (**`noGcTest` IS one of the two Epsilon gates, not a third**), + determinism asserted at
4 layers. Engine layer is genuinely strong and machine-verified — the "we're under-tested" premise is
wrong there. **Three findings that drive the plan:**
1. **NOTHING runs in CI** — not the JUnit suites, not the gates, not the proof scripts. The 5 GitHub
   workflows are docs/spec-kit/OpenAPI/script-parity only (`run-all-conformance-packs.sh` runs WITHOUT
   `--execute-runtime-checks`). Honest posture: *"853 machine-verified tests, all operator-driven, zero
   in CI."* **This is the cheapest + highest-visibility fix (a reviewer's first click is the Actions
   tab) — do it FIRST in brief 04, before writing anything new.**
2. **Silently-disabled tests (intersection of spikes 01+02):** upstream's `src/main/test` convention
   disables tests unless a component overrides `sourceSets`. `account-service` has the override;
   **`trade-service` / `trade-processor` / `position-service` do NOT → `trade-service` has ZERO executable
   tests, two inherited smoke tests are dead files.** It's an inherited baseline behaviour (so it's talk
   content, and the fix is the known one-liner `account-service` already carries), not our omission.
3. **17 falsifiable proof scripts** (cancel, ClOrdID suppression, STP+atomic replace, FIX session, recon,
   reproducible reg export, EOD risk extract) are our strongest correctness evidence and have **zero
   regression protection** — none in CI. The YU13 read-model effect-end proof isn't even a committed
   script yet. Promoting these to CI-run integration tests is brief 04's core value.

**Rebase, now SIZED (spike 01, `2ddc4c4f`; FINDINGS `01-upstream-rebase-spike-FINDINGS.md`):**
62 commits behind `finos/main` over 7 weeks = **29 dep/CVE bumps + 29 generator/publish plumbing + 6
website/docs; ZERO service source changes** (proven by rendering the baseline from both points and
diffing the trees — 0 differing `.java`, 0 differing service `.ts`). **Structural bucket empty.** The
real cost is the dead-layer trap: ~150 one-line config edits re-applied at the highest carrying layer per
branch (shadow-copy count grows 8 at YU03 → 16 at YU15 — that curve IS the talk narrative). Recommendation:
**do it as a 2–3 day dependency/CVE catch-up** (29 of 62 are CVE fixes; "7 weeks behind on the upstream
security baseline" is a bad slide); allocation gates + `noGcTest` catch a regression; only
`kotlin-stdlib 2.3.20→2.4.10` is a minor. **Runs in parallel with 03 as long as they're never on the same
branch at once** (the rebase rewrites `build.gradle`).

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
