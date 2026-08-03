# Implementation Status: YU13-limit-order-book

Status as of 2026-07-20. Verified on the JVM (kind-free); the live-cluster bench and HA proof
(T-LOB14) are the remaining work.

## Generation

- `bash pipeline/generate-state.sh YU13-limit-order-book` exits 0, recursively generating the
  full YU12→…→YU02→014 ancestry and overlaying the YU13 crossing-book assets last-wins.
- Shared-override markers verified in the generated `order-matcher` tree — every ancestor's
  behavior survives the overlay alongside YU13's:
  - YU13: `LimitBook` class, `FLAG_RESTING_UPDATE`, `SNAPSHOT_FORMAT = 2`.
  - YU12: `aeron-cluster` dependency, `ClusteredService` hosting, `TradeNatsPublisher` bridge.
  - YU11: SBE generation. YU10: QuickFIX/J. YU09: no-GC/allocation gate tasks. YU03: risk
    gateway (`BlpRiskState`, `decideAndReserve`).
- Runtime wrapper scripts (`{start,stop,status}-state-YU13-limit-order-book-generated.sh`,
  `test-state-YU13-limit-order-book.sh`) delegate to the inherited YU10 harness; YU13 changes
  the matching policy, not the run/deploy surface.

## Engine (crossing limit-order book)

- `LimitBook`: array-indexed price levels on a 0.001 grid (`BOOK_TICK_PX = 1000`) inside a
  banded window (`BOOK_LEVELS = 1<<17` = $131.07, anchored mid-band on the security's first
  limit); intrusive doubly-linked FIFO queues of pooled orders; per-side occupancy bitmaps for
  O(1) best-price maintenance; O(1) append/reduce/unlink.
- `MatchingEngine`: grid/band admission before reservation, create-ack first, best-price-first
  FIFO crossing with both-side paired emission (order update + trade + position per side,
  resting side flagged), limit-rest or market-cancel of the remainder, cancel/force-fill unlink,
  ticks reduced to risk-freshness + mark seeding (the mark is the last trade price, ADR-051).

## Behavioral proof (all green on the JVM)

- `LimitOrderBookTest` — price-time priority, best-price-first across levels, partial fills
  keeping queue position, market fill-then-cancel-remainder, market PRICE_MISSING on empty
  unpriced book, off-grid INVALID, out-of-band PRICE_COLLAR, cancel mid-level unlink, ticks
  never fill, last-price-is-last-trade, and replay-determinism (identical recovery digests).
- `ClusterSnapshotCodecTest` — format-2 header geometry + band-anchor round-trip; a post-restore
  crossing sweep reproduces the source's exact fills (per-level FIFO preserved); fail-closed on
  off-grid/out-of-band restored rows, legacy format-1, and every inherited corruption case.
- `AeronClusterSpikeTest` — single-member snapshot+tail AND zero-tail recovery on the crossing
  engine: refs 1..9 never reused, trade counter continues (2 pre-snapshot → 14 after the
  crossing sweep), reservations/executed exposure exact across two recoveries, idempotent retry
  answered with the original ref after both recoveries.
- `ThreeMemberClusterTest` — leader kill, wiped-member empty-disk rejoin, second failover: no ID
  reuse across two failovers, cross-member state equality, trade lineage unbroken (maxTradeSeq 14).
- `OutputDisruptorHandlersTest` — a cross emits per side (order update + TradeBooked +
  PositionUpdated), both positions move, the resting side carries FLAG_RESTING_UPDATE.
- `LmaxHotPathParityTest` (12/12) — REST create/cancel/force-fill/market-trade lifecycle parity
  retained; every fill driven by a genuine resting-opposite + crossing order.

## Zero-allocation and banned-API

- Allocation gates (base `allocationGateTest`, risk-gated `riskAllocationGateTest`, Aeron
  transport, cluster apply-path) pass against the crossing engine, exercising crossing, partial
  fills, market-order remainder cancel, and terminal-retention eviction with exactly zero bytes
  on the producer, journaler, and BLP threads. The base gate needed the same C2-only pinning
  (`-XX:-TieredCompilation -XX:CompileThreshold=10000`) the Aeron/cluster gates already use — the
  crossing engine reshaped inlining enough that a tier transition rematerialized a constant 160
  bytes mid-window (a compiler artifact; an on-thread branch-by-branch probe measures exact zero
  for every steady branch once warm).
- `HotPathBannedApiTest` — `LimitBook` added to the hot-path scan set; no runtime string
  concatenation or other banned APIs on the crossing path.

## Engine latency artifact (NFR-LOB01)

`MatchLatencyBenchmarkTest`, per-order match op measured on the BLP thread under closed-loop
load (no coordinated omission), nanoseconds — the engine's own number, not wire-to-wire:

Captured on a quiescent machine (kind workload scaled to zero), 250k–500k measured ops per class:

| Op | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|
| resting insert | 167 ns | 583 ns | 2.04 µs | 32.1 µs | 136.6 µs |
| limit cross | 542 ns | 1.88 µs | 15.4 µs | 83.6 µs | 272.4 µs |
| market order | 584 ns | 2.04 µs | 15.7 µs | 74.8 µs | 1.20 ms |

