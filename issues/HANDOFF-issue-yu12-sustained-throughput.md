# YU12 — Sustained High Throughput (reproduce 135k reliably, not as a fragile burst)

**Purpose:** last night YU12 hit a **134,755 booked/s burst peak**; this session, on a clean cluster,
`batch=1000` gave only ~2k. This is the workstream to (a) explain the gap and (b) turn high
throughput from a fragile burst into a sustained, reproducible number. Folds in the bench-harness work.
**Status:** OPEN, high priority. Created 2026-07-20. Untracked working note.
**Parent:** `HANDOFF-ha-throughput-improvements.md`, `RECAP-2026-07-20-yu12-bridge-bench-session.md`.

---

## Measured reality (this session, clean cluster, 0 failures, 0 restarts)

| Path | submit/s | booked/s |
|---|---|---|
| via Service (pinned to 1 gateway by sessionAffinity) | 40,937 | 27,064 |
| 3 generators, one per gateway pod | 62,190 | — |
| 6 generators across 3 gateways | 63,761 | 44,916 |
| `batch=1000` burst, clean first load (tried to reproduce 134k) | 4,073 | **2,158** |

**Members ran at 5–10% node CPU throughout.** The cluster is NOT the bottleneck — the load path is.

## The three tangled problems

### 1. Reproducibility — why didn't 134k come back?
Leading suspect: **today's image rebuild changed the member binary.** The `/trades` fix rebuilt
`cluster-node:yu12`; with `imagePullPolicy: Always` + the clean restart, all three members pulled the
new build. Last night's 134k ran on a different binary. **UNVERIFIED — must confirm.** How: diff the
running image digest against last night's; if different, A/B the two builds under identical load. Do
this before any tuning — you may be chasing a binary change, not a config.

### 2. Sustainability — the book-growth decay (this is real and understood)
`FILL_FULL_THRESHOLD = 100` (`MatchingEngineClusteredService.java:50`): an order with `remaining ≥ 100`
fills **half** and leaves the rest resting (`MatchingEngine.java:620`). The bench default `QTY=500`
leaves a resting remainder on every order → the book grows unbounded → throughput decays run over run
(observed: 964 → 228 → 1318 booked/s as the book filled). 134k was always a **burst** that
destabilizes if held (batch=1000 saturates the in-JVM health server + grows the log → member restart
→ recovery death-spiral, per last night's own notes). Sustained ≠ burst.
- **Lever:** run with `QTY < FILL_FULL_THRESHOLD` (full fill at entry, flat book) for sustained tests,
  OR change the fill semantics so orders don't leave permanent remainders, OR feed periodic price
  ticks that clear the resting book (the cascade that produces high *booked* — see #3).

### 3. The booked/submit relationship (not a bug — a measurement subtlety)
`booked` counts **fill events**, and one price tick re-fills *every* resting order for that security
(`onPriceTick`, `MatchingEngine.java:530`). So a workload that builds a resting book then ticks the
price produces `booked >> submit` — this is how last night got 135k booked from ~29k ingress. The
slides' "booked > submit, looks flipped" was correct behavior. To reproduce high booked, you need the
**cascade workload** (build book → tick), not just raw submit.

## Bench-harness gaps (folded in from item E)

The current harness cannot saturate the cluster:
- **Single-threaded Node** load gen — one process ≈ one core; that's the ~40k ceiling, not the cluster.
- **`sessionAffinity` trap** (see the dedicated sessionAffinity issue) — pinned all load to 1 gateway.
- **No per-pod metric aggregation** — reading `/metrics` through the Service samples one random pod's
  counter; must sum across all gateway pods (each keeps its own).
- **Wrong QTY default** relative to `FILL_FULL_THRESHOLD` (problem #2).
- **Needs:** a distributed load generator (multiple pods / multiple client IPs), booked-cascade
  workload support, and correct per-pod aggregation. `scratchpad/yu12-bench.sh` from this session
  does the per-pod summing correctly — start there.

## Ordered next steps

1. Confirm/deny the image-binary hypothesis (#1) — cheapest, unblocks everything.
2. Fix the harness (distributed gen + cascade workload + per-pod metrics).
3. Split the sessionAffinity Service (separate issue) so REST scales out.
4. Re-measure sustained booked/s with the cascade workload on a clean book.
5. Only then tune consensus/snapshot/health-server for holding the burst without member restart.
