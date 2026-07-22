# RECAP — C4D decline settled, four-gateway retest, read-model characterization

> 2026-07-22 (America/New_York, late evening 07-21 local). Worktree
> `/Users/yaakov/dev/lmax/traderX-YU13-limit-order-book`, branch `YU13-limit-order-book`.
> Continues `RECAP-2026-07-21-gateway-throughput-campaign.md`; same measurement contract, same
> harness (`/private/tmp/run-gateway-campaign-once.sh`), same image digests (member
> `sha256:20dfd2bd…`, gateway `sha256:ba2ac500…`) — no service was rebuilt. Evidence rows appended
> to `scripts/bench/results/gateway-throughput-campaign-2026-07-21.csv`. Committed locally, never
> pushed.

## 1. The C4D bank's monotonic decline: settled — it was NOT epoch accumulation

Three fresh banked rows at 3gw/b1000/c64, first on a fresh epoch (account 44044, after the standard
20 s warmup) and then two more back-to-back in the SAME epoch (52355, 62654):

| Row | Account | Booked/s | Ack loss |
|---|---|---:|---:|
| fresh-epoch r1 | 44044 | **320,844.71** | 2.31% |
| same-epoch r2 | 52355 | **342,827.62** | 1.83% |
| same-epoch r3 | 62654 | **320,064.65** | 2.27% |

All bank gates passed (zero failures, applied/trade parity, fences bounded, zero high-water
timeouts, book empty, zero restarts). ~60M orders through one epoch with **no decline and no
ack-loss growth** — the 07-21 monotonic slide (279.0 → 256.4 → 238.4k, ack 1.72 → 2.34 → 2.81%)
does not reproduce under an idle read model.

The variable that separates the two days is the trade-processor. On 07-21 it was alive and
increasingly backlogged during the declining rows (and the one fast late-epoch row — the excluded
296.8k sample — ran exactly as it OOM-restarted). Today it was down (crashlooping, §3) during all
three rows, and the whole band sits 60–100k/s higher. Leading explanation: the read-model side
load (leader NATS bridge fan-out + trade-processor + MariaDB on the shared small nodes) both
depressed and progressively dragged the 07-21 bank. §4 records the direct cross-check.

**Headline consequence:** the "279k vs 256k" question dissolved. With the read model quiescent the
three-gateway configuration banks a **320.8k/s median** under the full gate set. The 07-21 numbers
stand as "with degraded read-model side load" rows; quote whichever matches the claim being made,
never blended.

## 2. Four gateways on C4D: rejection overturned, retained at four

The C3-era rejection rested on a saturated leader (2.80/3 cores, ~255k applied spread). On C4D the
leader idles ~1.28/3 at 256k. Retest: std-pool grown 2→3 (one e2-standard-2 added) so four
gateways keep required one-per-node anti-affinity on non-member nodes; fresh epoch reset + re-seed;
standard warmup; three banked rows at b1000/c64 (trade-processor down, same condition as §1):

| Row | Account | Booked/s | Ack loss |
|---|---|---:|---:|
| r1 | 11413 | **425,972.86** | 1.69% |
| r2 | 22214 | **422,427.37** | 1.76% |
| r3 | 42422 | **382,115.73** | 2.09% |

Median **422,427/s** vs the three-gateway 320,845/s median under identical conditions: **+31.7% at
fixed b1000/c64**. Member CPU samples during the runs put the leader at ~1.6–1.8/3 cores peak
(followers lower) — no member ceiling in sight; the next knee is client/gateway-side.

Source updated and committed (`d3fb201`): `gateway.yaml` replicas 3→4 with the C4D rationale. The
live deployment runs 4/4 on four distinct non-member nodes. Note the standing cost: the retained
tier now needs four non-member nodes (default-pool + three std-pool).

## 3. Trade-processor: the crashloop root cause was the settlement sweep, not the floods

Found in CrashLoopBackOff (52+ OOMKilled restarts over ~18 h, dying ~40–46 s after every boot,
even idle). Root cause chain, each step verified live:

1. The 07-21 floods left **3.66M `Processing` trades** in MariaDB (the epoch resets wipe cluster
   state, never the read-model DB).
2. `SettlementService.sweep()` (every 5 s, `@Transactional`) hydrates **every** due Processing
   trade into one `List<Trade>` — no paging. All 3.66M were T+1-due. Boot (~20 s) + hydration
   (~25 s) → container OOMKill at the 1Gi limit, forever. Floods never killed it directly; they
   left the corpse pile the sweep choked on.
