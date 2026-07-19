# YU12 sub-second failover — joint plan (post cross-critique)

Inputs: fable + codeX proposals and both cross-critiques (all in docs/handoff/, 2026-07-19).
This document supersedes both proposals. It records what each critique conceded, then the
converged implementation plan.

## fable's response to codeX's critique of the fable proposal

Accepted in full (verified where checkable):
- **P0 snapshot overlap**: FIRST_APPLY - KILL is the acceptance clock; kills must land inside
  snapshot windows; role-change timing demotes to a diagnostic. (Same finding as my own
  critique of codeX — settled by three-way convergence.)
- **P0 blind resubmit unsafe**: conceded; keys are 0, capacity 1024 (~28 ms at 36k/s), egress
  best-effort. Keyed retry is its own follow-on workstream (merged with batch correlation);
  this campaign gates on "no duplicate execution, resubmission disabled".
- **P1 no graceful step-down in Aeron 1.51**: VERIFIED — ToggleState is
  {SUSPEND,RESUME,SNAPSHOT,SHUTDOWN,ABORT,STANDBY_SNAPSHOT}; no leadership transfer. My
  planned-failover mechanism as written does not exist. Planned-maintenance failover is
  DROPPED from this campaign; if wanted later it must be designed (e.g. leader SUSPEND +
  crash-path election, or appointed-leader restart choreography) — not runbooked by assertion.
- **P1 CPU scheduling margin, P1 newLeaderTimeoutNs coupling, P1 timeout profile
  insufficiency**: accepted as prerequisites/adjustments (also in my critique of codeX).
- **P1 parallel-race fallback**: WITHDRAWN — given the live concurrent-session-limit incident,
  racing connects risks recreating the leak class. codeX's fallback order adopted
  (full-list native following -> single-endpoint bootstrap -> preserve session -> one
  rate-limited reconnect only after genuine close).
- **Corrections**: client-outage equation is max(system, stall+reconnect)+phase+RTT, not a sum
  (my version double-counted); listener method is `EgressListener.onNewLeader` (verified);
  `joinLogAsLeader` 50-100 ms was asserted without phase measurements — retracted pending the
  harness; "silent gateway session death during the bench" was a mid-debug hypothesis I later
  root-caused to the output-ring wedge and should not have restated as fact — retracted.

## codeX findings adopted from fable's critique of the codeX proposal

- Gate sizes trimmed for waypoint scope: ~25 idle + ~25 loaded kills per final candidate with
  >=5 inside snapshot windows, 10-min soaks per profile; codeX's full 100+100/60-min matrix
  becomes the graduation bar if YU12 leaves waypoint status.
- The 20/80/40/10 candidate is a sweep END-POINT, not a target: ladder stops at first margin
  failure, and every profile change starts a fresh settled epoch.
- Deletion is a goal: if the redirect wedge is dead on GKE, endpoint-cycling machinery is
  REMOVED (single-endpoint bootstrap remains only if multi-endpoint initial connect fails).
- State-size lever noted alongside the O(n) snapshot fix (bench-inflated retention is part of
  the 8 s).

## Converged implementation plan

Phase 0 — harness (prereq for every claim):
  a. Election-phase timestamps from Aeron's election-state counter per member; keep
     ROLE-CHANGE logging; add FIRST_APPLY (first post-kill committed apply on the new leader)
     and client FIRST_ACK / onNewLeader stamps.
  b. Always-pending canary at 5-10 ms cadence; node-clock kill instrument unchanged.
  c. Reproduce the 400/200 control numbers with the new clocks (baseline re-anchor).

Phase 1 (parallel) — the two independent wins:
  1a. No-code client spike on GKE: full ingressEndpoints + onNewLeader + explicit
      newLeaderTimeoutNs(2 s) + messageTimeoutNs measured; falsify the kind redirect wedge;
      verify SAME clusterSessionId across a leader kill. Success deletes endpoint cycling.
  1b. Snapshot barrier: replace the O(terminals x orders) scan with a ref->tuple index (plus
      direct streaming where cheap); re-run the snapshot completeness matrix + zero-tail/
      tail/promotion recoveries; gate: callback max <= 50 ms at bench-inflated state.

Phase 2 — placement/isolation (needs the C2 quota bump 8->12, user runs the quota request):
  one member per node, required anti-affinity, Guaranteed QoS (equal req=limit), gateway and
  clients off member CPUs; measure consensus/driver duty-cycle + cgroup throttle tails idle,
  flood, snapshot, catch-up. Margin gate: no gap within 4x of the candidate detector.

Phase 3 — timeout ladder (env-only; expose CLUSTER_ELECTION_STATUS_INTERVAL_MS first):
  control 100/400/200/100 -> 50/200/100/25 -> 25/120/60/10 -> 20/80/40/10; fresh epoch per
  profile; 10-min idle + 10-min flood soak each incl. >=1 snapshot boundary; stop at first
  false election or margin failure. newLeaderTimeoutNs stays pinned at 2 s throughout.

Phase 4 — unified owner loop (gateway + proof client): shared implementation, no stall-close,
  bounded pending queue during offer backpressure, sub-ms owner polling, one rate-limited
  reconnect only after isClosed. Delete cycling if 1a passed.

Phase 5 — acceptance: ~25 idle + ~25 loaded kills (>=5 in snapshot windows, both placements),
  report max/p99 of FIRST_APPLY-KILL and client ack-gap; zero reuse; zero duplicate
  execution; identical member state after each cycle. Honest reporting: if margins cap us at
  250-350 ms system, that is the recorded result and sub-200 is explicitly declined.

Deferred (own workstreams): keyed idempotent retry + egress key echo (with batch correlation
and retention sizing); planned-maintenance failover design; async/incremental snapshots (only
if the O(n) fix + state caps miss the 50 ms gate).

## Expected outcomes if gates pass

- System-facing FIRST_APPLY: 130-190 ms (codeX budget) if the full ladder lands; 250-350 ms
  honest fallback at the conservative rung.
- Client-facing: system + ~10-50 ms via same-session native following — sub-500 ms with margin
  either way; the 1.6 s bimodal mode eliminated.
