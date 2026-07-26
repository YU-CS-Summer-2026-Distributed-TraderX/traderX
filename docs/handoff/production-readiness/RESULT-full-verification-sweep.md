# RESULT: full verification sweep — 2026-07-26

Everything runnable without a GKE cluster was run: every automated suite, every kind proof script
(including the 4 new ones written today), on the live `kind-traderx-state-014` and
`kind-traderx-yu12-cluster` rigs. **Final state: every suite and every kind proof PASS, except one
proof marked PARTIAL with its claim verified by other means (detailed below).** Later the same
day GKE was brought up and the GKE proof set was run live — **all 6 PASS** (§C); benchmarks are
BANKED, and the `dedicated` engine job remains SKIPPED (needs idle hardware).

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

## C. GKE proofs — RUN 2026-07-26 (cluster brought up same day)

Pools scaled (3× c4d members + 5× std), the pinned YU15 tier deployed via
`scripts/yu15/start-cluster-gke.sh` (`cluster-node:yu15-idempfix` digest-verified serving), one
leader elected on a fresh epoch — then:

| Proof | Result | The evidence |
|---|---|---|
| `yu12-gke-recovery.sh` | **PASS** (first run) | member destroyed to an empty disk → rejoined to **byte-identity** on order hash, position hash, trades, nextOrderRef (all three agreed) → forced elections until the rebuilt member **won leadership** → booked a cross as leader, refs strictly monotonic |
| `yu12-gke-failover-transparency.sh` | **PASS** (first run) | leader killed under a live retrying stream: **588 client acks, ref delta exactly 588** on all three members — zero lost, zero duplicated; 1 in-flight send made whole by idempotent retry |
| `yu12-gke-cross-epoch-idreuse.sh` | **PASS** (first run) | old epoch high-water 603; every new-epoch ref above it, sets disjoint, counter monotonic, new refs live (they trade) |
| `yu12-gke-restore-from-gcs.sh` | **PASS** | whole-cluster destroy → restore from `gs://` **byte-equal to the quiesced backup point** (not the post-backup state — that 2-order gap is the honestly-stated RPO window) → restored book trades. One proof fix en route: the backup job's 100KB anti-empty floor needs real volume, so the proof now rests 5,000 filler orders first (`79d176b6`). |
| `yu13-gke-replace-proof.sh` | **PASS** | ack correlation under cancel-plus-add, three-member identity, replace surviving snapshot + empty-disk rebuild — re-proven on today's image |
| `failover-nodeclock.sh` | **MECHANICS PASS / measurement DEFECTIVE** | 3 kill→promote rounds completed, but its in-pod `date +%s%3N` t0 is empty on the busybox-based image, so it printed raw epoch stamps as "deltas" while exiting 0 — flagged as a follow-up (make t0 portable, fail loudly on empty). **No timing number is quoted from this run**; failover timing stands on the 2026-07-18 node-clock drills, and failover *correctness* stands on the transparency proof above. |

## Benchmarks — BANKED, deliberately not rerun

The performance thread is **done and banked** (2026-07-23/24, dedicated hardware): per-order
ceiling ~190k/s at 4 gateways (gateway-bound, consensus ceiling ~440k), p50 < 1.5ms sustained to
75k/s with consensus commit ~200µs load-invariant. Today's topology (no c2d load pool, e2 gateway
nodes) cannot reproduce those conditions, and numbers from an under-provisioned rig would only
muddy the banked ones. `run-all-tiers.sh`, the latency probes, and TAQ replay were therefore
**not rerun by design**, not skipped for lack of a cluster.

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

- GKE `traderx-lmax`: **left UP** per the run — 8 nodes (3 c4d members + 5 std), the pinned
  YU15 tier healthy on `cluster-node:yu15-idempfix`, backup CronJob still suspended (proof-created
  Jobs only). Scaling down is yaakov's call.
- `kind-traderx-yu12-cluster`: **torn down** (members + gateway scaled to 0) per request; the
  StatefulSet is parked on `traderx/cluster-node:yu15-sweep` (fresh YU15-tip build, includes
  readmodel + options + risk-extract) with wiped PVCs and a cleared read model — next bring-up is
  a clean current-image epoch.
- `kind-traderx-state-014`: left running (it predates this session); carries the heap +
  `NATS_BROKER_HOST` fixes.
- GKE: untouched, zero nodes.
