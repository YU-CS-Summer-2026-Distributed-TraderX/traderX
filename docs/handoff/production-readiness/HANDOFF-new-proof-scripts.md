# Handoff: write the missing falsifiable proof scripts (kind + GKE)

> Extends brief 04's Tier-2 proof coverage. Home: `traderX-YU15-eod-risk-extract` on branch
> `YU15-eod-risk-extract`. Author the scripts in `scripts/proofs/`, house style below. Commit,
> never push (push goes to yaakov).

## Goal for next chat

Write the **7 falsifiable end-to-end proof scripts that don't yet exist** but should, to close the
named gaps in the correctness story for the FINOS talk. Each drives the *deployed* system and prints
explicit ✔/✘ per step (Tier 2 of `04-RESULT-test-strategy.md`). Three are kind-runnable; four require
GKE because kind can't credibly carry 3-member consensus timing, real failover, or cloud storage.

## The proofs to write

### kind-runnable (correctness — no timing/cloud dependency)

| # | Script (suggested name) | Falsifiable claim | Existing CI unit counterpart |
|---|---|---|---|
| 1 | `yu13-readmodel-effect-end.sh` | Place → match → egress → SQL `orderbook` read model → `GET /accounts/{id}/orders` shows the order NEW, then a CANCEL makes it leave the open set / show CANCELED. **Assert at the SQL effect-end, not the 200.** | `ProjectorHandlerTest` |
| 2 | `yu06-quality-gate.sh` + `yu06-consumer-halt.sh` | Inject stale/spike/missing prices → EOD publish is **blocked** → override → publish succeeds; and: exclude a held security from the universe → the P&L consumer **halts fail-safe** (0 rows, not partial). | EOD unit tests |
| 3 | `yu08-algo-slicing.sh` | A parent order under a TWAP/execution algo emits **N child orders across the schedule** (count + timing bounded), all booking on the cluster. | `AlgoEventStoreReplayTest` |

### GKE-required (resilience & DR — kind is at its limit; see `yu13-gke-replace-proof.sh` for the pattern)

| # | Script (suggested name) | Falsifiable claim | Notes |
|---|---|---|---|
| 4 | `yu12-gke-recovery.sh` | Kill a member → it **rejoins from an empty disk** → all 3 members are **byte-identical** (order-book + position hashes) → the rejoined node can **later become leader**. | The system's strongest correctness story; today only a skill + unit tests, no committed script. |
| 5 | `yu12-gke-failover-transparency.sh` | During a leader kill under live REST/FIX load, in-flight clients see **zero lost and zero duplicated orders** (verified at the `next_order_ref` delta, not client 200s). | `failover-client-probe` (bench) measures *timing*; this is the pass/fail correctness proof. |
| 6 | `yu12-gke-cross-epoch-idreuse.sh` | After a failover, `next_order_ref` **never reissues an id from the prior epoch** — no id reuse across epochs. | Can fold into #4/#5 if cleaner. |
| 7 | `yu12-gke-restore-from-gcs.sh` | Tear the whole cluster down → bring it back with `RESTORE_FROM_GCS=1` → state (book, positions, next id) is **intact** from the last GCS snapshot. | The DR proof. Backup cronjob `yu12-snapshot-backup` exists (currently suspended); no restore proof. |

## What this chat accomplished (context)

- **Reorganized `scripts/`** (commits `de8b3096`, `b4ed761f`): moved the 17 existing falsifiable proofs
  (+ `seed-option-chain.sh` + `yu05-common.sh`) into **`scripts/proofs/`** with a catalog README, and
  split the benchmarks into `scripts/bench/{load,latency,replay}/`. **New proofs go in
  `scripts/proofs/` and must be added to `scripts/proofs/README.md`** (the catalog: script → claim →
  CI-counterpart, grouped by capability).
- **Brief 04 core is done**: CI matrix extended to YU13/YU14, a test-strategy statement written
  (`04-RESULT-test-strategy.md` — three tiers + the proof→in-process-test map), and the first
  cross-service integration test (`TradeProcessorPersistenceIT`, Testcontainers MariaDB) landed.

## Key files

