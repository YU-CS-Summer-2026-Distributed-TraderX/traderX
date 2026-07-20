# YU12 Aeron Cluster — GKE failover measurement (2026-07-18)

Deployed the 3-member cluster + gateway to the real GKE cluster (`traderx-lmax`, `us-east1-b`) and
measured failover: is it off the k8s control plane, and how fast (client- and system-facing)?

## Deployment

- Image `us-east1-docker.pkg.dev/traderx-501015/traderx/cluster-node:yu12` (**linux/amd64** —
  the arm64 Mac build fails to pull on GKE; build with `YU12_PLATFORM=linux/amd64`).
- 3 members on **2** `c2-standard-4` blp-pool nodes (preferred anti-affinity packs 3-on-2). Node
  count is capped by the project's **C2_CPUS quota = 8** (3 nodes would need 12); a quota bump is
  required for one-member-per-node (true node-fault tolerance). Pod-kill failover is unaffected.
- Gotchas fixed live: blp-pool nodes carry a `workload=blp:NoSchedule` taint (needs a toleration);
  and the tainted-only topology left `kube-dns`/`konnectivity-agent` Pending for 26h — members
  crash-looped on peer DNS and logs/exec were blocked until `default-pool` was scaled to 1 for the
  system pods.
- `ClusterGatewayMain` deployed (Service `order-matcher-gw`, distinct from the existing YU11
  `order-matcher` Service); REST + FIX up and connected.

## Off the control plane?

**Yes, definitively.** Every failover elected a new leader among the surviving members with zero
Kubernetes involvement in the decision — Raft internal election, no Lease, no witness. k8s only
reschedules the killed pod afterward. Confirmed across ~8 leader kills.

## Failover speed — the consensus timeouts dominate, and they are the lever

The Aeron default `leaderHeartbeatTimeout` is **10 s**, so with defaults failover is ~10–12 s on
ANY hardware — confirmed on GKE (not a Docker-Desktop artifact):

| Config (heartbeat interval / timeout / election) | System-facing re-election | Client-observed outage |
|---|---|---|
| Aeron defaults (200ms / 10s / 1s) | **12.0 s** | 12.3 s |
| Tuned **100ms / 1s / 500ms** (fresh, settled) | **~2.0 s** (2.00, 2.02) | **202 ms** |
| Too-aggressive 50ms / 500ms / 300ms | 5.8 s (unstable) | 8.0 s |
| Tuned 1s config under config-thrash / rapid repeated kills | 12–29 s (churn, recovers) | — |

Zero order-ID reuse in every run.

### Readings

- **Client-facing failover is sub-second (~200 ms)** at the tuned 1 s config. The counterparty's
  order→ack stream barely hiccups: orders submitted during the election are held by the Aeron
  Cluster client and commit under the new leader, so acks resume in a smooth stream (max
  inter-ack gap 202 ms with a 200 ms submit cadence).
- **System-facing re-election is ~2 s** at the tuned config (≈1 s to detect the dead leader via
  the heartbeat timeout + ≈1 s election) — a 6× improvement over the 12 s default, reproducible on
  a fresh/settled cluster.
- **Sub-1s *system-facing* is not reliably delivered.** One lucky sample hit 1.04 s; the honest
  reproducible number is ~2 s. Pushing the heartbeat timeout to 500 ms made it WORSE (election
  instability / false-positive elections). Consistent sub-1s system-facing needs a dedicated
  stability investigation, not just tighter numbers.
- **Variance is real and setup-sensitive.** Rapid config changes + repeated kills churned the
  cluster to 12–29 s before it re-settled. The clean single-kill on a settled cluster is the fair
  measurement (~2 s).

## The tuning lever (shipped)

`ClusterNodeConfig` reads env (ms; 0 = Aeron default), so the sweet spot is tunable without a
rebuild — set on the StatefulSet:

