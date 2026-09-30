---
title: Published measurement context
---

# Published measurement context

These are the existing published values. They are historical measurements, not new runs of the current integration revision. The September order-type acceptance still leaves SC-OT35/NFR-OT06 deployment-profile latency unverified. No benchmark was run for this documentation refresh.

| Published value | Measurement scope | Provenance and limits |
|---|---|---|
| **259,200 orders/sec** | Per-order binary ingress, six gateways, leader sequence delta over a 20-second steady window | 2026-07-26 YU15 GKE campaign recorded 259,211/s, rounded for the homepage. Three c4d-standard-8 members, six private c2d-standard-4 gateway nodes, a dedicated c2d-standard-8 load generator and support nodes. This is not REST throughput or current YU18 performance. The source described roughly 47,000/s per gateway; that is a historical scaling observation, not a six-way arithmetic guarantee. |
| **185–227 µs** | Consensus commit, leader acceptance to commitment across three members; reported stable across a 6× load sweep and tested in-flight windows | Retained from the July YU15 publication record. The later July 26 image lacked the commit-time metric, so it did not remeasure this value. Exact original revision, sample distribution and JVM build are not recovered in the public record. |
| **0.45–0.57 µs** | Match/apply on the replicated apply path | Retained from the same historical publication record. Allocation checks apply only to their declared warmed JVM/GC/compilation profile; they do not prove zero allocation on every runtime. Exact original sample metadata remains incomplete. |
| **< 1.5 ms p50**, p99 near **2 ms**, sustained to **75,000 orders/sec** | Published client-observed per-order round trip with an in-flight window sized for load | The headline is preserved, but the reviewed source does not provide enough per-run provenance to reconstruct this exact campaign. Do not present it as a current REST guarantee. The separate July 26 single-gateway REST probes reported 2.0/2.3 ms p50 at 1k/5k orders/sec; these are different workloads. |
| **< 200 ms** | Published leader-failover observation using an independent gateway-session probe | Keep separate from election time and from API-driven pod deletion latency. The July 26 report recorded 133 ms election in one drill and 141 ms election with first apply at +206 ms after the keepalive fix. Those observations do not prove every client recovers below 200 ms. The universal interpretation of the headline is unsupported. |

The source records describe Java 21-era YU15 images and tuned GKE member placement; they do not supply a complete immutable runtime manifest for every headline. Image labels and abbreviated digests are not substitutes for a verified full image digest. These provenance gaps remain open instead of being filled with guessed hardware or dates.

## Other published campaigns

The earlier four-gateway binary campaign reported **190,300 orders/sec**, before the six-gateway run. Preserve that context when comparing historical reports.

The 2026-07-28 observability/capture A/B used three c4d-standard-8 members, three gateways and three load generators on c2d-standard-8 nodes, and the same `yu15-costbench` image for each arm. Saturated-throughput medians were **129,050/s base**, **127,009/s tracing** (−1.5%) and **128,772/s capture** (−0.2%). Overlapping runs did not isolate a steady-state cost. They do not mathematically establish equivalence or zero cost.

Capture backlog drain after a flood produced multi-second latency tails while throughput continued. The [observability guide](observability-and-replay.md) explains the bounded, asynchronous capture design. A drop-on-full mechanism bounds pressure; it does not guarantee that the surrounding machine has no contention.

## Reading a result

Record transport, workload, offered and committed rate, window, topology, source revision, image/JVM identity, warmup and sample distribution. Separate client round trip, consensus commit and engine apply. Kind/laptop correctness checks do not establish deployment performance, and a green allocation gate is not a latency benchmark.
