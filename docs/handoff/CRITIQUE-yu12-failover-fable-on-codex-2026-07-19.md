# fable's critique of codeX's sub-second failover proposal

Verdict up front: **codeX's proposal is the stronger of the two.** It found three material
holes in mine, and its central reframe — "the SLO clock stops at FIRST_APPLY, not at role
change" — is correct and changes the plan. Its weaknesses are scope inflation (gates sized
for a production sign-off, not this waypoint) and two useful mechanisms it missed. I verified
its three most load-bearing factual claims against the code/jars before writing this; all
three hold.

## Verified claims (I checked, not trusted)

1. **The quadratic terminal scan is REAL** — `MatchingEngineClusteredService.writeSnapshot`
   does `for (ref : terminalFifo) { for (order : allOrders) { if match write; break; } }` —
   O(terminals x allOrders). That is my code (WS2, the F1 eviction-FIFO fix) and it is very
   plausibly most of the 8 s barrier. Best single catch in either proposal: the "hard
   blocker" may collapse to a cold-path loop fix + one re-run of the completeness matrix.
2. **`electionStatusIntervalNs` exists in Aeron 1.51** (confirmed in the jar) — the 100 ms
   canvass quantum is a real hidden term my sweep never touched.
3. **The gateway has NO ack-stall trigger** (only `isClosed()` — confirmed line 151). My brief
   overstated symmetry with the proof client; codeX was right to flag the measurement-
   attribution risk, and this is also the silent-session-death exposure we hit live.

## Where codeX beat my proposal

- **Snapshot-overlap invalidates the current SLO instrument.** A kill landing inside the
  barrier elects a leader whose SERVICE cannot apply for seconds; role-change timing reports
  a false pass. My proposal treated snapshots only as a false-election risk. With 60 s
  intervals and ~8 s barriers, ~13% of random kills land there — "consistent" is unclaimable
  without fixing this. FIRST_APPLY - KILL as the acceptance clock is the right definition.
- **`newLeaderTimeoutNs` coupling.** Client default = 2x the SERVER heartbeat timeout — at
  400 ms that is 800 ms vs our 778 ms loaded election (22 ms margin!), and tightening the
  server to 80 ms would give clients a 160 ms give-up window — LESS than the election. Nasty
  cross-tier landmine neither my proposal nor our incidents surfaced. Must be set explicitly.
- **CPU isolation as a prerequisite, with pass/fail duty-cycle gates.** I hand-waved "GKE
  jitter is small"; codeX is right that a 1-CPU-capped JVM running driver+archive+consensus+
  service, 3-on-2 packed, cannot honestly host an 80 ms detector. This correctly couples the
  C2 quota bump into the failover work rather than leaving it a separate line item.
- **Idempotency math kills my "blind resubmit is safe" hand-wave.** Keys are all zero today,
  capacity is 1024 entries (~28 ms at 36k/s), and best-effort egress breaks FIFO correlation.
  Their §7 (stable keys, echoed in egress, rate-x-horizon sizing, snapshot-cost interaction)
  is the serious version, and it merges with our already-open "per-order batch correlation"
  item — one design closes both.

## Where codeX's proposal is weak

- **Gate scope is production-sized for a waypoint state.** 100 idle + 100 loaded kills per
  final candidate, 30/60-min soaks per profile, one-way-blackhole fault matrix: days of wall
  clock. The claim we need at this stage is "consistent at the tens-of-kills scale incl.
  barrier-overlap kills". Trim to ~25 kills/config (5+ inside snapshot windows), 10-min
  soaks, and keep their full matrix as the graduation bar if YU12 leaves waypoint status.
- **The 20/80/40/10 headline is speculative where it matters.** Their own budget leaves
  "~90 ms for ballot + log join + first apply + tail" with zero measurements of that
  residual. They do gate it — but the honest headline is "sweep until the first margin
  failure", not a named candidate. (My proposal has the same flaw with 25/150/100.)
- **Missing: graceful step-down for planned failovers.** Rollouts/maintenance should pay
  ~election-only, no detection. Cheap, ops-valuable, absent from their doc.
- **Missing: the deletion win.** If multi-endpoint + NewLeaderEvent works on GKE, the whole
  endpoint-cycling machinery (and its bimodality) is removable code, not just a fallback to
  keep. Their §6 keeps bootstrap-cycling alive without asking whether it should die.
- **Missing: state-size lever on the barrier.** Snapshot cost scales with retained state;
  the 8 s was measured against bench-inflated books/terminal retention. Production caps
  shrink the barrier for free. Worth stating even if the quadratic fix dominates.
- **§3.2 alternatives (double-buffered image, incremental snapshots) are correctly flagged
  high-risk** — agreed, and they should stay parked unless the O(n) fix + state caps fail
  the 50 ms gate. Their instinct matches mine here.

## Convergent (both proposals independently) — treat as settled

- Native `NewLeaderEvent` same-session following is THE client fix; never close on stall.
- Re-test the kind "redirect wedge" on GKE now that term-length//dev/shm is fixed — it
  plausibly was that bug.
- Measure the detection/election split before tuning; phase-resolved instrumentation.
- Tighten timeouts only behind explicit soak gates incl. snapshot boundaries.
- Honest fallback ~250-350 ms system if margins fail; k8s stays out of the failover path.

## Disagreements to resolve in the merge

1. **Order of snapshot fix vs client spike.** codeX puts harness+snapshot before the client
   experiment; I'd run the no-code client spike FIRST (hours, falsifies the wedge, biggest
   client-facing win, zero risk) while the snapshot fix is being written. These parallelize.
2. **Gate sizing** — see above.
3. **Retry scope**: full keyed-idempotency rework is a workstream of its own; for THIS
   campaign, gate on "no duplicate execution with resubmit disabled" and land keyed retry as
   its own follow-on (merged with batch correlation) rather than blocking the latency work.

## Proposed merged sequence (for the joint plan)

1. Harness upgrade (codeX §2: FIRST_APPLY/FIRST_ACK clocks, election-state phases,
   always-pending canary) + reproduce the 400/200 control.
2. In parallel: no-code client spike (multi-endpoint + NewLeaderEvent + explicit
   newLeaderTimeoutNs; falsify wedge + session survival) AND the O(n) snapshot fix
   (ref->tuple index; completeness matrix re-run; barrier gate <=50 ms).
3. Placement/CPU isolation: quota bump -> 3 nodes, required anti-affinity, Guaranteed QoS,
   duty-cycle margins measured.
4. Timeout ladder (codeX's profiles incl. status-interval knob), one fresh epoch per
   profile, stop at first margin failure. Add graceful step-down for planned ops.
5. Unified owner loop for gateway+proof client (bounded pending queue, no stall-close,
   rate-limited reconnect fallback); delete endpoint cycling if the wedge is dead.
6. Trimmed acceptance: ~25 idle + ~25 loaded kills incl. >=5 inside snapshot barriers;
   report max/p99; zero reuse; identical member state after each cycle.