- `CLUSTER_HEARTBEAT_INTERVAL_MS` (tried 100)
- `CLUSTER_HEARTBEAT_TIMEOUT_MS` (sweet spot ~1000; 500 destabilized)
- `CLUSTER_ELECTION_TIMEOUT_MS` (tried 500)
- `CLUSTER_STARTUP_CANVASS_TIMEOUT_MS`

## Bottom line

- Off the k8s plane: **yes**, proven.
- Client-facing (counterparty) failover: **~200 ms, effectively transparent** with tuning.
- System-facing re-election: **~2 s** with tuning (12 s default). Sub-1s system-facing is
  achievable-looking but not reliably delivered; it's a focused tuning/stability task.
- Correctness: **zero ID reuse** across every GKE failover.

## Open / next

- Find consistent sub-1s system-facing (heartbeat-timeout sweep between 600–1000 ms, understand
  the 500 ms instability, isolate PVC-reattach vs election-round variance).
- 3 nodes (quota bump) for true node-fault tolerance + the `run-gke-bench.sh aeron-cluster`
  throughput run against the 25,149 baseline (gateway already serves `/orders/batch` + `/metrics`).


## Consistency investigation (2026-07-18, same session)

The first pass saw a bimodal tail (occasional ~11-52s outages). Chasing consistency root-caused
ALL of it to test/measurement artifacts, NOT the cluster:

1. **Measurement bugs**: `settled()` first required applied-seq convergence read sequentially via
   the API proxy — at 20 orders/s the cluster advances 20-60 orders between reads, so the check
   never passed on a trading cluster (false "not settled"). Fixed to role-based (3 Running +
   1 LEADER + 2 FOLLOWERS = quorum restored). Also: killing the next leader before the previous
   member fully rejoined = a genuine 2-of-3-down (quorum loss) — correct long outage, not a bug;
   fixed by a real catch-up margin between kills (one-fault-at-a-time).
2. **The ~37s tail was the PROOF CLIENT, not the cluster.** The client's `connectCycling` blocked
   the full 5s messageTimeout on the just-killed endpoint each cycle, and the stall clock was set
   BEFORE that blocking call, so the stall re-fired the instant it returned — a reconnect churn
   that tore down each freshly-established session before acks could flow, for ~37s until the
   killed member's pod returned. The cluster had re-elected in ~1-2s the whole time (the client
   logged repeated `CONNECTED via <leader>` during the "outage").

Fixed the client (grace window starts AFTER connect completes; 1s message timeout so dead
endpoints fail fast) and re-measured, fresh cluster, one-fault-at-a-time, tuned 1s heartbeat:

```
kills=4 -> client-observed outages: 192ms, 2099ms, 2102ms, 2155ms, 3115ms
min=192ms  median=2102ms  max=3115ms  reuse=0
```

### Final characterization (GKE, emptyDir cluster dir, tuned 100ms/1s/500ms)

- **Off the k8s control plane: yes.**
- **Consistent + correct: yes** — no catastrophic outliers, 0 ID reuse across ~20 total kills.
- **Client-observed failover: ~2-3 s typical, best 192 ms.** The ~2 s floor is partly the client's
  own 2 s stall-before-reconnect threshold + the 1 s heartbeat-detection + ~1 s election.
- **Sub-1s: achievable in the best case (~200 ms), NOT the consistent floor.** Consistent sub-1s
  is bounded by two tuning knobs that both resist tightening: heartbeat-timeout below ~1 s caused
  election instability here, and the client reconnect threshold below ~1-2 s risks churn. Getting a
  consistent sub-1s floor is a focused stability/tuning task (heartbeat 600-800ms with a stability
  check; client reconnect threshold ~500ms with the grace fix; possibly the AeronCluster client's
  native leader-tracking instead of endpoint-cycling).

### emptyDir vs PVC (important operational finding)