The median match is sub-microsecond and p99 is under 2 µs. The tail beyond p99.9 is JIT
compilation, safepoints, and OS scheduling on a shared dev machine — not the book operation; a
tuned host (AOT/GraalVM or Zing ReadyNow, isolated cores, Epsilon) is where the p99.99 goes. Do
not quote p50 alone: the tail is the number that matters. Honest talk line: the match is
nanoseconds, wire-to-wire is microseconds in software, nanoseconds only in silicon.

## Reconciliation with the YU12 lane (2026-07-21)

YU12's `3394186` (synchronous `/trades`) and `2f5a813` (seed-job hardening,
`RECON_POLL_INTERVAL_MS`, gateway replicas=3) were merged in. `2f5a813` cherry-picked cleanly
(none of its manifests are shadowed by a YU13 layer). `3394186` could NOT be cherry-picked: it
patches `ClusterGatewayMain`/`MatchingEngineClusteredService` in the YU12 layer, and YU13 carries
its own copies which win at generation — so it was hand-merged into the YU13 copies.

Two collisions resolved in the merge:

- **Egress ack byte 21.** `3394186` writes the RiskReason ordinal at byte 21; YU13 already uses
  byte 21 for the resting-update class. The two live in different spec layers, so git could not
  flag it. RiskReason moved to byte 22; ack length unchanged (24).