3. Two further unbounded-memory paths confirmed at flood time: `TradeFeedHandler.pending` is an
   unbounded in-heap queue (no backpressure on the NATS dispatcher), and the broker cuts the
   subscriber as a **Slow Consumer** at 64MB MaxPending / 10 s write deadline (observed in broker
   logs), after which delivery stops until reconnect.
4. Also: `-Xmx820m` inside a 1Gi limit leaves ~150MB native headroom — too thin for
   Spring+Hibernate+NATS even without the sweep.
5. Supply side: only the **leader** publishes to NATS through a bounded 64k ring
   (`TradeNatsPublisher`, drop-and-count). Broker counters showed 28.3M of the 4gw bank's 74.4M
   booked trades delivered (62% dropped at the bridge under flood — by design, best-effort).
6. `ReconciliationService` targets the old `order-matcher` service (0/2 Pending on cluster
   deployments) — every sweep logs ConnectException and skips. Recon is inert on YU12+ stacks.

Live remediation (no source change; hand-patched GKE deployment): memory 1Gi→4Gi,
`-Xmx820m`→`-Xmx3200m`, maxSurge=0. The application's own settlement sweep then cleared the 3.66M
backlog through its normal code path. A TRUNCATE was deliberately not used — rows preserved.
Source-side fixes (paged sweep, bounded intake queue, recon repoint, manifest resource sync) are
flagged as a separate follow-up task.

## 4. The read-model ceiling, measured

With the backlog cleared and the 4Gi pod stable, a controlled 5 s burst (54,800 orders, ~11k/s
inbound) was fully absorbed with zero drops and zero restarts, then drained to MariaDB:
**54,900 rows persisted over ~150 s ≈ 365 rows/s sustained** (per-poll band ~200–580/s, warming
upward). That is the trade-processor→MariaDB persist ceiling in current form (batch 100 intake →
`saveAll` + per-trade re-publish).

So the two numbers the talk must keep separate:

- **Engine/cluster:** 320,845/s booked (3gw median) and 422,427/s (4gw median), full bank gates.
- **End-to-end read model:** ~365 trades/s persisted-and-queryable. Above roughly that inbound
  rate the read model falls behind; under flood it is *designed* to sample, not keep up — the
  leader bridge drops beyond its 64k ring (62% dropped in the 4gw bank) and the broker cuts the
  subscriber at 64MB pending / 10 s write stall.

Direct cross-check of the §1 hypothesis — one banked b1000/c64 row with the trade-processor
**alive** (account 44044, four gateways): **382,383.77/s**, ack 2.29%, gates passed, and the
trade-processor survived on 4Gi (two broker slow-consumer cuts + reconnects during the flood,
zero restarts, backlog draining after). That row equals the lowest tp-down row (382,116) and sits
~9% under the tp-down median — consistent with a modest live-read-model drag, but within observed
spread, so not conclusive alone. What IS established: the 07-21 monotonic decline does not
reproduce with a healthy-or-absent read model, and the 07-21 rows were taken against a 1Gi
trade-processor OOM-thrashing on the shared gateway nodes.

### Cold plateau note (optional item, deliberately not run)

The pre-touch experiment (1 MiB Aeron term buffers) was skipped: today provided two fresh
epoch-reset + rollout sequences and neither produced a cold row — the first post-reset warmups
measured 372k and 327k over 20 s, and the first banked rows 320.8k and 426.0k. The plateau's
recurrence is now weaker than on 07-21; if it returns, instrument first (recap item 4 of 07-21)
before changing allocation code.

## 5. Reporting boundaries (unchanged and extended)

- Batch-path booked/s (this document) stays distinct from synchronous per-order REST.
- 345 ms node-clock role transition stays distinct from client-gap failover; idle stays distinct
  from saturated recovery.
- **Engine/cluster booked-per-second is not end-to-end booked-and-persisted.** The cluster books
  at 320–426k/s; the read model persists at the §4 rate. State both, never one as the other.
- Read-model-quiescent rows (07-22) and read-model-loaded rows (07-21) are different conditions —
  label them.
- The 07-21 recap's "no 300k claim" applied to that campaign's banked set; today's 320.8k (3gw)
  and 422.4k (4gw) medians are new banked rows under the same gates, with the read-model-down
  condition stated.