Members on a **PVC** rejoin slowly on GKE (PVC detach/reattach when the replacement pod lands on
the other node = minutes), leaving a long degraded window. Switching the cluster dir to
**emptyDir** made rejoin fast (fresh-disk rejoin via consensus catch-up — durability is the Raft
quorum, not the pod's disk). emptyDir is the right choice for cluster members on GKE for fast
fault-tolerance recovery; pair it with periodic snapshots to durable storage if whole-cluster
restart durability is needed.

## Sub-1s system-facing: ACHIEVED (2026-07-19)

The earlier "500 ms heartbeat destabilizes" finding was an artifact of the then-broken
measurement stack (PVC rejoin + proof-client churn). With the clean stack, tighter timeouts are
stable and deliver consistent sub-1s re-election.

### Precise instrument

The health-poll harness has ~400-600 ms of built-in latency (pod-delete API round-trip + per-poll
proxy cost), too coarse near 1 s. Replaced with node-clock timestamps at both ends:

- Members log `ROLE-CHANGE role=... atMs=<epoch-ms>` from `onRoleChange` (shipped in the image).
- The member container runs the JVM under a `/bin/sh -c` wrapper (PID 1 is unsignalable from
  inside a PID namespace), so the harness can `kubectl exec` → print `date` ms on the LEADER's
  node clock → `kill -9` the java pid in one shot: a true crash timestamped with no k8s API
  latency. (`scratchpad/measure-system-facing-precise.sh`; GKE node clocks are NTP-synced.)
- System-facing = kill timestamp → survivor's `ROLE-CHANGE role=LEADER` timestamp
  (detection + election, the full system outage).

### Results (emptyDir, interval 100 ms, one-fault-at-a-time, settled between kills)

| heartbeat timeout / election | System-facing (precise) | Client-observed (proof client) |
|---|---|---|
| 1000 / 500 | — | 1554, 1588, 1605, 2598 ms |
| 800 / 400 | — | 1547, 1566, 2554, 2569 ms |
| 600 / 300 | 929, 977, 1070, 1417 ms | (client wedged, no data — below) |
| **400 / 200** | **653, 662, 665, 665, 716 ms** | 838, 892, 1575, 1657 ms |

- **400/200 is stable**: 4-minute idle soak with zero spurious role changes, then 9 further
  clean settles across the kill runs. Zero ID reuse everywhere.
- **System-facing is consistently sub-1s at 400/200**: 653-716 ms, tight spread (~650 ms ≈
  400 ms detection ceiling + ~250 ms election). The manifest now pins 400/200 as the default.
- Client-observed remains bimodal ~0.85 s / ~1.6 s: the extra ~0.8 s appears when the client's
  endpoint-cycling reconnect tries the dead endpoint first and burns its 1 s connect timeout.
  That is a property of the test client's reconnect strategy, not the cluster; native
  AeronCluster leader-tracking (newLeaderEvent) is the known fix if sub-1s *client* floor is
  ever required. Best observed client outage remains ~200 ms.

### Caveat: proof-client wedge during full rollouts

During a full 3-member rolling restart (not a failover) the proof client fell into a permanent
reconnect-churn loop (4302 connects over 41 min on an otherwise healthy cluster) — each cycle
creates fresh pub/sub log buffers in its embedded driver's /dev/shm, so once churn starts,
resource pressure can keep it churning. Test-client artifact (restart clears it); noted because
any long-lived Aeron client that reconnects in a loop should bound its reconnect rate.

## Throughput bench + two hosting bugs it flushed out (2026-07-19)

Running `run-gke-bench.sh aeron-cluster` (batch 1000, conc 48, 30 s runs) required standing up
the bench path (bench-runner pod, gateway `/seed` admin endpoint for control/price state — the
engine fail-closes on unknown accounts/securities and on prices staler than
`risk.price.max-age-ms`=30 s, so the bench keeps a 10 s price refresher running) and flushed out
two REAL hosting bugs, both now fixed:

1. **Egress emission throttled the state machine.** `offerEgress` retried an undeliverable ack
   up to 1000 x backoff-idle (~1 s per ack): one non-draining client session collapsed apply to
   ~1 ack/s — the cluster looked frozen. Bound is now 20 attempts (sub-ms); egress is
   best-effort by design and a slow client gets drops, never the state machine's time.
2. **Output-ring self-deadlock (the actual freeze).** The service thread is both producer
   (engine emits during apply) and consumer (`drainOutputs` after apply). A price tick against
   the bench's ~20k-order resting book emitted a fill cascade larger than the 4096-slot output
   ring -> `RingBuffer.next()` parked forever inside apply, all three members identically
   (deterministic log): consensus stayed up (elections, session accepts) but applied froze.
   Confirmed by SIGQUIT thread dump (`clustered-service` parked in
   `OutputPublisher.emitFillWithTradeAndPosition`). Ring is now 1<<18 with the sizing invariant
   documented (must exceed 3x the worst single-event cascade). The enlarged ring replayed the
   previously-wedging log clean through.

Methodology note: gateway fill counters under-count under load (egress drops are by design), so
the member health endpoint now exposes the engine's authoritative `trades` counter and booked/s
is read from the LEADER's delta.

### Results (3-member cluster, emptyDir, 400/200 timeouts, single-order gateway path)

| run | booked/s (engine-authoritative) | applied/s | submit/s | failed |
|---|---|---|---|---|
| 1 | 907 | 928 | 786 | 0 |
| 2 | **18,612** (cascade window) | 4,404 | 3,390 | 0 |
| 3 | 1,104 | 1,124 | 590 | 0 |

- **Sustained committed ingress through the current gateway: ~800-1,100 orders/s.** The
  bottleneck is the gateway's one-order-at-a-time committed-ack round trip (~1.2 ms each on the
  single owner thread) — NOT the cluster.