- **`KIND_TRADE_BOOKED` is no longer unique to `/trades`.** The crossing book emits it for BOTH
  sides of every ORDER match, so the ack kind alone would let a foreign fill answer a `/trades`
  request (a 200 for someone else's trade) and would inflate the new market-trade metric with all
  crossing fills. Byte 23 now carries a market-trade class, stamped for the duration of a
  `TYPE_TRADE_NEW` apply (covering mid-apply backpressure drains); the gateway gates both the
  correlation and the counters on it. Verified live: three `/trades` calls against a cluster that
  had just booked two crossing fills reported exactly `booked=1, rejected=2` — un-inflated.

`422` vs `504` preserved exactly: 422 is a committed business rejection carrying its RiskReason;
504 means no committed decision arrived (failover/timeout — ambiguous, the trade may still
commit). Live-verified: valid trade → 200 `{"booked":true}`; unseeded security → 422
`UNKNOWN_SECURITY`; unknown account → 422 `UNKNOWN_ACCOUNT`.

Re-run as a hot-path change after the ack-layout edit: `test` 227/0, `noGcTest` + `riskNoGcTest`,
and all four allocation gates green.

## Live kind measurements (T-LOB14, 2026-07-21)

3-member cluster + 3 gateways on kind (one member per worker, 1 CPU / 1536Mi each), image pinned
`traderx/cluster-node:yu13`, 7 real accounts + a 20-ticker reference universe seeded FIRST (the
OSFF-1 silent-reject gate — verified by a smoke cross that booked 2 fills before any bench ran).

| Measurement | Value |
|---|---|
| Submit rate (in-cluster, 3 gateways, conc 48 × batch 200) | ~10,267 orders/s |
| Booked trades/s (leader engine trade counter, 2 per cross) | **10,533/s** |
| Crossing ratio | 0.92 fills per submitted order — genuinely two-sided |
| Failed | 1.2% under maximum concurrency |

This does NOT clear the 25,149 booked/s NFR-AC02 bar, and it is not a like-for-like comparison —
that baseline was established on GKE (`c3-standard-4` dedicated nodes), whereas this is kind on a
laptop with 1-CPU-limited members inside a Docker VM. The GKE run below settles it.

## Live GKE measurements (T-LOB15, 2026-07-21) — the like-for-like number

3-member cluster on `blp-c3-pool` (3 CPU / 2Gi per member, one per node) + 3 gateways, image
`us-east1-docker.pkg.dev/traderx-501015/traderx/cluster-node:yu13` built `--platform linux/amd64`
(the recurring arm64/amd64 trap), same bench script and parameters as the kind run.

| Run | Config | Submitted | Failed | Booked trades/s |
|---|---|---|---|---|
| 1 (cold JIT) | conc 48 × batch 200 | 1,860,400 | 0 | **62,333** |
| 2 (warm) | conc 48 × batch 200 | 2,253,600 | 0 | **75,440** |
| 3 (over-saturated) | conc 96 × batch 250 | 348,000 | 27,000 | 11,866 — past the knee |

**The crossing engine clears the NFR-AC02 bar decisively: 62–75k booked trades/s vs 25,149,
roughly 2.5–3×, with zero failures at the sustainable concurrency.** Peak observed submit rate
71,606 orders/s.

Two properties worth noting from the run:

- **The book stays bounded.** With genuinely two-sided flow every order crosses, and
  `traderx_book_open_orders` returned to 0 on all three members after each run — the unbounded
  growth of the parent state's `FILL_FULL_THRESHOLD` half-fill policy is structurally gone.
- **No divergence under sustained load.** After ~4.5M booked trades all three members reported an
  identical trade counter and identical book digest.

kind vs GKE is ~7× (10,533 → 75,440) on identical code and bench parameters, confirming the kind
figure was environment-bound (1 CPU vs 3 CPU members, Docker VM vs dedicated nodes) and never
engine-bound — consistent with the 542 ns p50 in-JVM cross.

Two measurement traps worth recording: benching through `kubectl port-forward` understates
throughput badly (a single-threaded proxy — 2,396 vs 9,383 submit/s in-cluster), and the batch
harness rotates tickers per order while alternating sides by index, so with an EVEN ticker count
each symbol receives only one side and nothing ever crosses on a real book. Use an odd ticker
count.

## Wire-to-wire REST latency on GKE (2026-07-21)

Client-observed round trip for ONE order through the synchronous REST path — HTTP in, gateway
forwards over Aeron ingress, Raft commits, the service applies, committed egress ack, HTTP out.
Measured in-cluster at CONSTANT ARRIVAL RATE with latency taken from the INTENDED send time, so
a stalled system cannot hide its own stalls (coordinated omission). Self-crossing single account
so the flow keeps booking rather than drifting into POSITION_LIMIT rejects.

| Offered rate | p50 | p90 | p99 | p99.9 | max | failed |
|---|---|---|---|---|---|---|
| 100/s | **1.42 ms** | 2.33 ms | **8.55 ms** | **13.97 ms** | 18.5 ms | 0 |
| 250/s | 5.78 ms | 9.31 ms | 13.74 ms | 19.94 ms | 32.3 ms | 0 |
| 500/s | 6.48 ms | 14.81 ms | 33.05 ms | 53.80 ms | 108 ms | 0 |
| 1000/s | collapse (multi-second queueing) | | | | | 7,444 |

Quote the 100/s row: it is the unsaturated latency. The synchronous per-order path saturates
between 500/s and 1000/s because each gateway serializes offer→commit→ack on its owner thread —
which is exactly why the throughput path is a pipelined batch endpoint (75k booked/s) and the
per-order path is not. Same cluster, two different contracts; do not mix the numbers.

**A 40 ms measurement trap found and fixed here.** The first readings showed p50 ≈ 43 ms flat
regardless of rate. `/ready` — which never touches the cluster — measured the same 42.6 ms, so it
was not the matching path at all: the JDK `com.sun.net.httpserver` writes response headers and
body as two small segments with Nagle enabled, and the client's delayed-ACK timer then adds its
~40 ms. Fresh sockets (no reuse) dropped it to 2.8 ms, confirming it. Setting
`-Dsun.net.httpserver.nodelay=true` on the gateway took `/ready` from 42.6 ms to **2.50 ms** with
keep-alive. This was taxing every keep-alive REST client — including the UI — and any latency
number taken before this fix was ~97% HTTP artifact. The flag belongs in the gateway manifest.

## Failover on the crossing engine, node-clock precise (2026-07-21)

This closes the YU12 open issue `HANDOFF-issue-yu12-failover-measurement.md`, which recorded that
a clean pod-kill number had never actually been taken (the planned kill did not execute that
session). Measured its way: a timestamped probe at one order / 200 ms, reporting FAILED REQUEST
COUNT and the gap between last-success-before and first-success-after — not "first-fail to
last-fail", which overstates an outage that is really a few scattered failures.

Five clean leader kills (`--grace-period=0 --force`, leader confirmed by role first), probe at
20 ms cadence. **Probe cadence is the measurement floor** — a first pass at 200 ms produced
602/602/802 ms and looked like a regression; it was quantization plus under-sampling, and the
sub-200 ms failovers were invisible to it. Do not measure this with a coarse probe.

| Killed | Failed reqs | Success rate | Client gap |
|---|---|---|---|
| m1 | 3 | 99.85% | **83 ms** |
| m2 | 3 | 99.86% | **141 ms** |
| m2 | 3 | 99.85% | **182 ms** |
| m0 | 32 | 98.37% | 673 ms |
| m0 | 32 | 98.55% | 848 ms |

**Median 182 ms, best 83 ms**, against the parent state's 653–716 ms system-facing and its
best-ever client-observed 192 ms — the crossing book did not regress failover; the median is
better than the parent's best case.

### The bimodality — root-caused and fixed

The two modes were not noise and not cluster-side. Running `/orders` and `/ready` as independent
probes at the same cadence separated them: `/ready` is gateway-local (200 only while the gateway's
cluster session is up, never touches the leader), so if it fails alongside `/orders` the outage is
the GATEWAY re-establishing, not the cluster electing.

| Killed member | `/ready` fail window | `/orders` gap |
|---|---|---|
| m2 | 41 ms | 201 ms |
| **m0** | **1270 ms** | **1316 ms** |

`/ready` and `/orders` failed for the same window, and the only variable was WHICH member died —
a 31x penalty. Cause: `connectCycling()` declared `int attempt = 0` as a LOCAL, so every reconnect
tried ingress endpoint 0 first. `GATEWAY_INGRESS_ENDPOINTS` is ordered `0=…,1=…,2=…`, so whenever
member 0 was the member that died, the gateway blocked on the dead endpoint's connect timeout
before trying a live one. Killing m1/m2 hit a live endpoint immediately — the fast mode.

Fixed: the first reconnect attempt now hands Aeron the COMPLETE member list so the cluster client
resolves the leader itself; single-endpoint cycling remains as a fallback with a rotating start
(`connectRotation` persists across reconnects) so a dead endpoint is never retried first twice.

| | before | after |
|---|---|---|
| killing m1/m2 | 83, 141, 182, 201 ms | 162, 204 ms |
| **killing m0** | **673, 848, 1316 ms** | **222, 445 ms** |
| worst observed | **1316 ms** | **445 ms** |

The bimodality is gone — member 0 is no longer an outlier and the >600 ms tail is eliminated.
Quote **median ~200 ms, worst ~450 ms**. This mattered beyond the number: member 0 is the first
pod of the StatefulSet, so the slow path was the one most likely to be hit in practice.

Measurement notes that cost real time here:

- **`kubectl delete` return is not the kill instant.** The pod keeps serving for up to ~2.5 s
  after the command returns (observed: last client success at +2240 ms, new leader's ROLE-CHANGE
  at +2577 ms). Any metric anchored on "when I issued the delete" inherits that variance. The
  client gap — last success before, first success after — is immune to it and is the number to
  quote.

Two things this measurement taught, both worth keeping:

- **Failover under saturation is a different number.** Repeating the kill under a 30–60k orders/s
  batch flood gives ~8–12 s of zero/degraded throughput, because all three gateways lose their
  cluster session at once and must reconnect while a large backlog drains. Both numbers are real;
  they answer different questions. Quote ~600 ms for "what does a trader see when a node dies",
  and the flood figure only with its context.
- **`FIRST-APPLY` is not a client-facing metric.** Measuring kill → the new leader's first applied
  message gave ~12.5 s under flood and ~10 s on an idle cluster — because it cannot fire until
  some client message actually reaches the new leader. It measures cluster liveness, not service
  restoration. Likewise, kill → `ROLE-CHANGE(LEADER)` measured ~2.6 s, but ~2 s of that is the pod
  actually terminating after `kubectl delete` returns, not Raft election.

## HA recovery proof on the crossing engine (T-LOB14)

Fixture: ten asks @150.050
arriving FIRST, ten @150.000 arriving SECOND — arrival order and price order deliberately
disagree, so the fill pattern is a falsifiable test of price priority.

Proven across two crashes and two promotions, asserted from the members' own book digests:

- Snapshot format 2 **round-trips the full two-level resting book across a failover**: the killed
  leader reloaded its own snapshot and reconstructed a book hash byte-identical to the members
  that never restarted.
- **Price-time priority survives the failover** — the post-failover sweep consumed exactly the ten
  better-priced asks (the ones that arrived second) and left the worse level untouched.
- **Book identical on all three members** at every checkpoint; a snapshot-recovered member later
  won an election and led successfully.
- **Zero ID reuse**: `nextOrderRef` strictly monotonic 1 → 21 → 22 → 23 → 24 across both crashes.
- **Determinism across epochs**: the same input sequence replayed in a different epoch after a
  full wipe produced the identical book hash.

NOT proven — **empty-disk rejoin into an already-advanced cluster**. The joiner wedges in consensus
log replication (`applied=-1`). This is the inherited Aeron 1.51 defect documented in the YU12 lane
(`ISSUES-yu12-rejoin-term-poisoning-2026-07-19.md`), not a YU13 regression: it reproduced on a
cluster whose book was EMPTY with no format-2 snapshot content, and no snapshot/format/band error
appears in the joiner's logs — the failure is entirely inside log replication, before the service
loads any state. Three members starting together from empty always converge (done four times).

## Real TAQ order flow on the crossing engine (OSFF-3, 2026-07-21)

The demo realism beat: real historical NYSE flow crossing on the Raft-consensus book.

**Curation.** DuckDB on the tick-store pod (data-local, GCS HMAC already in env) over the YU07
store (`gs://traderx-501015-tick-store/ticks/source=taq/`): 7 symbols (odd count, per the harness
side-alternation trap) — AAPL, MSFT, NVDA, TSLA, AMZN, META, JPM, all inside the seeded 20-ticker
universe — 2025-03-03 09:30–10:00 ET, `event_type='trade'` prints only. **1,138,793 real prints,
$18.2B notional**, tick-rule aggressor side (47.3% buy — a down-open day), prices rounded to the
0.001 grid. 38.6 MB CSV in `scripts/bench/results/taq/`. (`kubectl cp` silently truncated the file
at ~35 MB — transfer big files with gzip + `exec cat` and md5-check both ends.)

**Method — trade-print replay.** Each print becomes a passive limit at the print price plus an
aggressor limit at the same price on the other side; the pair is order-insensitive (whichever
lands first rests, the other crosses at the resting price), so a sequenced replay reproduces
every historical print at its historical price, the book self-cleans to empty, and positions stay
a near-flat random walk. Passive and aggressor accounts rotate over the 7 real SQL accounts and
are always distinct. `scripts/bench/replay/taq-replay.mjs`:

- `--mode paced` — sim-clock at `--speed`× real time, ONE in-flight batch. **Must target a single
  gateway pod**: the `order-matcher-gw` Service round-robins across gateways, which interleaves
  batches and lets fills slip off the historical price. Sequential batches through one gateway =
  exact global ordering.
- `--mode max` — `--conc` workers race sequential chunks through the Service. Real flow at max
  ingest; cross-pair interleaving can fill at a better resting price than the print (stated, not
  hidden).
- Orders do NOT refresh risk price-freshness (only `TYPE_PRICE_TICK` does; 30 s max age), so the
  harness seeds each symbol at its first-print price upfront and re-`/seed`s per symbol every 8 s
  at the latest replayed price — pointed at a DIFFERENT gateway than the replay stream, or the
  refresher queues behind a stalled batch on the same owner thread.

**Results (GKE, 3× c3 members, fixed image, fresh epoch, seeded first):**

| Run | Result |
|---|---|
| Paced 5× (the flagship) | 1,138,793 prints in 360.2 s — pacing held exactly. 2,277,155/2,277,586 orders accepted (**431 policy rejects, 0.019%**, including both sides of the two 2,111,072-share NVDA opening blocks vs the 1M-share order-size cap — the 15c3-5 gate working). 0 HTTP failures. 2,276,892 trade records booked. |
| Post-run state | **Book returned to exactly empty on all 3 members** (open=0, order-hash=0); position hash, nextOrderRef, applied position **byte-identical across members** after ~2.4M trades. |
| Booked-rate timeline | Tracks real market intensity: ~20k booked/s at the compressed 09:30 burst decaying to ~4k/s by 10:00 — the shape of the open, on a consensus-replicated book. |
| Max-rate (conc 8, via Service) | 1,138,793 prints in 78.1 s — **14,589 prints/s, 29,172 accepted orders/s**. Client-bound (one bench pod, conc 8), NOT the cluster ceiling — the 75k booked/s pipelined figure stands. |
| Single ordered stream ceiling | ~7,700 prints/s (15.3k orders/s) through one gateway at batch=100. |

**Two new traps recorded:**

- **Band-anchor vs real prices.** The book's price band (±$65.5) anchors on each security's FIRST
  limit price of the epoch and never re-anchors. An epoch that has traded bench flow at 150
  collars real prices (AAPL 241.81 and MSFT 388.49 rejected PRICE_COLLAR while NVDA 114 passed).
  Replay real prices on a fresh epoch, or ensure the first limit per symbol is at the real level.
- **Batch size vs the ack window.** At 200 prints (400 orders) per POST, the gateway's pipelined
  ack wait loses egress acks under crossing load (2 booked events per print) and every lossy
  batch burns its full ~7 s ack budget — a single-stream run stalls hard. batch=100 is
  stall-free; the default in taq-replay.mjs.

## T-LOB16 RESOLVED: the "empty-disk rejoin defect" was our NetworkPolicy, not Aeron (2026-07-21)

The OSFF-3 retest disproved BOTH prior theories (inherited Aeron 1.51 defect; stale-PVC
hypothesis). The wedge reproduced on kind with a genuinely fresh, empty PVC — and the leader
showed zero replication activity during the joiner's attempts, which localized it to
connectivity:

- **Root cause:** `ClusterNodeConfig` (mirroring the Aeron 1.51 sample) left three channels on
  ephemeral ports (`endpoint=host:0`): the archive client's controlResponseChannel, the Archive's
  replicationChannel, and the ConsensusModule's replicationChannel. The kind NetworkPolicy admits
  UDP 21800–22200 only, so FOLLOWER_LOG_REPLICATION traffic lands on an ephemeral port and is
  **silently dropped** — the joiner loops INIT→CANVASS→FOLLOWER_LOG_REPLICATION at `applied=-1`
  forever, with no error on either side (UDP).
- **Why it masqueraded as an Aeron defect:** PVC-preserving restarts use FOLLOWER_CATCHUP on the
  in-block transfer port (allowed) — so "members with state rejoin, wiped members wedge" looked
  exactly like an empty-disk-rejoin bug. And GKE never applies that policy to the cluster pods
  (the gke kustomization omits it), hence "works on GKE, wedges on kind".
- **Smoking-gun experiment:** deleting the policy un-wedged a 10-minute-stuck joiner within 10 s
  (applied −1 → fully caught up).
- **Fix:** pin the three channels to fixed offsets 6/7/8 in each member's 100-port block (inside
  the policy range). `ClusterNodeConfig.java` in the YU12 layer; propagated to the YU12 lane.
