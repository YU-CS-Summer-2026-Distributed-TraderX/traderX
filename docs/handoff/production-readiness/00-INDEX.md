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
| [01](01-upstream-rebase-spike.md) → [FINDINGS](01-upstream-rebase-spike-FINDINGS.md) · [EXPERIENCE](REBASE-EXPERIENCE.md) | **Upstream rebase — ✅ DONE + PUSHED 2026-07-24. All 15 branches (YU01→YU15) merged + CVEs landed + suites green (269/283/300 intact), ON ORIGIN (ahead=0).** `lmax-sequencer-no-gc` left as-is (superseded alias). Follow-ups (non-blocking): conform 3 PR-only governance gates; log the YU01 009b RUN_FROM_GENERATED.md hunk-drop in `issues/` (intentional per-branch divergence — do NOT re-sync). | done + pushed |
| [02](02-test-coverage-inventory.md) → [MAP](02-RESULT-coverage-map.md) | Test-coverage inventory — **✅ DONE 2026-07-24** (`e3225c4f`) | **853 tests, all operator-driven, ZERO in CI** | done |
| [03](03-baseline-unit-tests.md) → [RESULT](03-RESULT-baseline-unit-tests.md) | Unit tests for the plain-vanilla TraderX baseline — **✅ DONE 2026-07-25** (`2759cfd6`, `ed78fbac`, `a8c81017`). **0 → 48 executable tests across all 6 baseline services** (Java: account 8 / trade 5 / position 5 / processor 7; NestJS reference-data 7; .NET people-service 16). Java root cause = the `src/main/test/java` NO-SOURCE silent-pass trap; TS uses `node:test` via ts-node (no jest added), .NET uses xUnit + hand fakes (no Moq). 3 CI jobs (`baseline`, `baseline-reference-data`, `baseline-people-service`). Deferred to brief 04: per-state composed-tree matrix. | ✅ done + GREEN IN CI (run #9) |
| [04](04-milestone-and-integration-tests.md) → [CI RESULT](04-RESULT-ci-bringup.md) · [STRATEGY](04-RESULT-test-strategy.md) | **CI wiring ✅ DONE + GREEN** (`4abcfdf2`, badge live). **Matrix extended to YU13/YU14 ✅ 2026-07-25** (`9bec0cb6` + branch commits `ff9afc29`/`b145e5d5`): all 3 legs GREEN IN CI on GitHub 2026-07-27 (run #9 `f6cf4255`: YU13 229s / YU14 226s / YU15 218s) — locally 270/284/298, 0 fail. **Test-strategy statement ✅ written** (04-RESULT-test-strategy.md — 3 tiers + proof→in-process-test map; the required deliverable + a slide). **Part B first integration test ✅ DONE 2026-07-25** (`de8daae4`): `TradeProcessorPersistenceIT` — real `TradeService.processTrade` vs a **real MariaDB (Testcontainers)** on the **deployed configmap schema** (ddl-auto=none, FK + CHECK), incl. the headline "unknown account → FK loud-fail, not silent drop". 3/3 green locally; `@Tag("integration")` isolates it so the fast unit job stays Docker-free; new `integration-trade-processor` CI job. Part A milestone units substantially satisfied by the existing 853 + 48 in-process tests (verify-not-rewrite per brief). **Remaining (optional): more seams (order→readmodel→REST, limit→reject) via the same Testcontainers pattern.** **⚠️ CI radar (pre-existing): earlier states' order-matcher has ~12 `@SpringBootTest` context-load tests that RED under bare `./gradlew test` — a composed-tree matrix past YU13–15 needs a DB service / Testcontainers / H2-DDL fixture.** | Part B pending infra call | 04-CI done |
| [05](05-opentelemetry-observability.md) | OpenTelemetry + observability platform, **async** | high | nothing — **start in parallel** |
| [06](06-kdb-journaling-playback.md) | **KDB-X tick store — LOCAL HALF DONE 2026-07-27** (`4923112e` + `a5d49977` on `YU07-historical-tick-store`, **unpushed**). **Headline: KDB-X Community reads the existing ZSTD Parquet NATIVELY — there is no conversion step and none is needed; the store IS the corpus in GCS.** 16 GiB cap is not binding either (768 MiB peak over 47.8M quotes). Shipped ~320 lines under `specs/YU07-.../runtime-overrides/tick-store/kdb/`: `tickstore.q` (quote/trade tables + VWAP/spread/session/replay), `selfcheck.q`, `fetch-sample.sh`, README. **Gate: 17/17 green, every expected value cross-computed in DuckDB (cross-implementation, not self-agreement) — re-run and verified by the coordinator.** Sample 310 MB / 17 files (2 days × AAPL/MSFT/SPY/CROX). Aeron-vs-kdb journal senses kept separate in code, README and commit. **Remaining: the off-consensus leader-side tap** (beside `TradeNatsPublisher`/`OrderNatsPublisher`, best-effort + visible drop signal, never in the apply path) — needs a running cluster. | medium | local half done; tap needs a cluster |
| [07](07-risk-integration-with-alex.md) | Risk-component integration with Alex | high (blocks another person) | a design session with Alex |
| [08](08-github-io-branding.md) | YU-branded github.io site | low ("if you have time") — but Dov wants it | sync with Dov |
| [PROOFS](HANDOFF-new-proof-scripts.md) | **New falsifiable proof scripts — WRITTEN 2026-07-26 (7/7), kind set GREEN.** kind: `yu13-readmodel-effect-end` + `yu06-quality-gate` + `yu06-consumer-halt` (recovered from the YU06 worktree's untracked demo scripts, hardened) + `yu08-algo-slicing` — **all run green** on the live rigs (the consumer-halt run found + fixed real rig drift: position-service missing `NATS_BROKER_HOST`, EOD consumer dead on localhost; also a trade-processor heap OOM at default 25%-of-1Gi under the 1.6M-row recon sweep — heap bumped live, manifest fix flagged as a follow-up). GKE: `yu12-gke-{recovery,failover-transparency,cross-epoch-idreuse,restore-from-gcs}.sh` **authored against the known topology, not yet run** — cluster is torn down; run them at next bring-up (coordinate credits). All 8 cataloged in `scripts/proofs/README.md` + the test-strategy proof→test map. **Full verification sweep ✅ DONE 2026-07-26** ([RESULT](RESULT-full-verification-sweep.md)): 7/7 automated suites PASS, every kind proof PASS (option-persistence PARTIAL — two-DB rig limitation, claim verified directly), 6 real rig/proof faults found + fixed by the sweep itself (tp heap OOM, dead EOD consumer, EOD DB split-brain, stale migrations ConfigMap, a no-gradual-roll wedge, 3 proof defects). **GKE portion RUN 2026-07-26 same-day bring-up: all 6 GKE proofs PASS live** (recovery byte-identity + rebuilt-member-leads, failover 588-acks=588-delta zero loss/dup, cross-epoch no-reuse, DR restore byte-equal to backup point, replace re-proven; nodeclock mechanics pass / its timing capture defective on busybox — flagged). Benchmarks BANKED (07-23/24), not rerun by design. Cluster state VERIFIED 2026-07-27: **control plane RUNNING, ZERO nodes / zero compute instances** (the earlier "left UP (8 nodes)" line was stale). Burn is the GKE control plane only, ~$2.40/day; node pools exist at 0 nodes and cost nothing. Teardown deliberately deferred by yaakov 2026-07-27. | high (talk credibility) | GKE runs need a cluster + credits |

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

**⚠️ REBASE-LANE TODO — three inherited upstream CI gates need a conform-vs-retire call.** The first push
woke up upstream's own spec-kit governance workflows (`spec-kit-root-gates`, `runtime-script-parity`,
`docs-spec-sanity`, last touched by upstream `9f8dda57`). They **fail** against our 14-state fork — they
lint upstream doc/spec conventions our states don't follow (concrete first failure: `specs/YU02-*/README.md`
heading must be `# Feature Pack YU02:` / `# YU02 ...`; more behind it). They are part of the "29
generator/publish-gate plumbing" commits this spike counted. **Scoped to PR-only for now** (`1a90a2f6`) to
stop per-push alert spam. **Decision for the rebase lane: CONFORM (lean) vs retire.** Lean conform — *"our
fork passes upstream TraderX's own spec-kit governance gates"* is a strong FINOS slide, and it's mechanical
doc-convention cleanup across the states. Retire only checks that validate upstream's *publishing registry*
(which we don't use).

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
- **Achievement-focused framing in decks AND docs** (updated 2026-07-27, yaakov). Do **not** volunteer
  caveat / limitation / "what's missing" sections nobody asked for. Still required: never assert
  something known to be false, and keep real blockers/open items in **handoffs** (they exist to
  transfer state and are useless if hollow).
- Quote **ratios and the commit number**, never a single run's absolute client RTT (~1.5–2× run-to-run
  variance at fixed config).