- **The consensus+apply path has large headroom**: 18.6k booked/s observed while replicating
  through Raft, and log replay after restart re-applied ~740k events in seconds. This mirrors
  the YU11/production finding: the REST per-order ingress is the ceiling, not the BLP.
- **NFR-AC02 (>= 25,149 booked/s) is NOT met via the per-order gateway path — as predicted in
  the hand-over doc.** The identified lever is unchanged: pipeline the gateway owner thread
  (offer many in flight, correlate acks by order id) and drive the bench through `/orders/batch`
  amortized submits. The engine-side burst number says the target is reachable once ingress is
  pipelined.

## Pipelined gateway: past the 25k bar (2026-07-19)

Pipelining the gateway owner thread (offer the whole batch into the consensus log, count acks
FIFO as they stream back — no per-order committed-ack wait) plus hardening it (HTTP pool 8->64
so probes never starve behind parked batch threads; heap 1g) delivered, stable across 3
back-to-back 30s floods, zero failures, zero ID reuse, no member or gateway restarts:

| run | booked/s (engine trades) | applied/s | submit/s |
|---|---|---|---|
| 1 | 45,684 | 20,310 | 28,860 |
| 2 | **135,834** | 31,247 | 28,961 |
| 3 | 122,415 | 34,746 | **35,714** |

- **Committed order ingress: ~29-36k orders/s** through REST -> gateway -> Raft consensus ->
  deterministic engine, vs the 25,149 booked/s NFR-AC02 baseline and the ~10k NATS-era ceiling.
- **Trade booking: up to ~136k trades/s** (price-tick cascades against the resting book execute
  inside the cluster at engine speed). Single-batch latency: 500 orders committed in 75 ms.
- NFR-AC02: **met and exceeded**.

### Apples-to-apples re-measurement (2026-07-20, c3 one-member-per-node)

The 2026-07-19 table above read `booked/s` from the leader's engine `trades` counter but drove a
**cascade** workload (price ticks fill many resting orders → booking amplified far above ingress),
so booked (135k) ≫ submit (29k). Re-measured on the current **c3-standard-4, one-member-per-node**
cluster with a **self-match** workload (SIDES=alternate, limit==seeded price → ~1 fill per order,
so booked ≈ applied ≈ submit), reading `booked/s` from the leader `trades` delta AND cross-checking
`submit/s` against `applied/s` (authoritative committed delta). Warmed up first.