- **Acceptance (kind, policy ENFORCED, fixed image):** empty-disk rejoin into an aged cluster
  (3 leadership terms, snapshots, 1000 trades) caught up in <30 s; a PVC-preserving restart
  caught up too; all three members byte-identical after; full suite 227/0;
  ThreeMemberClusterTest passes solo in 26 s (its documented parallel-load flakiness stands).
- **Distinguish the two wedges.** The 2026-07-19 GKE issue
  (`ISSUES-yu12-rejoin-term-poisoning-2026-07-19.md`) is a DIFFERENT mechanism with a different
  signature: an explicit `ArchiveException: requested replay start position=0 …` storm after ~46
  leadership terms accumulated degenerate RecordingLog entries — real Aeron 1.51 behavior at
  heavy term churn, unaffected by today's finding, remediation (clean reset) stands. The kind
  wedge (T-LOB14/T-LOB16) is SILENT — no exception anywhere — and was the policy bug. Triage by
  signature: exception storm → term poisoning; silence → check replication-port reachability
  first. The kind empty-disk-rejoin acceptance line is **unblocked**: a config rule (pin
  replication ports / scope policies to cover them), not an engine limitation.
- GKE redeployed on the fixed image (`cluster-node@sha256:242ddd30…`), fresh epoch, re-seeded.
  Failover spot-check on the fixed build: **124 / 348 / 223 ms** client gap across rotated
  leader kills (20 ms probe) — consistent with the recorded ~200 ms median / ~450 ms worst, no
  regression from the port pinning.

