# RESULT: full verification sweep — 2026-07-26

Everything runnable without a GKE cluster was run: every automated suite, every kind proof script
(including the 4 new ones written today), on the live `kind-traderx-state-014` and
`kind-traderx-yu12-cluster` rigs. **Final state: every suite and every kind proof PASS, except one
proof marked PARTIAL with its claim verified by other means (detailed below).** The GKE proofs,
all benchmarks, and the `dedicated` engine job are SKIPPED with reasons.

Per the interpretation discipline: **no timing or throughput number in this document** — kind
results are correctness PASS/FAIL only.

## A. Automated test suites — 7/7 PASS

| Suite | Result | Notes |
|---|---|---|
| Engine YU13 (`generate-state` + `engine-tests.sh hosted`) | **PASS** | exact CI path, run alone |
| Engine YU14 (same) | **PASS** | |
| Engine YU15 (same) | **PASS** | |
| Baseline Java (`scripts/ci/baseline-tests.sh`) | **PASS** | account/trade/position/trade-processor |
| reference-data (`npm test`) | **PASS** | |
| people-service (`dotnet test`) | **PASS** | |
| `TradeProcessorPersistenceIT` (`./gradlew integrationTest`, Testcontainers MariaDB) | **PASS** | |
| Engine `dedicated` job (3-node + timing + Epsilon gates) | **SKIPPED** | needs idle dedicated hardware; this host was running two kind rigs — a timing claim here would be a lie |

## B. Proof scripts (kind) — final state

### state-014 rig

| Proof | Result | Notes |
|---|---|---|
| `yu03-risk-demo controls` | **PASS** | |
| `yu04-live-delta` | **PASS** | after a script fix it exposed: its exit code was the trailing *informational* metrics grep, not the catch-up verdict (a TIMEOUT could exit 0). Fixed + committed (`b23a387b`). |
| `yu04-offline-catchup` | **PASS** | |
| `yu05-auth-entitlements` / `recon` / `regulatory-reproducible` / `settlement` | **PASS** ×4 | |
| `yu10-fix-session` | **PASS** | 15,103 FIX order→ExecutionReport lifecycles, read model grew to match. First runs failed on a real rig fault (below), not the feature. |
| `yu06-quality-gate`, `yu06-consumer-halt`, `yu08-algo-slicing`, `yu13-readmodel-effect-end` | **PASS** ×4 | the new proofs, green earlier today (see their commits) |

### yu12-cluster rig

| Proof | Result | Notes |
|---|---|---|
| `yu13-clordid-suppression` | **PASS** | after retargeting its SQL at the bridge DB (`8d41491b`) |
| `yu13-cancel-ingress` | **PASS** | full before/after gateway roll; epoch survived, members never bounced |
| `yu13-stp-and-replace` | **PASS** | all 9 steps incl. pre-change falsification arms (wash trade books on the old engine, `/replace` 404s), run on a fresh epoch created on `yu15-pre` |
| `seed-option-chain` | **PASS** | 24 contracts seeded, option cross smoke-booked |
| `yu15-risk-extract` | **PASS** | full acceptance: EOD chain with 24 options quality-OK → extract for sequence N, byte-identical, quiesced, write-once, and re-rendered identically by a restarted member |
| `yu15-option-persistence` | **PARTIAL** | steps 1–3 (the migration mechanics: narrow a populated volume to the pre-YU15 schema, prove the shipped `900-migrations.sql` widens it in place) **PASS**. Step 4's before/after arm is **not reproducible on this rig**: the proof assumes its migration testbed (`eod-price-db`) is the same database the trade bridge writes (`database` here), so its "option trade lands intact" assertion reads the wrong DB. The claim itself was **verified directly**: an OCC-symbol option cross landed **2 rows in the bridge DB** post-migration (`yu15-option-cross-direct:ROWS=2`). Making the proof two-DB-aware is a small follow-up. |

## C. GKE proofs & benchmarks — SKIPPED (needs a cluster)

`yu12-gke-{recovery,failover-transparency,cross-epoch-idreuse,restore-from-gcs}.sh`,
`yu13-gke-replace-proof.sh`, `failover-nodeclock.sh`, and **all** of `scripts/bench/` (load ladder,
latency probes, TAQ replay): **SKIPPED-needs-cluster** — GKE is at zero nodes. Run at the next
bring-up (coordinate compute credits). Benchmarks additionally must never be run for numbers on
kind.

## What the sweep itself caught (real faults, all fixed)

The first-run failures were never the features — every one was rig drift or a proof defect, which
is exactly what a verification sweep is for:

1. **trade-processor OOM under the recon sweep** (state-014): default JVM heap = 25% of 1Gi
   (~256MB) vs a 1.59M-row `trades` table. Live-patched to `MaxRAMPercentage=75` + a 2Gi limit;
   the manifest fix landed repo-wide from a parallel session (3 spec layers, all branches — YU15
   commit `a0a06e30`).
2. **position-service EOD consumer dead** (state-014): missing `NATS_BROKER_HOST`, dialing
   localhost forever. Spec already carried the fix; live deploy patched.
3. **EOD DB split-brain** (yu12-cluster): trade-processor writes `database`, position-service read
   `eod-price-db` → every close published fine while the P&L consumer saw an empty snapshot table
   and halted every account. Repointed position-service to `database`.
4. **Stale `database-init-sql` ConfigMap** (yu12-cluster): the deployed `900-migrations.sql` had
   **zero** MODIFY statements (pre-dates the YU15 OCC fix) — the option-persistence proof caught
   the *shipped artifact* being stale, its whole reason to test the ConfigMap. Refreshed from the
   current render and applied to the `database` deploy (the VARCHAR(15) OCC blocker was live there).
5. **The no-gradual-roll rule, re-confirmed the hard way**: rolling the members from
   `yu13-fixstatus` to the freshly built YU15-tip image (`yu15-sweep`) member-by-member left the
   cluster with **no leader** (election wedged at INIT). Recovery: PVC-wipe fresh epoch. A
   deterministic-core boundary requires wipe-and-replace, exactly as documented on 2026-07-22.
6. **Proof defects**: `yu04-live-delta` exit-code bug; `yu13-{clordid,stp}` asserting the wrong DB;
   stp preflight `>` vs `>=` off-by-one (a *healthy* same-epoch bridge has engine counter == SQL
   max). All committed.

## Rig state after the sweep

- `kind-traderx-yu12-cluster`: **torn down** (members + gateway scaled to 0) per request; the
  StatefulSet is parked on `traderx/cluster-node:yu15-sweep` (fresh YU15-tip build, includes
  readmodel + options + risk-extract) with wiped PVCs and a cleared read model — next bring-up is
  a clean current-image epoch.
- `kind-traderx-state-014`: left running (it predates this session); carries the heap +
  `NATS_BROKER_HOST` fixes.
- GKE: untouched, zero nodes.