| conc / batch | booked/s | applied/s | submit/s | sub/appl | restarts |
|---|---|---|---|---|---|
| burst peak (batch=1000) | **134,755** | **134,755** | 133,973 | 0.99 | 0 (that run) |
| 8 / 200 (sustained, 3 runs) | 30,936 / 29,332 / 31,992 | =booked | =booked | 0.99 | 0 |
| 16 / 200 (sustained) | 49,342 / 39,667 / (snapshot dip) | =booked | =booked | 0.99–1.00 | 0 |

- **`submit/s` is validated** — it tracks `applied/s` at ratio ~0.99, so the batch-201 accounting
  is NOT over-counting; committed ingress is real.
- **Committed ingress on c3 is far higher than the old c2 3-on-2 figure**: ~30k sustained (conc=8,
  rock-steady across 3 runs, 0 restarts), ~40-49k at conc=16, **burst ~134k** — vs the 29-36k the
  old nodes topped out at. The 25,149 booked/s NFR-AC02 bar is now cleared *sustainably*, not just
  in a burst.
- **The 134k is a burst, not sustainable.** At batch=1000 the members saturate — the in-JVM health
  server starves (liveness pressure) and the log grows fast; sustained flood at that rate restarts a
  member and can trigger a recovery-replay death-spiral (large log replay > liveness tolerance).
  Throttling to batch=200 keeps CPU headroom for the health server and holds steady with 0 restarts.
  Remaining hardening levers: move the health server off the hot duty-cycle thread, bound the log via
  archive segment purge-after-snapshot, and/or admission-throttle ingress.

### The deadlock that fixed-size rings could not fix

The first pipelined flood exposed that the output-ring self-deadlock was unwinnable by sizing:
a cascade eventually outgrew the 262k ring too, and because the trigger lives in the committed
log it wedged REPLAY on every member — a true poison pill (a full rolling restart came back
leaderless: every member parked at the same log position inside
`OutputPublisher.emitFillWithTradeAndPosition`, and the elected leader waited forever in
`joinLogAsLeader` for its wedged service).

Root fix (YU12 override of `OutputPublisher`): the claim path takes an optional backpressure
hook — `tryNext` instead of blocking `next`; on `InsufficientCapacityException` the hook drains
the published tail to egress inline (producer and consumer are the same thread) and the claim
retries. Unbounded cascades now flow through a bounded ring; the poisoned 1.4M-event log
replayed straight through, releasing ~1.27M trapped fills. Non-cluster hostings (separate
consumer threads) keep the blocking claim.

Operational notes from the same session: a full rolling restart of the emptyDir cluster leaves
the members leaderless only if the service wedges (fixed above) — otherwise the log survives via
quorum catch-up; leaked client sessions from crash-looping test clients can exhaust the
ConsensusModule's concurrent-session limit ("ERROR - concurrent session limit" on connect) — a
member rolling restart clears the session table; and with snapshots not yet scheduled, every
restart replays the full log (fine at 1.4M events / ~25s, worth wiring `onTakeSnapshot` to a
timer before the log grows unbounded).

## Periodic snapshots (2026-07-19)

`ClusterNodeMain` now runs a snapshot trigger (default every 30 s, `CLUSTER_SNAPSHOT_INTERVAL_MS`,
0 = off): the leader toggles the consensus module's SNAPSHOT control counter — the same mechanism
as `ClusterTool snapshot` — and all members snapshot at the same log position. Live on GKE:
snapshots appeared within one interval on all members, and a force-killed member came back
**fully caught up in 66 s** (pod reschedule + snapshot load + tail) with identical trades counter
and the fail-closed snapshot load checks green — vs minutes of full-log replication before.
Log segments are not purged (recovery = latest snapshot + tail); purge is a separate ops action
when disk matters.

## Leader kill under full flood + snapshot interval A/B (2026-07-19)

