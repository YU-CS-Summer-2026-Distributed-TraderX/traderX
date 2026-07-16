# Issue: order-matcher logs "Risk replica bootstrap complete" at INFO every ~1s

**Status: open (observed 2026-07-14 during the YU04 demo on kind). Cosmetic/low priority — a
gap-filler task, not a state.**

## Observation

On kind (YU04 state, single-BLP), `order-matcher` emits one INFO line per second, indefinitely:

```
f.t.ordermatcher.risk.ReplicaBootstrap : Risk replica bootstrap complete:
  accounts=7 securities=30 (account watermark=0, security watermark=22)
```

This is the Gateway replica's poll loop (`ReplicaBootstrap`, ~1s interval). Functionally correct —
the replica state it reports is current, and the YU04 control-feed proofs pass — but:

- It drowns out real signal in `kubectl logs` / Loki (86,400 identical lines/day/pod).
- "bootstrap complete" logged 1/s suggests the poll path may be re-running more of the bootstrap
  work than a steady-state delta-consumption loop needs — worth a quick look at whether the loop
  is re-fetching/re-verifying the snapshot rather than just consuming live deltas (ADR-019's
  subscribe→snapshot→catchup→**live-consume** protocol implies the snapshot leg should run once
  per (re)bootstrap, not per tick).

## Suggested fix (cheap)

1. Log the "bootstrap complete" line at INFO **once per actual (re)bootstrap** (first success,
   after quarantine, after reconnect), and drop the steady-state repeat to DEBUG or a
   watermark-changed-only condition.
2. While there, confirm the 1s loop is delta-consumption only in steady state; if it re-runs
   snapshot fetch/verify, fix that too (bigger win than the log line).

Touches `order-matcher/.../risk/ReplicaBootstrap.java` (YU04 spec layer — apply on the YU04 home
branch and carry to all descendants per `HANDOFF-issue-spec-layer-propagation-gaps.md`).
Hot-path-adjacent but edge-only (`GatewayReplicaStore` is edge state); still run `bench-compare`
after per standing convention.
