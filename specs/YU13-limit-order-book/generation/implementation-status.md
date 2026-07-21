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

Full evidence in `docs/handoff/PROOF-yu13-kind-ha-crossing-book.md`. Fixture: ten asks @150.050
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
are always distinct. `scripts/bench/taq-replay.mjs`:

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

## Open

- Tail latency on a tuned host (OSFF-3 #2) — see the scoping in the OSFF-3 handoff STATUS:
  a dedicated-VM engine-bench rerun is feasible on GCP; ef_vi is not acquirable there and
  DPDK needs the commercial Aeron premium media driver, so the talk keeps the honest line —
  nanoseconds in the engine, microseconds wire-to-wire in software, nanoseconds only in silicon.