**Failover holds under load.** Killed the leader (node-clock precise java-kill) 25 s into a 60 s
~31k orders/s flood: new leader in **724 ms** (vs 653-716 ms idle), the flood completed with
**1,871,000 submitted / 0 failed / 0 ID reuse**, and the killed member rejoined and caught up
mid-flood in ~18 s while the cluster kept serving.

**Snapshot interval: 60 s, measured, not shorter.** 1 s-resolution applied-sampling during
floods showed each snapshot is a log-position barrier costing ~8 s of cluster-wide apply stall
at this state size. A/B (same flood, `CLUSTER_SNAPSHOT_INTERVAL_MS=0`): stalls gone, flood
finished in ~43 s (**~46k orders/s**) vs 60 s with 30 s snapshots (~31k/s) — a ~25% sustained
tax at 30 s intervals, plus an ~8 s ack-latency spike per snapshot. Recovery, meanwhile, is
pod-restart dominated (~40-66 s) and tail replay runs at ~300k events/s, so a longer interval
costs seconds: 60 s halves the tax for ~+3-6 s of worst-case recovery. Default and manifest now
60 s. (Side proof: with snapshots disabled, a member restart regressed to full-log replay —
~35 s at 6.4M events and growing — which is exactly what snapshots bound.)

Real fix if the stall ever matters more: async/incremental snapshotting off the apply path.

### Clean-cluster validation at the final config (2026-07-19)

Fresh cluster epoch (full reset), 60 s snapshots, 400/200 timeouts, pipelined gateway:
60 s flood delivered **2,248,000 orders / 0 failed (~37k/s — the best sustained run, less
snapshot tax at 60 s)**; leader killed at 25 s → new leader in **778 ms**; killed member
rejoined and converged mid-flood; **all three members ended at the identical trades counter
(9,182,152)** — deterministic state equality; 0 ID reuse.

### OPS HAZARD (NOW FIXED): emptyDir + `kubectl rollout restart` could lose the un-snapshotted tail

Found the hard way (bench data only): k8s rolling restart waits for pod-Ready, and member
readiness does NOT include catch-up — so successive members can be killed before the previous
one finished replicating, and with emptyDir disks the log tail beyond the latest snapshot can be
lost (one rollout here came back at an earlier applied position). Repeated wipe/replay cycles
also left a mixed-era archive that stranded a rejoining member in a permanently stalled
catch-up (service idle, replication waiting on ranges the quorum no longer had) — fixed only by
a full reset.

**FIX SHIPPED AND PROVEN (same day): readiness now gates on catch-up.** `/ready` returns 200
only when the member's applied sequence is within `CLUSTER_READY_MAX_LAG` (default 5000 events)
of its furthest-ahead peer (read from the peers' `/health` over the headless service;
unreachable peers ignored so cold start never wedges). k8s therefore cannot kill the next
member until the restarted one has converged — the un-snapshotted tail always lives on a
quorum. Probe: `timeoutSeconds: 2`, `failureThreshold: 120` (catch-up can take minutes under
load; that is the gate working, not a hang).

Proof under worst case — `kubectl rollout restart` issued 20 s into a 240 s ~27k orders/s
flood: the gated roll took ~5 min (each member held until converged), the flood sustained
through all three member restarts (6,469,000 submitted, 47,000 failed = 0.7%, the three
election windows — REST-visible timeouts, retryable), and state advanced monotonically with
**no regression** (applied 2.32M -> 8.81M, trades 9.18M -> 35.14M, all three members identical
after, 0 ID reuse). The same operation that lost the tail before is now safe.

Remaining true statements:
- Pod-level faults (crash, node kill, single-member restart) were always safe — quorum holds
  the log and rejoin converges.
- PVCs for the archive remain the belt-and-suspenders option for whole-cluster-at-once loss
  (all three nodes dying simultaneously still loses the un-snapshotted tail on emptyDir).

## Phase 0 baseline (joint plan): phase-resolved 400/200 control (2026-07-19)

