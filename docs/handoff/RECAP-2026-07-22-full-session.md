# RECAP — full session, 2026-07-22: decline settled, four gateways, read model, profile, transport

> Worktree `/Users/yaakov/dev/lmax/traderX-YU13-limit-order-book`, branch `YU13-limit-order-book`.
> Continues `RECAP-2026-07-21-gateway-throughput-campaign.md` under the identical measurement
> contract and harness (`/private/tmp/run-gateway-campaign-once.sh`). Detailed per-task write-up
> lives in `RECAP-2026-07-22-c4d-decline-4gw-readmodel.md`; this file is the session-level index.
> Commits `d3fb201`, `edf8c74`, `48ec357` — local only, nothing pushed. **GKE scaled to zero at
> session end.**

## Headline

**149,878 → 438,109 booked orders/s (+192%)** across the campaign, same contract throughout. The
07-21 campaign banked 256.4k; this session raised it to 438.1k and, more importantly, replaced
"we don't know what limits us" with a named constraint backed by thread-level measurement.

| Configuration | Banked rows (booked/s) | Median | Ack loss |
|---|---|---:|---:|
| Campaign start — 3gw, 64 KiB, C3, b200/c48 | 141.5 / 149.9 / 157.3k | 149.9k | ~22% |
| 07-21 banked C4D — 3gw, 1 MiB, b1000/c64 (read model thrashing) | 279.0 / 256.4 / 238.4k | 256.4k | 1.7–2.8% |
| 3gw, read model quiescent | 320.8 / 342.8 / 320.1k | 320.8k | 1.8–2.3% |
| 4gw (source-retained tier) | 426.0 / 422.4 / 382.1k | 422.4k | 1.7–2.1% |
| **4gw + de-tenanted nodes (final live config)** | 438.1 / 441.9 / 423.1k | **438.1k** | 1.8–2.2% |

All rows passed the full gate set: zero HTTP failures, exact member applied/trade parity, book
empty after every run, zero high-water fence timeouts, zero restarts, fences bounded by offers.

## What each task established

**1 — The 07-21 monotonic decline was not epoch accumulation and not variance.** Three fresh
banked rows (one after a fresh epoch, two more back-to-back in the same epoch, ~60M orders total)
showed no decline and no ack-loss growth. The separating variable was the trade-processor: alive
and OOM-thrashing on 07-21, down today. The "279k vs 256k" headline question dissolved — with the
read model quiescent, three gateways bank 320.8k.

**2 — The four-gateway rejection is overturned; four is now the retained tier.** The C3-era
rejection rested on a leader saturated at 2.80/3 cores. On C4D the leader peaks ~1.6–1.8/3, and
four gateways banked a 422.4k median against 320.8k for three at identical load — **+31.7%**.
Committed as `gateway.yaml` replicas 3→4 (`d3fb201`). Standing cost: four non-member nodes.

**3 — The read model's ceiling is ~365 trades/s persisted, and its crashloop had a different root
cause than assumed.** The trade-processor's 52-restart CrashLoopBackOff was *not* flood damage: the
floods left 3.66M `Processing` rows in MariaDB, and `SettlementService.sweep()` hydrates every due
trade unpaged into one transaction — OOM at the 1Gi limit ~45s after every boot, forever, even
idle. Hand-patched live to 4Gi/-Xmx3200m; the application's own sweep then cleared the backlog
(2.97M settled in one pass, no SQL mutation). Measured ceiling afterward: 54,900 rows over ~150s.
Also confirmed: unbounded `TradeFeedHandler` intake queue, broker slow-consumer cuts at 64MB/10s,
62% of trades dropped at the leader's bounded bridge under flood (by design), and
`ReconciliationService` pointing at a service that no longer runs.

**4 — The bottleneck is gateway-node CPU exhaustion by co-tenancy, not any service thread.**
Per-thread CPU accounting at the retained operating point: gateway owner thread and embedded
SHARED driver ~60–65% duty each, HTTP/JSON ~2%, consensus module ~18%, leader driver thread the
hottest single thread at ~72% of a core. Nothing saturates; c96 and c128 do not raise throughput;
the load generator uses 0.25 cores. The controlled tp-alive row gave the mechanism exactly: the
gateway sharing a node with the trade-processor collapsed 1.24 → 0.58 cores, −5.1% fleet-wide.
Ranked levers: (1) gateway isolation/CPU discipline, (2) receiver-side transport scheduling,
(3) consensus batching — not implied at 18%, (4) binary ingress — rejected, HTTP is ~2%.