## Failover presentation framing (decision for the talk)

- **The headline number is the idle client-observed one: median ~200 ms, worst ~450 ms** — "what
  a trader sees when a node dies", measured node-clock-free as the client gap (last success
  before → first success after) with a ≤20 ms probe and rotated member kills.
- **The ~8–12 s figure under a 30–60k orders/s flood answers a different question** — "how long
  until a saturated pipeline is back to full throughput when all gateways lose their sessions at
  once and a backlog must drain". It goes in a backup slide / spoken caveat WITH that context,
  never on the same slide as the 200 ms number, never averaged with it.
- Never present FIRST-APPLY or ROLE-CHANGE-derived times as client-facing (they measure cluster
  liveness and pod teardown, not service restoration).

## Engine tail on a dedicated pinned host (OSFF-3 #2, 2026-07-21)

`MatchLatencyBenchmarkTest` rerun on a dedicated, otherwise-idle GCE `c3-standard-8`
(Temurin 21, `taskset -c 2-7`, no other load), two runs, reproducible:

| Op | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|
| resting insert | 352 ns | ~500 ns | **~665 ns** | ~8–10 µs | 24–107 µs |
| limit cross | 870 ns | ~1.35 µs | **~4.4 µs** | **~12 µs** | 20–55 µs |
| market order | 946 ns | ~1.5 µs | ~4.5 µs | ~12.6 µs | ~2.6 ms (one op — see note) |