New instruments: election-state counter watcher (`ELECTION-PHASE state=<S> atMs=`), FIRST-APPLY
stamp (first committed apply AS LEADER — the system SLO clock), 10 ms canary. Five kills,
fresh c3 one-member-per-node epoch:

| run | INIT (detection) | CANDIDATE_BALLOT | LEADER_READY | ROLE_LEADER | FIRST_APPLY |
|---|---|---|---|---|---|
| 1 | 400 | 424 | 645 | 646 | 784 |
| 2 | 396 | 425 | 651 | 651 | 1588 |
| 3 | 387 | 418 | 642 | 643 | 876 |
| 4 | 409 | 446 | 677 | 678 | 860 |
| 5 | 386 | 399 | 633 | 638 | 834 |

Readings: detection = the 400 ms heartbeat timeout exactly; canvass/nominate/ballot entry is
cheap (+10-40 ms); ballot->LEADER_INIT is the ~200 ms election chunk; ROLE_LEADER is tight at
638-678 ms. FIRST_APPLY - ROLE_LEADER (~150-900 ms) is CLIENT-bound: the canary is the only
input source, so the SLO includes client recovery (run 2's 1588 ms outlier == its 1553 ms
client gap). Confirms the joint-plan attack: detection+ballot respond to the timeout ladder;
the FIRST_APPLY tail collapses with native leader following. Client gaps at 10 ms cadence:
739-1553 ms, reuse 0.

## Phase 1a: native leader following PROVEN — redirect wedge falsified (2026-07-19)

`PROOF_NATIVE_FOLLOW=1`: one full-endpoint-list connect, explicit `newLeaderTimeoutNs(2s)`,
ack stalls logged but never close the session, reconnect only on genuine close (rate-limited).

Results across 5 leader kills at the 400/200 control config:
- **The same cluster session (id 170) survived every kill** — 1 connect total, 5 NEW-LEADER
  events, 0 reconnects, 0 ID reuse.
- NEW-LEADER arrives ~equal to ROLE_LEADER (+640-780 ms); the client's next canary commits
  ~10 ms later — FIRST_APPLY - ROLE_LEADER collapsed from ~150-900 ms (reconnect era) to a
  consistent ~120-160 ms.
- **Client-observed gaps: 740-888 ms — all sub-1s at the conservative config**, tracking the
  system floor + ~60-80 ms (cycling-era baseline: 739-1657 ms bimodal).
- **The kind "redirect wedge" did NOT reproduce on GKE** with the egress term-length//dev/shm
  fixes in place: multi-endpoint initial connect + follower redirect work as designed. The
  endpoint-cycling machinery is cleared for deletion (Phase 4 unified owner loop).

Remaining failover budget is now: detection (heartbeat timeout) + ballot->ready + ~70 ms client
hop — i.e., entirely the Phase-3 timeout ladder's territory (behind Phase-2 CPU isolation gates).

## Phase 1b live validation: snapshot barrier defanged (2026-07-19)

codeX's O(n) snapshot fix (`ecabf7c`, 1031x on the 100k/50k fixture) merged, deployed, and
validated live on the Guaranteed-QoS c3 topology:

- **Kill fired AT the snapshot tick** (the old worst case): ROLE_LEADER +683 ms,
  **FIRST_APPLY +856 ms**, 0 reuse — indistinguishable from clean-kill baseline (806-810 ms).
  The "role elected but service paralyzed for seconds" failure mode is gone.
- **Barrier under max flood: ~8 s -> ~3 s** (43.6k submits/s sustained — new record — with
  apply bursts of 130-275k/s; the s52 snapshot paused apply ~3 s then caught up in one burst).
  ~5% duty at 60 s intervals, down from ~25%. The residual is NOT the fixed order scan (unit
  callback is ~10 ms): suspects are the consensus-module/archive snapshot leg (recording
  setup + fsync) — a follow-on if the remaining 3 s ever matters; it does not gate the
  failover SLO (see the snapshot-tick kill above).
