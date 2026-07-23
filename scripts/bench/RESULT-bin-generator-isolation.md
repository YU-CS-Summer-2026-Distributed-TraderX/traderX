# Phase 0 result — the load generator is not the ceiling

> Campaign: "find the real per-order ceiling." Phase 0 only: build a generator that can genuinely
> saturate the cluster, and **prove its own ceiling in isolation** before it is ever pointed at a
> cluster. A ceiling measured with a generator that caps first is worthless — that is the whole lesson
> of the retired 12k number (one Node event loop, quota-capped, absorbed with zero backpressure).

## Deliverable

`BinGen.java` — a compiled (JDK `javac`), thread-per-connection binary order-entry generator. Every
connection gets its own OS writer + reader thread, so it is CPU/kernel-bound, never event-loop-bound
(the thing that caps Node). Same wire and same unique-key / leader-aware design as codeX's
`bin-multi.mjs`, in a language that can actually over-offer.

`BinEcho.java` — a throwaway drain-only ACK target (no engine, no matching, no risk). Its only job is
to sink faster than any real cluster hop so the number below is the **generator's** offer ceiling.

`run-bin-isolation.sh` — compiles both, starts the echo on loopback, blasts the generator at it.

## The isolation proof

```
SESSIONS=32 SECS=20 BATCH=256 bash scripts/bench/run-bin-isolation.sh
```

**Blast, 32 connections, loopback echo, 20 s sustained:**

| metric | value |
|---|---:|
| offered (in window) | **2,188,471 / s** |
| acks read back (in window) | 2,109,188 / s |
| completed total after drain | 43,786,752 (== offered → **reader lossless**) |
| ack seq mismatches | **0** (FIFO correlation intact) |

**~180× the 12k number, sustained flat across the whole 20 s window** (per-2 s samples 2.85M → 2.16M/s;
the slow decline is the echo/loopback filling, not the generator — in-flight balloons to 1.5M frames
because the writers out-run even a 2.1M/s sink).

Complementary paced proof — 100k/s target, 32 conn, 20 s:

| metric | value |
|---|---:|
| offered | 99,757 / s (== target) |
| acks | 99,757 / s (== offered) |
| max in-flight / conn | 1,169 |
| write stalls (kernel backpressure) | ~0.07% of writes |

At the 100k/s the brief asks for, the generator offers **without its own backpressure**: in-flight
stays bounded, acks track offers exactly. (The ~2 ms latency floor is the paced writer's `Thread.sleep`
granularity on macOS at that trivial rate, not backpressure — irrelevant once real cluster latency
dominates in Phase 1.)

## Conclusion

The generator sustains **≥2.1M offered orders/s against an echo — ~180× the 12k "ceiling."** Any
cluster number Phase 1 later reports is therefore known to sit *far* below the generator's offer rate.
The generator is retired as a suspect. When the cluster knees, it will be the cluster, not the harness.

## What Phase 0 did NOT do (by design)

- No GKE, no engine change, no gateway change (beyond the throwaway echo).
- No seeding / HTTP path in the generator — pure wire, so it stays a clean single-variable tool. Seed
  accounts + resolve the numeric `securityId` out-of-band before pointing it at a real gateway.

## Phase 1 gate (separate GKE session)

Phase 1 (saturate the real cluster, read the per-hop funnel) is gated on the `CPUS_ALL_REGIONS` quota
decision — under 32 vCPU you cannot run enough gateways *and* generator capacity to force backpressure
at the binding hop. Point `BinGen` at the three gateway pod IPs (`GATEWAYS=ip1:18140,ip2:18140,...`,
connection `i` pinned to `i%G`), resolve the leader by `traderx_cluster_role`, and read the member
`nextOrderRef` delta as ground truth — exactly the funnel in `RECAP-codex-binary-ingress-ceiling.md`.