**5 — De-tenanting alone is worth +3.7% and most of the variance.** The full tuned-pool experiment
is blocked on quota (below). The quota-neutral half — evicting NATS and bench-runner onto one
sacrificial node — moved the median 422.4k → 438.1k and tightened the low-side spread from 382k to
423k. The variance in earlier banks *was* the co-tenancy.

**6 — The term-size question is closed with a mechanism, not a search.** A 2 MiB build banked
350.1k at 0.15–0.25% ack loss: 15× cleaner acks, 20% less throughput. Aeron counters explain both
directions — loss originates as egress UDP overrun (~55k NAKs across gateways and ~54k leader
sender back-pressure events per 20s at 1 MiB). Small terms rotate past gaps so the client skips
them and stays fast; large terms keep gaps repairable, but ordered egress **head-of-line blocks**
on every gap awaiting retransmit, delaying fence acks and throttling batch pipelining. 4 MiB is the
same mechanism pushed past the 10s ack timeout — precisely the 3/3 timeout rejections of 07-21.
**1 MiB retained**; source and live state restored to digest `ba2ac500`.

## Numbers that must stay separate

- **Cluster/engine booked/s** (everything above) vs **end-to-end read model** (~365 trades/s
  persisted) — three orders of magnitude apart; never blend.
- **Batch path** vs synchronous per-order REST.
- **1 MiB rows** vs **2 MiB rows** — different operating points, not a before/after.
- **Read-model-quiescent** vs **read-model-loaded** rows — label the condition per row.
- **345 ms node-clock role transition** (idle, system-facing) vs **~200 ms median client gap** vs
  **8–12 s saturated recovery** — three claims, three slides.
- In-process engine 1.13M orders/s is an engine figure, not a cluster figure.
- No 300k-class claim without naming the gateway count, term size and read-model state.

## Live state at session end

- **All node pools scaled to 0** (`blp-c4d-tuned-pool`, `default-pool`, `std-pool`); zero compute
  instances; both cronjobs (`eod-session-close`, `yu12-snapshot-backup`) **suspended** so they stop
  spawning pods against a dead cluster. Unsuspend on next bring-up.
- Source retained config: 4 gateways, 1 MiB terms, C4D members. Gateway image digest
  `ba2ac500…`; the 2 MiB experiment image remains in GAR as `cluster-node:yu13-2m`.
- Live trade-processor was hand-patched to 4Gi/-Xmx3200m; **manifest source still carries
  1Gi/-Xmx820m** — sync is part of the separate trade-processor fix session.
- `gateway.yaml` replicas 3→4 (`d3fb201`) **needs a hand-merge into YU14**, which shadows that
  file. Not a cherry-pick.
- Destructive actions performed: five member epoch resets with re-seeds, std-pool grown 2→3, both
  gateway image rolls (2m and back), trade-processor bounce/re-resource, bench-runner recreated on
  a different node, NATS broker moved by nodeSelector, all pools scaled to zero.

## Blocked and next

**Blocked:** the full gateway CPU-discipline experiment (4× `c2d-standard-8`, SMT off, static CPU
manager, Guaranteed integer-CPU gateways, private nodes) needs `CPUS_ALL_REGIONS` raised 32 → 64.
That requires upgrading off the GCP free trial, which yaakov declined for now. Do not re-propose
until he raises it. `C2D_CPUS` itself is free (0/100); `IN_USE_ADDRESSES` is 7/8 so any new pool
must be private (Cloud NAT already exists).

**Next, in value order when work resumes:** (1) the tuned gateway pool, once quota allows — the
profile says it is the highest-value remaining lever; (2) receiver-side driver tuning
(socket buffers, dedicated cores) as the real attack on the ~2% egress loss; (3) the
trade-processor source fixes and manifest sync from the parallel session.