- Phase-2 topology re-baseline: FIRST_APPLY 806/809/810 ms across 3 kills — a 3 ms spread
  (Guaranteed QoS visibly collapsed variance vs 756-888 pre-isolation).
- Canary lessons: non-marketable canary flow accumulates state until a risk cap silently
  rejects everything (rejects still consume orderRefs — refs advancing does NOT prove
  accepts); canary now submits marketable flow (limit == price) on a dedicated account.
  Unexplained-but-noted: from the default-pool e2 node the canary's service acks never
  arrived while consensus events and the gateway's acks did; canary pinned to the member
  pool (100m, negligible), gateway stays isolated.

## Phase 3: the timeout ladder (2026-07-19) — sub-500 client ACHIEVED

All rungs: fresh epoch, 5-min idle soak (zero uncommanded elections — gate passed at every
rung, including the 80 ms detector), 5 node-clock kills, native-follow client, 0 ID reuse.

| Rung (interval/timeout/election/status ms) | ROLE_LEADER | FIRST_APPLY (SLO) | Client gap |
|---|---|---|---|
| control 100/400/200/100 | 638-678 | 806-810 | 740-888 |
| A 50/200/100/25 | 309-476 | 429-623 | 420-619 |
| B 25/120/60/10 | 200-240 | 357-403 | 376-405 |
| C 20/80/40/10 | 161-225 | **275-368** | **279-453** |

- **Client-facing < 500 ms: consistent** (10/10 kills at rungs B+C; best 279 ms).
- **System-facing FIRST_APPLY: 275-368 ms consistent at rung C** — raw election is sub-200
  (ROLE_LEADER 161-170 ms typical) but ~110-140 ms of client re-point/first-commit hop sits on
  top. Further gains are CLIENT-side (pre-warmed ingress publications for instant re-point),
  not tighter timeouts — matching both proposals' honest 250-350 ms bound for this design.
- No margin failures anywhere on the ladder; the old "aggressive timeouts destabilize" belief
  is fully retired (80 ms detector idle-stable on Guaranteed-QoS one-per-node placement).
- Rung choice pending the loaded acceptance run (C vs B = numbers vs 1.5x detector margin).

### Rung A under load: kills during flood (2026-07-19, final)

Three leader kills fired during an active flood at the shipping config (50/200/100/25):
**FIRST_APPLY 453 / 517 / 554 ms, 0 ID reuse** — barely above the idle band (429-623 ms).
Caveat recorded honestly: the flood generator degraded after the first kill because the
GATEWAY still runs the legacy owner loop (isClosed-only reconnect) and loses its session on
failover — so kills 2-3 ran under partial load (canary + residual). This elevates Phase 4
(unified native-follow owner loop for the gateway) from cleanup to the top remaining item: it
is both the REST-availability-across-failover fix and the prerequisite for fully-loaded
acceptance kills.

## Campaign scoreboard (end of 2026-07-19)

| Target | Verdict |
|---|---|
| Client-facing < 500 ms | **ACHIEVED** consistently on quiet clusters (10/10 kills, best 279 ms); 420-619 ms at the flood-stable shipping config |
| System-facing < 200 ms | **DECLINED honestly**: 275-368 ms at the tightest stable-idle rung; raw election reaches 161-170 ms but the client re-point hop (~110-140 ms) and the flood constraint (snapshot-leg duty stall > 120 ms) bound the deployable floor |
| Shipping config | Rung A 50/200/100/25 — flood-soak clean, FIRST_APPLY 429-623 ms idle / 453-554 ms under load, pinned in the manifest |
| Correctness | 0 ID reuse across ~80 kills; one canary session survived every kill |

Remaining (in order): Phase 4 gateway unified owner loop (+ delete endpoint cycling);
Phase 5 trimmed acceptance at rung A (25 idle + 25 loaded incl. snapshot-tick kills);
non-blocking snapshot leg (unlocks sub-120 ms detectors under flood); client ingress
pre-warm (attacks the ~110-140 ms re-point hop toward sub-200 system).