Read against the shared-laptop numbers above: **the tail collapses ~7–13×** (cross p99.9
15.4 µs → 4.4 µs, p99.99 83.6 µs → 12 µs, max 272 µs → 20–55 µs) while p50 roughly doubles —
the laptop's M-series core is simply faster per-op than the c3 Xeon vCPU, but the shared-host
scheduling/JIT noise WAS the tail, exactly as claimed. Honest slide line: the p99.9+ tail is
the host, not the book; a quiet pinned core buys an order of magnitude in the tail before any
exotic tuning. One ~2.6 ms outlier op on the market path reproduces in both runs (1 in 250k, a
JVM safepoint/GC event; p99.99 stays ~12.6 µs) — quote percentiles, not max. Next rungs if ever
needed, in ROI order: Epsilon on the bench JVM, `isolcpus`/`nohz_full` (kernel cmdline on a
dedicated VM), then AOT. Kernel-bypass wire-to-wire stays literature-cited: no ef_vi on GCP,
and Aeron's ef_vi/DPDK media driver is the commercial premium tier.

## Throughput sweep + in-process benchmark (OSFF-3 follow-up, 2026-07-21 EOD)

**New headline: 146–165k booked/s** (leader trade-counter, 60 s runs, conc 48 × batch 200,
fresh epoch, gateways spread one-per-node) — reproduced four times: 146,500 / 148,031 /
164,949 / 163,755. **This roughly doubles T-LOB15's 75k and reaches the never-reproduced
"135k burst" territory as a measured sustained-for-a-minute figure.** The enabling change is
topology, not code: the gateway Deployment had no anti-affinity, and when the scheduler packs
all three gateways onto one 2-vCPU node (observed directly today) the tier tops out near the
old ~75k. `podAntiAffinity` added to the gke gateway manifest — note `preferred` does NOT
guarantee spread through a rolling update (all three re-packed onto one node; had to recycle
pods sequentially to spread them). Batch size is a cliff, not a dial: batch 200 is the knee,
and batch ≥ 500 collapses outright (owner-thread queueing pushes whole batches past the 30 s
client timeout — this also finally explains the unexplained 2026-07-20 "batch=1000 gave ~2k/s").

**In-process journaled benchmark re-run on the crossing engine** (harness updated in the YU13
layer: the auto-fill-era all-BUY generator rests forever on a real book — sides now alternate
so every pair crosses; driver test `JournaledBlpBenchmarkDriverTest`, env-gated
`RUN_BLP_BENCH=1`): **1,134,658 orders/s sustained on a GKE-class c3 VM** (1,561,764 on the
laptop — faster core), output ring ~3.8–5.3M events/s. Two implications: the engine tier has
~7× headroom over even the new 165k REST figure, and **a remembered "~6M/s" almost certainly
refers to output EVENTS/s** (each order fans out to ~3–6 output events), not orders/s.

### Sweep postmortem — the leak that caps sustained throughput (FIXED — see next section)

The first soak (~33M cumulative orders) killed the cluster, and the failure chain is worth
recording precisely:

1. **`MatchingEngine.ordersByRef` grows with cumulative order count, not live orders.** It is
   a flat array indexed by orderRef, doubled to cover the highest ref ever issued and never
   shrunk; terminal eviction nulls entries but the array itself is ~4 B/slot × total orders.
   At ~33M orders (~130 MB + a doubling copy) it OOM'd the members' then-512m heap on the
   LEADER mid-apply. Inherited from the YU01 bounded-retention design (the RETENTION is
   bounded; the INDEX never was) — it affects the whole lineage, not just YU13. It also costs
   throughput before it kills: run 3 of the confirmation set decayed to 109k booked/s as the
   array (and GC pressure) grew. **Fix identified:** replace with an `Int2ObjectHashMap`
   bounded by open+retained orders (snapshot iteration must sort refs for determinism); needs
   the full gate suite + HA re-proof — a session of its own, filed as follow-up.
2. **The OOM cascaded into a wedge via undersized /dev/shm**: the recovering member's replay
   publication needed a 201 MB log buffer against 178 MB usable shm (512Mi limit).
