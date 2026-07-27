# RESULT: full verification sweep — 2026-07-26

Everything runnable without a GKE cluster was run: every automated suite, every kind proof script
(including the 4 new ones written today), on the live `kind-traderx-state-014` and
`kind-traderx-yu12-cluster` rigs. **Final state: every suite and every kind proof PASS, except one
proof marked PARTIAL with its claim verified by other means (detailed below).** Later the same
day GKE was brought up and the GKE proof set was run live — **all 6 PASS** (§C); the benchmarks
were then RERUN on an improved 6-gateway topology (§Benchmarks) and the `dedicated` engine job
was rerun on the quieted host — **PASS**. Nothing on the original board remains open.

Per the interpretation discipline: **kind results are correctness PASS/FAIL only** — every
timing/throughput number below comes from the GKE runs.

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
| Engine `dedicated` job (3-node + timing + Epsilon gates) | **PASS** (2026-07-26, second pass) | initially SKIPPED while two kind rigs burned the host; rerun once the host was quiet — all 4 allocation gates + the 3-node suite + `noGcTest`, exit 0 |

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
| `yu15-option-persistence` | **PARTIAL on kind → PASS on GKE** | steps 1–3 (the migration mechanics: narrow a populated volume to the pre-YU15 schema, prove the shipped `900-migrations.sql` widens it in place) **PASS**. Step 4's before/after arm is **not reproducible on this rig**: the proof assumes its migration testbed (`eod-price-db`) is the same database the trade bridge writes (`database` here), so its "option trade lands intact" assertion reads the wrong DB. The claim itself was **verified directly**: an OCC-symbol option cross landed **2 rows in the bridge DB** post-migration (`yu15-option-cross-direct:ROWS=2`). Making the proof two-DB-aware is a small follow-up. **Superseded same day: the full proof PASSES unmodified on the GKE tier** (single-DB — both writers point at `eod-price-db`), including the step-4 arm: OCC option lost to the narrowed schema, shipped migration applied, 19-char option trade landing intact in `trades` + `positions`. The kind two-DB split was the anomaly, exactly as diagnosed. |

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
| `failover-nodeclock.sh` | **PASS (fixed twice), measurement decomposed** | First run: the busybox `date +%s%3N` t0 came back empty and the script printed raw epoch stamps while exiting 0 — fixed (`af7d3736`: portable second-edge t0, hard-fail on garbage; then `42ce06b5`: configurable `BENCH_RUNNER_POD` + member fallback + the round-spacing trap documented). Clean measured round under a 5k/s pump: **raft election 133ms** (the promoted member's own CANVASS→ROLE-CHANGE stamps — pure node-clock), consistent with the 07-18 drills and the live rung-A timeouts (50/200/100/25 verified in the serving pods). The script's own `electionMs 2576 / servingMs 7711` additionally include ~2.4-2.6s of `kubectl delete --force` kill-delivery latency (kubelet — harness, not system). **The "~5s gateway re-establishment" was chased to a real gateway bug and fixed the same night** (`f92be1ab`): the gateway never sent a cluster-session keepalive, so every IDLE gateway's session was expired and rebuilt every ~5-10s — a one-clock observer caught all quiet gateways flapping `/ready` 503 continuously, and a failover sampled mid-flap (or with a 40k/s backlog to drain) read as seconds of outage the election never caused. After the fix (`sendKeepAlive` 1s idle + reconnect gate 100ms; gateway-only roll to `cluster-node:yu15-keepalive`, digest `a3b8dd28`): 90s quiet watch = **zero flaps**, and a live leader kill under the same observer = **zero flaps, zero failed orders at 100ms cadence, election 141ms, FIRST-APPLY +206ms**. The honest client-visible failover claim: a continuously trading client never saw a single failed order. |

### Gateway keepalive fix — propagation state

The fix lives in each branch's own gateway layer, so it needed a real sweep rather than one commit:
YU15 `f92be1ab` (origin, GKE-verified) → YU13 `1e96e816` → YU14 `eceb6968` (both byte-identical,
md5 `15e2c117…`, engine suites green) → **YU12 `10c49a72`**, which predates the pipelined-ingress
rewrite of this file and so was **adapted to its own `ownerLoop()`** rather than copied — same bug,
same fix shape (suite 211/0). An initial read of "YU12 doesn't carry this path" was wrong: it lacks
the *YU13-layer* file but has its own copy, and that copy had the bug.

Deliberately left alone: YU13/YU14/YU15 each still carry an unfixed **YU12-layer** copy of
`ClusterGatewayMain.java`. It is a dead layer — shadowed at render time by their own YU13-layer
copy — so it has no runtime effect. Noted here and in the YU12 commit so nobody "fixes" a file
that never executes, and so nobody mistakes it for a missed propagation.

## Benchmarks — RERUN 2026-07-26 on GKE (6 gateways), banked numbers left untouched

**Topology** (the "as many gateways as quota allows" run): 3× c4d-standard-8 members +
**6× c2d-standard-4 PRIVATE gateway nodes** (one gateway per node, hard anti-affinity) +
1× c2d-standard-8 dedicated load-generator node + 3× private e2-standard-2 for every support
service — 62/64 `CPUS_ALL_REGIONS`, 4/8 `IN_USE_ADDRESSES`. The unlock: **private nodes consume
zero external addresses**, so the 8-address quota (which capped last week's rig at 7 public nodes
/ 4 gateways) stops binding entirely; the real ceiling is now the 64-vCPU project quota, and a
quota bump is the only thing between this rig and 8+ gateways.

### Per-order throughput (binary ingress, `bin-multi.mjs`, member `nextOrderRef` delta over a 20s steady window)

| offered | load pods | committed/s | client p50 | verdict |
|---|---|---|---|---|
| 20k/s | 1 | 19,972 | 8ms | warmup, exact 4-way gateway split |
| 200k/s | 4 | 195,347–195,748 | ~32ms | tracking offered, reproduced twice |
| **250k/s** | **5** | **259,211** (lowpark: 257,919) | ~35ms | **the 6-gateway ceiling point** |
| 300k/s | 6 | 49k | — | past the knee and/or loadgen-bound — **not quotable** |

**Headline: 259k/s per-order committed at 6 gateways vs the banked 190.3k at 4** — the parked
"more gateways" lever, now measured: still roughly the ~47k/s-per-gateway linearity, still
gateway-bound, no member ceiling in sight.

### Latency (CO-safe `rest-latency-probe.mjs`, in-cluster, seeded ticker, committed orders — `ok`=all)

| offered (1 gateway) | p50 | p99 | p99.9 |
|---|---|---|---|
| 1k/s | 2.0ms | 6.3ms | 7.1ms |
| 5k/s | 2.3ms | 6.1ms | 7.5ms |

Consistent with the banked ~2ms story. A single gateway's REST per-order path saturates between
5k and 15k/s — beyond that a single-probe run measures its own queue (client p50 seconds), which
is load-spreading across gateways, not a cluster limit.

### The lowpark A/B (and the manifest gap it exposed)

`CLUSTER_IDLE_STRATEGY` was implemented (`ClusterNodeConfig`, YU13 layer, inherited into YU15's
render) **but never set in any deployment manifest in the repo** — every cluster tier to date ran
Aeron's default 1ms-park backoff while the LATENCY-01 "ship lowpark" verdict lived only in docs.
Fixed for both YU15 GKE statefulsets (`6f37a834`; deliberately NOT kind, where
`CLUSTER_IDLE_SLEEP_MS` must stay in charge). Measured A/B on this rig: **neutral** — latency
1k/s p50 2036→2002µs, 5k/s 2285→2299µs; throughput 259,211→257,919/s (all within noise). That is
*consistent* with the banked decomposition (transport ~70% of RTT, consensus ~24%): on this
transport-bound path a 1ms→1µs park has nothing to bite on. **Decision: keep lowpark** — never
worse, measured 2.4× better when consensus is on the critical path, 0.34 core cost — so the
config is already right when the transport levers (compact placement) land. This build exposes no
commit-time metric, so the banked ~200µs consensus commit was not re-measurable here.

### Methodology findings (each one initially produced a wrong number)

1. A single Node.js generator caps at **~76k/s** — beyond it, "latency" is the generator's own
   send backlog (p50 6s at 120k scheduled). Multi-pod with per-pod rates under the cap.
2. The `nextOrderRef` scrape (`kubectl exec` + wget) **times out under load** and an empty read
   masquerades as a throughput collapse — retry-any-member until numeric (`/tmp/ref-probe.sh`).
3. The latency probe against an **unseeded ticker measures reject latency**, `ok=0` — seed first.
4. Multi-pod floods sharing one ticker/account pair hit position caps and book nothing —
   distinct tickers per pod.
5. `failover-nodeclock` needs a stamping pod and **spaced rounds**: back-to-back kills measure a
   degraded cluster, and heavy backlog inflates `servingMs` with queue-drain time.

**Not rerun**: TAQ replay (needs curated data prep), the FIX-ingress rate ladder, and the JSON
batch ladder (`run-gke-bench.sh` reads the old order-matcher deployment's metrics, not this
gateway tier — porting it is a follow-up). Their banked numbers stand unchanged.

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
