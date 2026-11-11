# Order-path latency measurement (latency L0)

Status: measured on GKE 2026-11-10 (disposable rig `pa-proof-400k`, ADR-103 member shape). Owner: latency L0 lane.
The latency brief's measurement rules apply to every requirement here: one rig, one session; a constant-arrival-rate
probe, never the saturating driver; the nanosecond cluster clock; bound an effect before building for it.

## Requirements

- **FR-OPL-01 (wire floor):** `AeronPingPong` `MODE=ping`/`MODE=pong` SHALL measure the Aeron UDP round trip between
  two pods with no cluster, consensus or service in the path: a DEDICATED driver like the members', `IDLE=busyspin`
  (the floor) or `lowpark` (the members' `CLUSTER_IDLE_STRATEGY`), a fixed send schedule (`RATE`/s), and the RTT of the
  measured part taken from the intended send time. `l0-baseline.sh floor` SHALL map each pod to its node and refuse a
  pair on one node (the August loopback trap), and SHALL run the JVM without the pod's `JAVA_TOOL_OPTIONS` (a member's
  pre-touched 12g heap).
- **FR-OPL-02 (client probe):** `binary-latency-probe.mjs` SHALL offer orders on the binary path at a constant rate on a
  wall-clock schedule and report latency from the intended send time (from the write when the timer fired early),
  plus from the write, per wall-clock window aligned to the epoch. Orders SHALL go round-robin over enough sessions
  that one session is idle between its orders (the acceptor answers one order per session at a time). It SHALL be
  book- and position-neutral on its own ticker (pairs cross, the buyer alternates between two accounts, never a
  self-trade), and exit non-zero on any unanswered order, skipped send or lost session.
- **FR-OPL-03 (window protocol):** `l0-baseline.sh block` SHALL run binary-load at a fixed background rate with the
  rig's risk policy raised for the test accounts, and take 60 s windows that skip the minute holding binary-load's
  phase pause. A window SHALL be refused, not recorded, unless all eight load pods run and none has a rejected order at
  its start and its end. Per window it SHALL record every gateway's `/latency` (reset at the start), the leader's
  `/latency` (commit and apply, `CLUSTER_CLOCK=nanos`), the leader's per-thread CPU, and the probe's window; the
  leader's safepoints come from `-Xlog:safepoint` in the member's JVM options.
- **FR-OPL-04 (engine-node CPU budget):** `l0-baseline.sh alloc` SHALL list each engine node's capacity and allocatable
  CPU and every pod on it with its QoS class and CPU requests, so a static CPU manager's exclusive budget can be
  checked against the member's integer request before the pool is recreated.

- **FR-OPL-08 (FIX probe):** `fix-latency-probe.mjs` SHALL apply FR-OPL-02's method to the FIX 4.4 acceptor (port
  18130): constant rate on a wall-clock schedule, latency from the intended send time (from the write when the timer
  fired early) plus from the write, epoch-aligned `PROBE-WINDOW` lines and a `PROBE-TOTAL`. It SHALL report two stages,
  each p50/p90/p99/p99.9/max: **ack**, a NewOrderSingle to the first ExecutionReport for its ClOrdID (New, or Filled
  for an aggressor answered filled, FR-FFR-01); and **fill**, the crossing sell's send to the ExecutionReport that ends
  the later of the pair (the aggressor's Filled and the resting buy's fill report, FR-FFR-02). It SHALL stay book- and
  position-neutral as FR-OPL-02: a lane is one session sending both accounts (`--accounts A,B`, the CompID bound to
  both) or two sessions each trading its own account (`--creds`, an even count, distinct accounts per pair). It SHALL
  log on every session before the first order, and exit non-zero on a reject (39=8, 35=3, 35=j), an order that ends
  without filling, a lost session, a skipped send, or an order unanswered or unended at exit. `l0-baseline.sh
  fix-probe` SHALL run it as a Job beside `probe`, the credentials mounted from a Secret, never on the command line.

## Coverage

| Behaviour | FR | Evidence | Result |
|---|---|---|---|
| ping/pong RTT, both idle strategies | 01 | local loopback self-test 2026-11-10 | count = sent less warm-up, both modes report; loopback figures not used |
| wire floor between nodes | 01 | GKE 2026-11-10, `latency-l0-20261110/floor.txt` | pairs on distinct nodes; see `latency-l0-baseline-20261110.md` |
| probe from intended time | 02 | local mock gateway 2026-11-10 | the early-send case read below the write time and was fixed; after it intended ≥ from-write |
| probe at 500/s on the rig | 02 | GKE 2026-11-10, `probe*.log` | 0 rejected, 0 unanswered |
| a window over a stopped load is refused | 03 | GKE 2026-11-10, `void-no-policy/` | without the policy raise the load rejected 21% and stopped at the phase boundary; those windows measured an idle leader (0.54 cores) and are void; the precondition now refuses them |
| windows per rate, twice (A, B) | 03 | GKE 2026-11-10, `latency-l0-20261110/table.md` | p50 within ±1–3% between A and B, p99 ±2–5%, p99.9 ±15–20%; see `latency-l0-baseline-20261110.md` |
| a gateway-only A/B in the same session (C) | 03 | GKE 2026-11-10, `C*-gwspin/` | leader commit unchanged at idle (the control); the gateways' driver on spin: client p50 1,165 → 614 µs at idle, −8% capacity at saturation |
| engine-node budget listed | 04 | GKE 2026-11-10, `alloc.txt` | 16 capacity, 15,890m allocatable, DaemonSets 433m, member Guaranteed 15 |
| FIX probe stages from a scripted acceptor | 08 | `test_fix_latency_probe.Stages`, local mock 2026-11-11 | 2 ms ack / 5 ms fill injected: ack p50 in [2, 4.5) ms, fill p50 in [5, 7.5) ms, two acks per fill; an aggressor answered Filled lands its first answer at 5 ms |
| FIX probe refusals | 08 | `test_fix_latency_probe.Refusals`, local mock 2026-11-11 | a session dropped after 100 orders: exit 1, lostSessions=1, skipped and unanswered > 0; an odd session count without `--accounts` refused |
| FIX probe on GKE | 08 | not yet run | carried by acceptance run 4 |