3. Mitigations applied + deployed: member heap 512m → 1536m, shm 512Mi → 1Gi, pod memory
   2Gi → 4Gi (each member is alone on a 16 GB node). This moves the wall ~4× out
   (~100M+ orders/epoch); it does not remove it — the index fix does.
4. ~5.6M orders in these runs were risk-rejected with no booked record. CORRECTED attribution
   (2026-07-21, after the soak session found the credit wall below): originally read as
   PRICE_STALE under apply backlog, but the confirmation runs all used ONE account (62654,
   ~30.5M cumulative orders) and the counter froze at trades=30,744,570 — within 4 of the
   soak's measured `CREDIT_LIMIT` wall at ~30.7M orders of 500 × $150. These were credit-wall
   rejections; run 3's "decay to 109k" was substantially the wall cutting in, on top of index
   growth. Still the 15c3-5 gate fail-closing correctly — but the right slide line is the
   credit gate, not staleness.

### ordersByRef index fix + 136M-order soak (2026-07-21) — the leak is gone

**Fix (commit a6776e8).** `ordersByRef` is now an Agrona `Int2ObjectHashMap<RestingOrder>`
bounded by open + retained-terminal orders, presized at construction (2× the steady population
of terminalCap + pool over a 0.55 load factor) so it never rehashes after warmup — Epsilon
`noGcTest`/`riskNoGcTest` double as proof of zero steady-state map allocation. Snapshot
serialization takes `snapshotOrderRefsAscending()` (sorted copy, cold path) to preserve the
established ascending-ref byte order; retained terminal rows still follow in exact
eviction-FIFO order (ADR-046). `openOrderTuples`/`allOrderTuples` sort by ref so the
executable byte-order contract test is unchanged. Gates: full suite 228/0, all four
allocation gates, both Epsilon gates green.

**Soak (GKE, fresh epoch, same 3×c3 members + 3 gateways, image `cluster-node:yu13`
@sha256:9c77663).** conc 48 × batch 200, SIDES=alternate QTY=500 LIMIT=150 TICKERS=JPM,GS,COF,
8 s `/seed` refresher, account rotated per 120 s chunk (see trap below):

| Measure | Result |
|---|---|
| Cumulative booked trades | **136,400,990 in ~14.6 min** (leader trade counter) |
| Booked rate, 30 s windows | 130–170k/s throughout; **no downward trend 18M → 136M** |
| First vs last fresh-account chunk | 143.6k/s (chunk 1) vs **150.7k/s (chunk 7)** — the old code was at 109k/s by 30M |
| Member parity | applied=136,402,195 and trades identical on all 3 members; 3 snapshots taken mid-soak, no divergence — the sorted-ref snapshot path proven live |
| Heap / stability | working set plateaus ~0.9–1.0 Gi on 4 Gi pods, flat vs order count; **0 restarts** |
| Book | returns to ~0 open orders after load stops |

Bonus stress from the aborted first attempt (same image, prior epoch): ~159M orderRefs issued /
126M applied — ~96M of them risk-rejected — with all members healthy. Under the old flat array
that epoch alone is a ~640 MB index per member plus a doubling copy: certain OOM.

**New trap — the per-account credit wall.** `executedNotional` accumulates GROSS per fill and is
never released; against `CREDIT_LIMIT_TICKS = Long.MAX_VALUE/4` an account walls after exactly
~30.7M booked orders of 500 × $150 (first soak attempt stalled at trades=30,744,566 with orders
still applying — every one CREDIT_LIMIT-rejected). HTTP-level counters show `failed=0` because
the batch endpoint 2xxes; the tell is the trades counter freezing while `applied` keeps rising.
Long benches must rotate accounts (7 seeded accounts ≈ 215M-order budget per epoch).

### Where the next throughput lives (gateway tier analysis, ranked)

The engine does 1.13M orders/s in-process on this hardware; REST books 165k. The gap is the
gateway tier, whose structure is: 64 HTTP threads (JSON parse) feeding ONE owner thread per
gateway that does resolve→encode→offer→pollEgress for every order, with committed acks
returning on a 64k-term egress channel that DROPS under load (a lossy batch then burns its
full ~10 s ack budget — the batch-500 cliff and the single-stream batch-200 stalls are both
this).

1. **More gateways × guaranteed spread** (done for 3; scale replicas with nodes) — measured
   2× today; scales roughly linearly until members/consensus saturate.
2. **Fix the lossy-batch full-budget wait**: complete a batch on the ack high-water mark
   (appliedSeq ≥ last offered) instead of requiring every individual ack — removes the batch
   cliff, unlocks batch 500–1000 (fewer HTTP round-trips per order).
3. **Raise the egress/ingress term buffers** (64k → 1–4M) — directly cuts ack drops, the root
   of both stall modes.
4. **Binary/SBE ingress or leaner JSON** (Jackson tree-parse per order on HTTP threads) —
   secondary; CPU is currently gateway-node-bound before parse cost dominates.
5. **Bypass HTTP for machine flow** (Aeron ingress client / the FIX gateway) — the REST hop
   is a convenience contract, not the product's fast path.

## Added later — tracing across consensus, and the KDB-X capture tap (2026-07-27 / 2026-07-29)

Specified in the addendum in `spec.md`, decided in `system/adr-060`. Neither changes the wire
shapes, the replicated log, the snapshot format, or what the state machine reads.

