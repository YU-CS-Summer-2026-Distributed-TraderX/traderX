# RECAP — YU13 gateway throughput campaign and C4D rollover

> Completed 2026-07-21 (America/New_York). Worktree:
> `/Users/yaakov/dev/lmax/traderX-YU13-limit-order-book`, branch `YU13-limit-order-book`.
> This lane changed `ClusterGatewayMain` and GKE only. Reference data and the risk gate were not
> edited. Git was committed locally and was **not pushed**.

## Outcome

The clean starting point was a three-gateway, three-C3-member deployment with 64 KiB Aeron terms,
batch 200 and concurrency 48. Its three 60-second runs booked
`141,480.17 / 149,878.21 / 157,317.23 orders/s` (median **149,878.21**), with roughly 22% of fill
acks unobserved by the gateways. The final retained configuration is:

- applied-sequence high-water batch completion;
- 1 MiB ingress and egress Aeron term buffers;
- three gateways, with required one-per-node anti-affinity;
- three `c4d-standard-8` members in `us-east1-b`, CPU Manager `static`, `threadsPerCore=1`, compact
  placement, Guaranteed QoS and cpuset `1-3` per member.

On C4D, the predeclared three-run bank at batch 1000 / concurrency 64 booked
`278,987.03 / 256,402.66 / 238,372.45 orders/s`: mean **257,920.71**, median **256,402.66**,
47,041,000 total booked orders, zero HTTP failures, zero member/gateway restarts, empty book after
every run, and exact member applied/trade parity. Median ack loss was **2.34%**. This is +71.1%
against the original median, but the campaign did **not** bank 300k/s; do not promote a burst or
the excluded run below into that claim.

## Measurement contract

All reported throughput rows are the pipelined `/orders/batch` contract, in-cluster, with odd
ticker set `JPM,GS,COF`, alternating sides, real seeded SQL accounts rotated, sustained for 60 s.
The bank gate required zero request failures, no restart delta, no high-water timeout delta,
open-orders gauge returning to zero, and exact applied/trade parity on all members. Do not mix
these rows with synchronous one-order REST measurements or in-process engine results.

The local ignored evidence file is
`scripts/bench/results/gateway-throughput-campaign-2026-07-21.csv`. The temporary harness was
`/private/tmp/run-gateway-campaign-once.sh`; it rejects any high-water timeout delta and checks
committed fences never exceed offered fences.

## Phase 1 results

| Stage | Fixed workload | Sustained booked/s | Ack-loss result | Decision |
|---|---|---|---|---|
| Clean baseline, 64 KiB | 3 gw, b200, c48 | 141,480 / 149,878 / 157,317; median **149,878** | 21.98–22.69% | Baseline |
| Lever 1, high-water completion | 3 gw, b1000, c48 | 149,634 / 164,805 / 160,019; median **160,019** | 24.17–28.61% | Retain; batch cliff removed |
| Lever 2, 1 MiB terms | 3 gw, b1000, c48 | 163,765 / 228,022 / 233,266 / 227,759; all-run median **227,890** | first three 1.30/1.34/2.52%; fourth 9.88% | Retain |
| Lever 3 candidate | 4 gw, b1000, c64 | 250,573 / 187,473 / 212,229 / 249,897; median **231,063** | 10.11–11.30% | Reject fourth gateway |
| Fixed-load control | 3 gw, b1000, c64 | 236,292 / 196,862 / 275,057; median **236,292** | 2.52–7.11% | Fourth gateway gives no gain |

Lever 1 changed the usable batch curve: the post-fix 20-second checks were about 84.1k at b100,
118.5k at b200, 132.7k at b500 and 147.9k at b1000, all with zero failures. The old b500+
full-budget collapse was gone. The final implementation uses a committed cancel/fence as the
high-water marker so the batch can finish after `appliedSeq` passes its last offered order without
requiring every individual lossy ack.

The four-gateway comparison initially appeared to gain about 10%, but it also changed total offered
load from c48 to c64. The requested 3gw/c64 control removed the attribution error: at fixed c64 the
fourth gateway's median was about 2.2% lower. Source and live state therefore retain three gateways.

### Member-side evidence and the cold plateau

Live C3 samples at the 4gw/c64 point showed the leader at 2.62–2.67 of its 3 cores, followers at
1.65–2.05, and the leader roughly 248–253k applied entries behind the furthest member. The 3gw/c64
control was even clearer: leader 2.804/3 cores, followers 1.832/1.813, roughly 255k applied spread.
The scaling knee was member/consensus-side, not proof that the gateway owner thread stopped scaling.

Low rows recurred immediately after a rollout/reset and disappeared on a later run without a config
or restart change: 163.8k in the first 1 MiB bank, 187.5k in the four-gateway bank, and 196.9k in the
three-gateway control. Record them; do not silently discard them. The evidence supports a cold
JVM/session/path plateau, but the campaign did not instrument a single causal counter deeply enough
to call that root cause proven. Both all-run and warm-run summaries should remain available.

### Reconsidered 4 MiB terms

At four gateways, the retained 1 MiB channel again lost about 10% of fill acks. A 4 MiB warm run cut
that to **0.806%** and booked 223,910.92/s, so transport headroom was real. However, three subsequent
full attempts each increased `traderx_gateway_batch_high_water_total{outcome="timeout"}` (deltas
1, 4 and 3). Those rows were rejected before banking. Lower ack loss did not make the contract safe;
source and live state were restored to 1 MiB.

