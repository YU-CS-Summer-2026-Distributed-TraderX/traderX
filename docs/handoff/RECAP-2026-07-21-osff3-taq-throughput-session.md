# RECAP 2026-07-21 — OSFF-3 closed out: real TAQ flow, T-LOB16 root-caused, 2× throughput, index leak fixed

> Session recap, fable lane, `traderX-YU13-limit-order-book` worktree. Untracked working note,
> per house convention. Authoritative detail lives in
> `specs/YU13-limit-order-book/generation/implementation-status.md`; this is the narrative.
> Companion: the ordersByRef fix ran as a spawned parallel session (its commits are on this
> branch). **Nothing pushed anywhere — 5 new commits on `YU13-limit-order-book`
> (`f03b1b4`, `a3fd922`, `69117b1`, `cc9928d` + fix-session `a6776e8`/`ca6dde9` + `652fe06`),
> 1 on `YU12-aeron-cluster` (`f9690b7`).**

## Where the headline numbers now stand (all crossing-engine, all re-measured)

| Question | Number | Provenance |
|---|---|---|
| Sustained REST throughput | **130–170k booked/s, no decay across a 136.4M-order soak (~14.6 min)** | fix-session soak, leader counter, fresh accounts rotated |
| Before today | 75k (T-LOB15) — the gap was gateway topology + two state walls, all found today | |
| In-process engine (journaled) | **1,134,658 orders/s** on GKE-class c3 (1.56M on the laptop); output ring 3.8–5.3M **events**/s — a remembered "6M/s" is events/s, not orders/s | driver test, harness fixed for crossing |
| Engine match op | p50 352 ns insert / 870 ns cross; tuned pinned host collapses the tail ~7–13× (cross p99.99 83.6 µs → 12 µs) — "the tail is the host, not the book" | c3 VM, 2 runs |
| Wire-to-wire REST (1 order) | p50 1.42 ms @100/s (unsaturated row; coordinated-omission-safe) | unchanged from earlier measurement |
| Failover (client gap, ≤20 ms probe) | median ~200 ms, worst ~450 ms; spot-check on the final build 124/348/223 ms rotated kills | |
| Saturated failover | ~8–12 s to full-throughput recovery — different question, backup slide only, never mixed | framing decision documented |

## 1. Real TAQ order flow (the demo beat) — DONE

- Curated **1,138,793 real NYSE prints** (AAPL MSFT NVDA TSLA AMZN META JPM — odd count on
  purpose — 2025-03-03 09:30–10:00 ET, $18.2B notional) from the YU07 tick store, DuckDB on the
  tick-store pod. `scripts/bench/taq-curate.py`; slice CSV gitignored (reproducible).
- **Trade-print replay**: each print → passive limit + aggressor limit at the historical price;
  the pair is order-insensitive, so a sequenced replay reproduces every print exactly and the
  book self-cleans to empty. `scripts/bench/taq-replay.mjs` (paced / max modes).
- Flagship run (paced 5×): pacing held exactly (360.2 s), **0.019% rejects** (incl. both sides
  of two 2.1M-share NVDA opening blocks vs the 1M order-size cap — the gate working), book
  returned to exactly empty, **all three members byte-identical after 2.4M trades**. Booked
  rate traced the real open: ~20k/s → ~4k/s.
- Traps recorded: band anchors on the epoch's FIRST limit (bench-at-150 epochs collar real
  prices — fresh epoch first); exact sequencing requires ONE gateway pod (the Service
  round-robins); batch >100 prints/POST stalls on egress-ack loss; `kubectl cp` silently
  truncates ~35 MB files (gzip + `exec cat` + md5).

## 2. T-LOB16 — resolved: it was our NetworkPolicy, not Aeron, not stale PVCs

- Reproduced the wedge on kind with a genuinely fresh empty PVC (stale-PVC hypothesis dead),
  then noticed the leader saw ZERO replication traffic → connectivity, not consensus.
- Root cause: `ClusterNodeConfig` left three channels on ephemeral ports (`:0`, the Aeron
  sample default); the kind NetworkPolicy admits UDP 21800–22200 only, so
  FOLLOWER_LOG_REPLICATION traffic was silently dropped. PVC restarts hid it (FOLLOWER_CATCHUP
  uses the in-block transfer port); GKE never applied the policy.
- Smoking gun: deleting the policy un-wedged a 10-min-stuck joiner in <10 s.
- Fix: ports pinned at offsets 6/7/8 (committed in BOTH lanes). Acceptance with the policy ON:
  empty-disk rejoin into an aged 3-term cluster converges <30 s. Failover unchanged after.
- The 2026-07-19 GKE `ArchiveException` term-poisoning wedge is a DIFFERENT mechanism — triage
  by signature: exception storm → term poisoning; silent loop → check replication-port
  reachability first. Issue doc carries the split.

## 3. Throughput: 75k → ~150k sustained, and the two walls behind it