| File | Why it matters |
|---|---|
| `scripts/proofs/` | Where these scripts go. Read 2-3 existing ones first (see house style). |
| `scripts/proofs/README.md` | The catalog — **add a row per new script** (grouped by capability, with its CI counterpart). |
| `scripts/proofs/yu13-stp-and-replace.sh`, `yu13-cancel-ingress.sh` | Best templates: ✔/✘ helpers, effect-end SQL assertions, `next_order_ref` ground truth. |
| `scripts/proofs/yu13-gke-replace-proof.sh` | The template for a **GKE** proof (what kind can't carry, and why). |
| `scripts/proofs/yu05-common.sh` | Shared bash helpers the yu05 proofs source. |
| `docs/handoff/production-readiness/04-RESULT-test-strategy.md` | The tier framing; add these to the proof→test map when done. |
| `docs/handoff/production-readiness/00-INDEX.md` | The board — update it when proofs land. |

## Architecture / context the next chat needs

- **House style (from the existing `yu13-*` proofs):** `set -uo pipefail`; one readable line per step
  (`printf "   %-26s %s\n" label result`); end states marked ✔/✘; helper fns (`order()`, `ctl()`);
  `curl -s -m8` + python3 one-liners for JSON (not jq — parse failures must degrade quietly); poll
  loops with explicit timeouts, not sleeps-and-hope; time-based ids (`Z$(date +%s | tail -c 5)`) so
  reruns don't collide; reach the order-matcher via the edge-proxy when the demo restarts the pod.
- **Ground truth for anything order-count-related is the member `traderx_cluster_next_order_ref`
  delta — never a gateway "accepted"/booked counter.** A 200 from the gateway has repeatedly meant
  nothing booked. Assert at the **effect end** (SQL read model / member metrics).
- **kind vs GKE:** for correctness-only cluster work on kind, set `CLUSTER_IDLE_SLEEP_MS=1` (makes the
  rig usable but disqualifies any timing/throughput claim). The 3-member timing/failover proofs need
  real GKE hardware.
- **GKE bring-up traps** (memory `issue_gke_per_order_bench_bringup`): pd-standard boot disks for load
  pools (SSD_TOTAL_GB=500 is the binding quota); bring an untainted pool up alongside the tainted
  member pool or CoreDNS/konnectivity starve; `RESTORE_FROM_GCS=1` is gated (default off = empty boot).
- Much of the resilience story (3-member identity, snapshot+rebuild survival, read-model effect-end,
  gs:// extract, failover transparency) was **proven live on GKE in past sessions** — the mechanics are
  known; the gap is that they were never captured as re-runnable committed scripts.

## Decisions already made (don't re-litigate)

- **Proofs live in `scripts/proofs/`** (not `scripts/bench/`), YU15-tip only. Benchmarks (which produce
  a *number*) stay in `scripts/bench/`; proofs produce a *verdict* (✔/✘).
- **Assert at the effect-end**, ground-truth = `next_order_ref` delta. Non-negotiable — it's why the
  existing proofs are trusted.
- **kind for correctness, GKE for timing/failover/DR.** Don't try to make timing claims on kind.
- Scripts are self-contained, operator-run, documented in their own header + the catalog README.

## Open questions / known issues

- **YU06 proofs may already exist on the YU06 branch.** The demo-prep notes claim
  `yu06-{versioning,quality-gate,chain-e2e,consumer-halt}.sh` were built, but they are **not committed
  in the YU15 worktree**. Check `git show YU06-eod-price-production:scripts/bench/…` (old flat path)
  before writing #2 from scratch — recover-and-propagate may be cheaper than rewrite.
- **GKE cluster is currently torn down** (0 nodes, per memory). The GKE proofs (#4–#7) need a cluster
  stood up — coordinate with yaakov on bring-up + compute credits before running them; they can be
  *written* against the known topology now and *run* once a cluster is up.
- Whether #6 (cross-epoch id reuse) is its own script or folded into #4/#5 — author's call.
- These are **YU15-tip only**; propagating to owner branches (YU06/YU08/YU12) is a later decision.

## Suggested first steps for next chat

1. Read this doc, then read `scripts/proofs/yu13-stp-and-replace.sh` + `yu13-cancel-ingress.sh` +
   `yu13-gke-replace-proof.sh` to absorb the house style and the effect-end/ground-truth discipline.
2. Start with **#1 `yu13-readmodel-effect-end.sh`** — cheapest, kind-runnable, closes an explicitly
   named gap, and the read model + `GET /accounts/{id}/orders` was already live-proven so the
   mechanics are known. Get it green on kind.
3. Then #2 (check the YU06 branch first) and #3 on kind.
4. Author #4–#7 against the known GKE topology; run them once a GKE cluster is up (coordinate with
   yaakov). #4 (recovery correctness) is the talk's money demo — prioritize it among the GKE set.
5. Add each landed script to `scripts/proofs/README.md` and note it in `00-INDEX.md`. Commit per
   proof; never push.