## Phase 2 — C4D rollover

C4D was present and allocatable in `us-east1-b`. `pd-balanced` is unsupported for this machine
family; the successful pool uses Hyperdisk Balanced. Project CPU quota prevented creating all
three C4D nodes alongside all three C3 nodes, so the members were scaled to zero, their `emptyDir`
state was erased, `blp-c3-pool` was deleted, and `blp-c4d-tuned-pool` was grown to three nodes.
The failed/empty trial pool was deleted as well.

The first rebuilt pods were Burstable because the restore init container had limits without equal
requests. Commit `3b28a61` makes its CPU/memory requests equal its limits; after a fresh reset all
three members were Guaranteed and received cpuset `1-3`.

Compared with the C3 3gw/c64 control median of 236,292/s, the primary C4D median is 256,403/s
(**+8.5%**). A valid live sample from C4D run 1 showed leader/followers at
1.281/0.758/0.733 cores with under ~3k applied spread. The later sample-only run used non-SQL account
61231 and is excluded from all throughput claims; its member CPU/apply samples must not be used for
the public comparison either. If a second C4D member sample is wanted, repeat with one of the seven
real accounts.

## Tail latency and failover acceptance

`MatchLatencyBenchmarkTest` ran three times in fresh JVMs inside a temporary Guaranteed 3-CPU C4D
pod with static cpuset `1-3`. Median-of-three percentile rows (p50 / p99 / p99.9 / p99.99):

| Operation | p50 | p99 | p99.9 | p99.99 |
|---|---:|---:|---:|---:|
| resting insert | 230 ns | 321 ns | 431 ns | 5,471 ns |
| limit cross | 551 ns | 831 ns | 1,071 ns | 6,443 ns |
| market order | 590 ns | 911 ns | 1,192 ns | 6,771 ns |

The prior pinned C3 cross p99.99 was about 12 us, so C4D improved that tail about 1.9x. Quote
percentiles, not maxima.

One clean **idle, node-clock role-transition** proof killed member 0's JVM at
`1784679489717`; member 2 logged `ROLE-CHANGE role=LEADER atMs=1784679490062`: **345 ms**. The
restarted member rejoined and all three returned to exact applied/trade parity. This is not a
client-gap measurement and must not share a slide with saturated 8–12 s recovery.

## Commits made locally

- `cdb62dc` — high-water batch completion plus corrected batch harness crossing tail.
- `77ab0b1` — 1 MiB gateway Aeron terms.
- `2228e31` — temporary four-gateway hard spread for measurement.
- `2ca9018` — temporary 4 MiB terms for measurement.
- `5273a88` — retain 1 MiB after timeout rejection.
- `d39df71` — retain three gateways at the member ceiling.
- `a90e5be` — C4D pool selector/config and manifest comments.
- `3b28a61` — Guaranteed QoS through the restore init container.

The temporary experiment commits remain in history intentionally; final source state is three
gateways and 1 MiB terms. No commit was pushed.

## Final live GKE state

- `blp-c4d-tuned-pool`: 3 x `c4d-standard-8`, Hyperdisk Balanced, CPU Manager static,
  `threadsPerCore=1`, compact placement, status RUNNING.
- Members: 3/3 Ready, one per C4D node, Guaranteed, cpuset `1-3`, zero restarts, image digest
  `sha256:20dfd2bd04c517705e7f3b5b1ecf75b2b313091b79147ec3853d1dee92f75e09`.
- Gateways: 3/3 Ready, three distinct non-member nodes, zero restarts, digest
  `sha256:ba2ac5009fa092bcf5e792930c1b2c176058d7612b53085448376f8dd2dac754`.
- Fresh epoch seeded with the seven real accounts: all members at applied 287, trades 0, one leader
  and two followers.
- Reference-data deployment remained generation 14 on image
  `reference-data:state009-yu09-20260713`; no instrument-model change was attributed to this work.
- The sample flood caused the already-backlogged trade processor to OOM-restart; after the final
  fresh epoch reset it recovered to Ready. No trade-processor source/config was changed.

Destructive actions performed: member scale-to-zero/reset for the quota rollover, deletion of the
old C3 pool, a second member reset for Guaranteed QoS/latency isolation, deletion of the temporary
latency pod, and delete/recreate of the completed account-seed Job. The first seed re-apply briefly
landed in `default` because the manifest has no namespace; that inert Job was deleted and the
unchanged manifest was re-applied with `-n traderx`.

## Next chat

1. Do not rerun Phase 1 or rebuild the pool: the campaign is banked and final source/live state is
   already the selected 3gw + 1 MiB + tuned C4D configuration.
2. If the talk needs two C4D CPU/apply samples, run one additional sustained b1000/c64 row using an
   unused **real** account from `10031,11413,22214,42422,44044,52355,62654`; capture two live
   `kubectl top` plus `/health` snapshots. Do not use account 61231 or its 296.8k row.
3. If YU14/reference-data changes land, establish a new three-run baseline before any further
   gateway experiment. Current reference data is still generation 14, so this campaign is clean.
4. Optional diagnostic only: instrument JVM compilation/session state and member duty-cycle counters
   around a first-after-roll run to turn the repeatable cold plateau correlation into a proven cause.
5. Preserve reporting boundaries: primary C4D median 256.4k (not 300k), batch path distinct from
   synchronous REST, 345 ms node-clock role transition distinct from client gap, and idle distinct
   from saturated recovery.