- **Gateway topology was half the story**: no anti-affinity meant the scheduler could pack all
  three gateways on one 2-vCPU node (observed) — that topology is the old ~75k ceiling. Spread
  one-per-node: **146,500 / 148,031 / 164,949 / 163,755 booked/s** across four fresh-epoch
  60 s runs. `podAntiAffinity` added (note: `preferred` does not survive a rolling update —
  recycle pods sequentially).
- **Batch size is a cliff**: batch 200 is the knee; ≥500 collapses on owner-thread queueing
  past the client timeout (retro-explains the 07-20 "batch=1000 gave 2k/s" mystery).
- **Wall #1 — `ordersByRef` unbounded index (engine bug, inherited from 009b)**: flat array
  grew with the highest ref ever issued, never shrank — OOM'd the leader at ~33M orders on the
  then-512m heap, degrading throughput before killing. **FIXED** (spawned session,
  `a6776e8`): bounded presized `Int2ObjectHashMap`, snapshot walks sorted refs preserving
  ADR-046 eviction order; 228/0 tests, all four allocation gates, Epsilon. **Soak-proven**
  (`ca6dde9`): 136.4M booked trades, 130–170k/s every 30 s window, no decay 18M→136M, heap
  flat ~1Gi, members byte-identical, 3 mid-soak snapshots, 0 restarts.
- **Wall #2 — the per-account credit wall (NEW trap)**: `executedNotional` accumulates gross
  fills forever; at the default cap an account walls after ~30.7M orders of 500×$150. The
  tell: trades counter freezes while applied rises, HTTP stays 2xx. Rotate the 7 seeded
  accounts in long benches. This retroactively re-attributed the sweep postmortem's "~5.6M
  PRICE_STALE rejects" and much of run 3's decay → credit wall (corrected, `652fe06`).
- Ops sizing fixed alongside: member heap 512m → 1536m, shm 512Mi → 1Gi (a 201 MB replay
  buffer couldn't fit during the OOM cascade), pod 2Gi → 4Gi.

## 4. Honesty corrections this session made (feed these to the slides)

- Every number above is crossing-engine; pre-YU13 numbers stay void.
- "6M/s BLP" = output events/s, not orders/s. Quote 1.13M orders/s in-process.
- The 135k "burst" from 07-20 is superseded by a measured, soak-proven 130–170k/s.
- Tail slides quote percentiles, never max (a reproducible 1-in-250k ~2.6 ms JVM-event outlier
  exists on the market path; p99.99 is ~12.6 µs on the tuned host).
- Failover: idle ~200 ms and saturated ~8–12 s never share a slide.

## Cluster / environment state at session end

- **GKE**: fixed image `cluster-node:yu13@sha256:9c7766…`, fresh-epoch cluster healthy,
  seeded, gateways spread one-per-node, new sizing applied. (yaakov scales to 0 when done.)
- **kind** `traderx-yu12-cluster`: 3 members + gateway on the port-pinned image, NetworkPolicy
  enforced, T-LOB16 acceptance state. T-LOB14-era PVCs were deleted during the retest.
- Temp GCE VM `bench-tuned` created and deleted (twice). Bench artifacts on the `bench-runner`
  pod: `/taq-full.csv`, `/taq-replay.mjs`, `/batch-load.mjs`, `/failover-client-probe.mjs`.

## OPEN OPTION — the next 2×: gateway-tier levers (ranked, ready to pick up)

The engine does 1.13M orders/s in-process; REST books ~150k. The gap is one owner thread per
gateway doing resolve→encode→offer→pollEgress per order, behind 64 JSON-parsing HTTP threads,
with committed acks returning on a 64k-term egress channel that drops under load (a lossy
batch then burns its full ~10 s ack budget — the batch-500 cliff and the single-stream stalls
are both this). In ROI order:

1. **Ack high-water-mark batch completion** — complete a batch when the ack stream's
   appliedSeq passes the last offered order, instead of requiring every individual ack.
   Removes the batch cliff entirely; unlocks batch 500–1000 (fewer HTTP round-trips/order).
   One gateway-file change + live A/B.
2. **Raise ingress/egress term buffers 64k → 1–4M** (`ClusterGatewayMain` channel URIs) — cuts
   the ack drops at the root. Trivial change; measure drop rate before/after under load.
3. **Scale gateways beyond 3 with guaranteed spread** — measured ~linear today; needs nodes
   (std-pool is 2× e2-standard-2 + default-pool). Cheap nodes, linear until consensus
   saturates.
4. SBE/binary batch ingress (drop per-order Jackson) — secondary until CPU says otherwise.
5. Machine flow bypasses HTTP entirely (Aeron ingress client / FIX gateway) — the REST hop is
   a convenience contract, not the fast path.

Realistic expectation: levers 1+2 alone plausibly clear 300k booked/s on today's hardware;
lever 3 rides whatever nodes you give it. Each is a clean measured before/after — ideal talk
material if the schedule allows one more measurement day.