| File | Role |
|---|---|
| `cluster/OrderTrace.java` | Trace identity, the member's parent span and the head sampling verdict, derived on both tiers by a pure function of the client idempotency key the log already carries. |
| `cluster/SpanSink.java` | The asynchronous sink. Producer copies a fixed record into a pre-allocated Agrona ring buffer and returns; a full ring drops and counts; one daemon thread does every format, batch and HTTP call. Null unless `OTEL_TRACES=1`. |
| `cluster/ClusterGatewayMain.java` | Emits the gateway's `gateway.queue` and `cluster.consensus` spans, both parented to the root, from the owner thread with the committed ack byte already in hand — which is what makes escalating a reject at the head possible at all. |
| `cluster/KdbTapWriter.java` | The leader-side, off-consensus KDB-X capture tap, third sibling of `TradeNatsPublisher`/`OrderNatsPublisher` in the same output-ring drain. Inert unless `KDB_TAP_DIR` is set; stops at `KDB_TAP_MAX_MB` (default 256). |
| `kubernetes-runtime/manifests/base/observability-prometheus-configmap.yaml` | The cluster tier's first Prometheus scrape — per pod through the headless service. |

### Layer coverage — read this before quoting the capability

`ClusterGatewayMain` is overridden at `YU12` and `YU13` only, so this state's copy is operative on
every descendant and the **gateway-side spans hold on generated `YU13`, `YU14` and `YU15` alike.**

The member-side spans (`cluster.commit`, `cluster.apply`) and the tap's construction both live in
`MatchingEngineClusteredService`, which `YU13`, `YU14` and `YU15` **each override** — so only a
state's own copy is operative when that state is generated. Measured across the layers:

| Generated state | Gateway spans | Member spans | KDB-X tap wired |
|---|---|---|---|
| `YU13-limit-order-book` | yes | **no** | yes |
| `YU14-listed-equity-options` | yes | **no** | **no** |
| `YU15-eod-risk-extract` | yes | yes | yes |

So a whole trace crossing consensus is live on **generated `YU15` only**, which is consistent with
the proofs being named `yu15-otel-*` and the suite running on the YU15 rig. The `YU14` tap gap is
different in kind: `YU14`'s override was cut from a pre-tap `YU13` and never carried the wiring
forward, so generated `YU14` ships `KdbTapWriter.java` on disk with nothing constructing it. That
is the standing dead-layer trap (the same shape as the `BlpRiskState` YU14 override), not a
deliberate scope decision — closing it is a hand-merge into `YU14`'s clustered service.

**The trace an order produces:**

```
order                (traderx-cluster-gateway)   root — the residence the client experiences
├── gateway.queue    (traderx-cluster-gateway)   submit → offer cleared into the log
└── cluster.consensus(traderx-cluster-gateway)   THE BLACK BOX: offer → committed ack
    ├── cluster.commit (traderx-cluster-member)  sequenced → apply start, leader clock
    └── cluster.apply  (traderx-cluster-member)  match + emit
```

**Verified:**
- `OrderTraceTest` — both tiers reach the same trace id, parent span and sampling verdict from the
  same key; a rejected order force-samples on both sides independently.
- `SpanSinkTest` — a full ring drops and counts rather than blocking the producer.
- `KdbTapWriterTest` — non-blocking offer, drop accounting, epoch-qualified rows, the
  `KDB_TAP_MAX_MB` ceiling, and an unregistered ticker captured as `#<id>` rather than dropped.
- `AeronClusterSpikeTest.leaderTapCapturesTheAppliedSessionForKdb` — a real Aeron cluster applying
  real consensus ingress, whose Java assertions pin the captured session against the engine's own
  trade counter. Its output is the committed fixture the YU07 q gate runs on.
- `scripts/proofs/yu15-otel-trace-join.sh` — one order produces one trace spanning both tiers,
  against a deployed cluster.
- `scripts/proofs/yu15-otel-reject-trace-log-join.sh` — a rejected order's log line resolves to
  that order's own trace by the derived id.
- The allocation gates and the Epsilon no-GC run stay green with both compiled in.

**What already existed, and what was actually missing:** the Kubernetes runtime has shipped an OTel
Collector, Tempo, Loki, Prometheus, Grafana and promtail since state `007`. What was missing was
that **nothing emitted to the collector** — zero OTLP emitters anywhere in the tree — and that
**Prometheus never scraped the Aeron cluster tier at all**, so `traderx_cluster_next_order_ref`,
the committed ground truth, existed on `/metrics` and reached no dashboard. Both are wired now.

**Gotcha recorded:** on kind the observability stack and the cluster bring-up land in *different*
clusters, so the collector endpoint resolves to nothing and the trace pipeline is **silently** dead
— orders book fine, spans go nowhere, and the only symptom is an empty Tempo.
`scripts/yu15/start-observability-kind.sh` exists to prevent exactly that.

## Open

- ~~The `ordersByRef` unbounded-index bug~~ — **fixed and soak-proven 2026-07-21** (136M booked
  orders, no decay, stable heap; section above). 165k booked/s is now "sustained" unqualified.
- Deferred: async snapshot off the hot thread (still nothing points at it),
  Epsilon/isolcpus/AOT tail rungs, GKE cluster NetworkPolicy (safe now ports are pinned).
