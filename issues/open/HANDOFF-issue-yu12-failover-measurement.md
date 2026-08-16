# YU12 — Clean Failover Measurement + Spontaneous-Election Investigation

**Purpose:** get a defensible client-facing failover number (leader kill → recovery), and explain a
spontaneous leader election observed under bench load. Neither was cleanly closed this session.
**Status:** OPEN. Created 2026-07-20. Untracked working note.
**Parent:** `RECAP-2026-07-20-yu12-bridge-bench-session.md`, skill `aeron-cluster-live-ops`.

---

## What happened this session

- A continuous probe (one order / ~200ms, timestamped) ran, but the **planned leader-kill did not
  execute** (the pod was never deleted — the classifier blocks the mutating command from the agent,
  and the manual delete wasn't run). So there is **no clean pod-kill failover number** from today.
- The probe DID catch a **spontaneous** leadership change (cluster-1 → cluster-0) under bench load:
  `total=654, ok=650, fail=4` → **99.4% success**, failures scattered across a ~15s window (NOT 15s
  of continuous downtime — only 4 in-flight requests failed).

## The measurement traps (from the aeron-cluster-live-ops skill — heed these)

- **`outageMs` = first-fail-to-last-fail span is misleading** — it is not continuous downtime. Report
  **count of failed requests** and the gap between last-success-before and first-success-after, using
  **node-clock** timestamps, not wall-clock across pods.
- The `kubectl rollout`/delete window is NOT the recovery signal — measure from the client probe, not
  from when k8s reports the pod gone.
- Kill the **actual leader** (confirm role from `ROLE-CHANGE role=LEADER` in the member log first),
  not an arbitrary member — killing a follower measures nothing.

## Action plan

1. Fresh clean cluster, seed, start the timestamped probe, let it baseline ~10s.
2. Confirm the leader, then `kubectl delete pod order-matcher-cluster-<LEADER>` (user runs it — the
   classifier blocks the agent). Optionally also test an ungraceful kill (`--grace-period=0`).
3. From the probe, report: failed-request count, client-facing gap (ms), and time to first
   post-failover success. Repeat 3× for a median.
4. Verify **no ID reuse / no lost trades** across the failover (cross-check DB trade count + cluster
   trade counter before/after) — correctness matters more than the timing.

## Spontaneous election — investigate

A leader election firing with no pod loss, under load, is worth understanding. Candidates:
- **GC pause on the leader** exceeding the election timeout (heap pressure under flood).
- **Health-server / duty-cycle starvation** delaying consensus heartbeats past the timeout.
- **Election-timeout tuned too tight** for the c3 nodes under this load profile.
Correlate the election timestamp with leader GC logs and CPU. If GC-driven, this ties back to the
throughput issue (holding a burst destabilizes the leader). Consensus timeout tuning lives in the
`aeron-cluster-live-ops` skill — do NOT tune blind; measure the pause first.
