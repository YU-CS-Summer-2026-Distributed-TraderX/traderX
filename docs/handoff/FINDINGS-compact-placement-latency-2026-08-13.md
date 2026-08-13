# Finding: compact placement is real, and it is not the latency lever

**Date:** 2026-08-13 · **Rig:** GKE `traderx-bench`, project `traderx-505400`, zone `us-east1-b`,
3 × `c2d-standard-8` members + 1 × `n2-standard-4`, YU16 `:yu16` build.
**Verdict:** measurable ~21% cut in per-hop wire RTT, worth roughly **14µs end-to-end** against a
**1079µs** p50. **Do not ship compact placement as a latency measure.** The remaining time is not
on the wire.

## Why this was run

LATENCY-01/03 decomposed the per-order budget and named transport — not the consensus model — as
the dominant share, with compact placement as the top untested lever. This is that test.

## The mechanism, measured first

Placement was measured *before* touching the order path, deliberately: if the physical network does
not move, nothing downstream can. ICMP, 500 packets per pair, all six directed pairs between the
three member nodes, `hostNetwork: true`.

| | min (mean of 6 pairs) | avg (mean of 6 pairs) |
|---|---:|---:|
| control — no placement policy | 58µs | 70µs |
| `--placement-type=COMPACT` | 46µs | 56µs |
| **change** | **−21%** | **−21%** |

Consistent across every pair and both directions. Compact placement does what it says.

**A trap worth naming:** the first reading showed 13µs to one peer and 77µs to another, which looks
like a spectacular placement effect. It was the probe pinging **its own node** — with
`hostNetwork: true` a pod's IP *is* the node IP, so one entry of the pair matrix is loopback. Always
map pod → node and skip self before believing a latency spread.

## What that is worth end to end

Measured on this rig, same session, in-cluster client (`rest-latency-probe.mjs`, constant arrival
rate, 500/s, 45s measured, 22,499 orders):

```
REST-LATENCY rate=500/s count=22499 p50=1079us p90=2325us p99=7247us p99.9=12797us max=23142us
```

Gateway-side decomposition (`/latency`, `LATENCY_DECOMP=1`) at the same moment:

| segment | p50 |
|---|---:|
| gateway owner-queue | 30µs |
| **cluster round trip** (gateway → leader → consensus → apply → egress ack → gateway) | **719µs** |

Now the arithmetic. A ping RTT of 56µs is ~28µs one-way. The order path crosses, at most:

| hop | compacted? | one-way |
|---|---|---:|
| client → gateway | n/a — **same node**, loopback | ~0 |
| gateway → leader | no (gateway is on `default-pool`, outside the policy) | ~35µs |
| leader ↔ follower (consensus quorum) | yes | ~28µs each way |
| leader → gateway | no | ~35µs |
| gateway → client | n/a — loopback | ~0 |

Total wire ≈ **126µs of a 1079µs p50, about 12%** — and compact placement only touches the two
consensus hops, so its share is **~14µs, about 1.3%**.

## Why the A/B was not run at the order level

A 1.3% expected effect cannot be resolved against a client-RTT p50 with roughly 2× run-to-run
variance. Running it would produce a number, and that number would be noise — quite possibly
*negative*, which someone would later quote as "compact placement made it slower". The mechanism
measurement bounds the effect below the noise floor, which is the stronger result and costs an order
of magnitude less.

This is a deliberate stop, not an unfinished experiment.

## What this actually tells us

**"Transport dominates" does not mean "the network is slow".** Physical wire time on this rig is
~12% of p50 at the absolute most. The ~700µs cluster segment is spent in the *software* path —
syscalls, buffer handoff, thread wakeups, serialization, idle-strategy park/poll — not in flight
between machines. Compact placement, faster NICs and closer racks all attack the 12%.

So the next levers are the ones that remove software work per message, not distance:

- idle strategy (already 2.4× from `lowpark`, and the biggest win found so far — same category);
- batching / fewer wakeups per order on the ingress and egress paths;
- kernel bypass, which is the only transport change large enough to matter, and is a much bigger
  piece of work than a placement policy.

**Caveat on comparing to earlier numbers.** LATENCY-01 recorded a client↔gateway segment of
321µs/37%; this run had the client on the *same node* as the gateway, so that hop was loopback here
and the two decompositions are not comparable. Different rig, different topology, possibly different
idle strategy. Everything above is measured on one rig in one session, which is the only way these
numbers mean anything — cross-run comparison is exactly the error that produced a phantom
"load-induced queueing" result in LATENCY-03.

## Reproducing

```bash
gcloud container node-pools create blp-compact --cluster traderx-bench --zone us-east1-b \
  --machine-type c2d-standard-8 --num-nodes 3 --node-taints workload=blp:NoSchedule \
  --placement-type COMPACT
```

The member StatefulSet pins `cloud.google.com/gke-nodepool: blp-c4d-tuned-pool` by nodeSelector, so
replacing the pool leaves all three members `Pending` on "didn't match Pod's node affinity/selector"
until that selector is patched. Worth knowing before assuming the compact pool is broken.
