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
- **FR-OPL-05 (exclusive cores, latency L1):** on a rig, the engine pool's static CPU manager gives a Guaranteed member
  with an integer CPU request exclusive cores (whether production's `node-engine.yaml` carries it is FR-PRG-05, a
  separate decision). The kubelet keeps ceil(kube-reserved) = 1 CPU (cpu 0) in
  the shared pool and allocates whole physical cores first. On a c4d-standard-16 a 14-CPU member gets 7 full cores; a
  15-CPU member also takes cpu 0's SMT sibling. GKE's node config exposes no `full-pcpus-only` and no IRQ affinity, so
  NIC interrupts are recorded (FR-OPL-06), not steered. `l0-baseline.sh static CPU` SHALL take the members to 0,
  apply `node-engine.yaml` with `cpuManagerPolicy: static` to the engine pool at 0 nodes, bring the pool and the
  members back at CPU, and refuse unless the pool reports the policy and each member's cpuset is exactly CPU wide.
  `l0-baseline.sh shared CPU` SHALL do the same with `cpuManagerPolicy: none` and refuse unless each member's cpuset
  is wider than CPU.
- **FR-OPL-09 (latency session 3 harness):** the cores switch SHALL take `NODE_SYSCTLS=k=v,...`, add each under the
  engine node config's `linuxConfig.sysctl` (replacing the same key), and refuse unless the pool reports every one.
  `l0-baseline.sh pin CPU` SHALL put each hot agent (sender, receiver, consensus module, clustered service) on its own
  whole physical core of the member's exclusive cpuset and every other JVM thread on the remaining cores, refusing on
  a cpuset wider than CPU or with fewer than five whole cores. The session-3 arms (`Nsh L1sh L1shbr L1shwm L1st L1pin
  Nsh2`) SHALL each state every node knob they touch (busy_read/busy_poll, wmem_max/wmem_default), so none inherits
  the previous arm's.
- **FR-OPL-10 (latency-tail attribution harness, 2026-11-12):** `l1-ab.sh` SHALL run `ARM_HOOK start LABEL` on the
  rolled members before the probe and `ARM_HOOK stop LABEL` before the next roll, refusing the arm if the start
  fails, and SHALL take `RATES` (default `0 300 500`) as the blocks an arm runs. `l0-baseline.sh` SHALL read thread
  CPU and leader state from the member's own JVM (the `java` process whose command line names `ClusterNodeMain`),
  not the first `java` in the container: a second JVM beside the member made every thread-CPU reading the probe's.
  `scripts/bench/latency/tail/` holds the hook and its readers:
  - `tail-hook.sh`: `HxProbe` on every member (every position counter each 1 ms; agent thread state, wait channel
    and run/wait time each 10 ms), then each member's GC, safepoint and snapshot log lines and every gateway's;
  - the probe SHALL write to `/dev/shm` and SHALL NOT read `memory.stat` or node `vmstat`: writing its series to the
    container's `/tmp` (the node's boot disk) gave 55-240 ms cluster stalls and +110 us of idle commit, where the
    same arm without it had none (runs 1, 3 against 2, 4);
  - the probe SHALL stop itself once its two files pass `hx.capBytes` (150 MiB): `/dev/shm` is a 1Gi volume shared
    with Aeron's buffers, and an uncapped probe filled it and failed the leader's driver (an election, run 4);
  - a script that acts on "the leader" SHALL refuse unless exactly one member's `/health` says LEADER: the failed
    ex-leader kept saying so, and two profiles were taken on it;
  - `prof.py` + `JfrDump.java`: an async-profiler JFR (itimer, 1 ms) of the leader, the clustered-service thread's
    samples split into normal, inside a service-lag episode (commit minus service position over a bar) and inside a
    snapshot's service-flat run, by family and by leaf frame;
  - `analyze.py`: per member, each stage's lag as bytes behind over the byte rate (leader: publication to sender,
    to its own recording, to commit, commit to the service; follower: received to recorded), split by whether the
    tick falls inside one of that member's own GC cycles, with flat runs of 5 ms or more; `ep.py`: episodes over a
    bar, with the agents' thread states at each.
- **FR-OPL-11 (apply-cost microbench):** `ServiceApplyCostBenchTest` (opt-in, `APPLY_COST_BENCH=1`) SHALL drive the
  clustered service's `onSessionMessage` on one thread with no cluster: keyed NEW orders that cross between two
  accounts, alternating direction so no limit is reached, with the idempotency table at its retention frontier. It
  SHALL print ns per order per window, by wall time and by the thread's own CPU time, and fail if the orders did
  not cross. `APPLY_COST_VARIANT` switches FR-OPL-12's knobs on for a run. A change is judged old against new in
  interleaved runs on one machine, by the best window's CPU time.
- **FR-OPL-12 (service-thread cost knobs, default unchanged):**
  - `CLUSTER_OUTPUT_RING_WAIT=none` SHALL give the service's output ring a wait strategy whose signal is a no-op.
    The ring is published and drained by the one service thread, so nothing waits on it; the default (the
    Disruptor's blocking strategy) takes a lock and signals a condition on every publish.
  - `CLUSTER_ENGINE_TIMING=off` SHALL construct the engine with no `HotPathMetrics`; the engine SHALL then take no
    clock read and record no histogram for them. Nothing in the cluster tier reads those metrics.
  - Neither knob SHALL change an output: `ServiceThreadCostKnobsTest` runs one input mix (crossing orders, repeated
    keys, cancels) through a default service and one with both knobs on, and compares every output's decision
    fields in order. Unset, both keep today's behaviour.
  - Measured 2026-11-12 (Mac, FR-OPL-11, CPU ns per order, best window, two interleaved runs each): unchanged
    647 / 675; ring 531 / 567; timing 572 / 575; both 450 / 465. At 300K/s on GKE the two were 9.0% and about 10%
    of the leader's service thread (`latency-tail-20261112/s2/prof3-B`).
  - Measured on GKE 2026-11-12 (slot A, `tail-17ab61cf-j21`, arms N, RT, N′ of FR-OPL-14): leader apply per order
    1.89-1.91 / 1.67-1.68 / 1.88-1.89 us; achieved at the 500K setting 441-456K / 481-494K / 446-471K; 300K commit
    to service lag p99 1,707 / 1,458 / 1,460 us (the controls differ by 15%, so no verdict on the 300K tail; its
    median fell 219 to 150 us against both). Each knob alone was not run.
- **FR-OPL-13 (idempotency table: measured, nothing changed):** a key-less run of FR-OPL-11 costs 290-294 ns per
  order against 475-542 ns keyed, so the risk state's idempotency table is about 40% of the bench's per-order cost
  (12% of the leader's service thread at 300K/s on GKE, where the blotter, bridges and egress also run). Two changes
  were tried on 2026-11-12 and withdrawn: holding each entry's {orderRef, decision} once, in the retention ring,
  instead of also in two table arrays (433-449 ns against 450-475 ns: inside the noise), and reading the next
  eviction victim's slot one order early (no further gain). The cost that remains is two dependent reads of random
  cache lines per new key, the new key's slot and the evicted key's, plus the backward-shift scan. The patch is in
  the session notes (`idempotency-dedup-and-touch.patch`); the table is unchanged.
- **FR-OPL-14 (service-thread knob arms):** `l1-ab.sh` SHALL take an arm's own member env from `ARM_ENV`
  (`KEY=VAL` or `KEY-`) and refuse the arm unless every member's container shows it. Arms on today's member and
  shared cores: `Nk`, `Nk2`, `Nk3` (both FR-OPL-12 knobs unset: the control at the start, between and at the end),
  `RT` (both on), `Rk` (the ring alone), `Tk` (the timing alone).
- **FR-OPL-06 (leader state per window):** each block window SHALL also record on the leader, at its start and end:
  - every Aeron counter in the driver's CnC (`AeronCounters`): each agent's max cycle time, cycles over the threshold
    (`l1-ab.sh` sets every agent's to 1 ms), and back pressure;
  - the container's `cpu.stat` (CFS throttling) and cpuset, and the SMT sibling map;
  - `/proc/interrupts` (gVNIC) and `/proc/softirqs` (NET_RX/TX) per CPU;
  - the CPU each thread last ran on.

  `l1-report.py` SHALL give one row per window: throttled periods and time, each agent's max cycle, cycles over the
  threshold, back pressure, NIC IRQs and NET_RX on the member's own cores against the rest, and any hot agents
  sharing a physical core.
- **FR-OPL-07 (L1/L2 A/B):** `l1-ab.sh` SHALL run five arms in one session, each a member roll. Each arm runs a probe
  with blocks at 0, ~300K and the 500K setting, and the gateways are on `lowpark` throughout:
  - N: today's member on shared cores (lowpark, SHARED archive, 15 CPU);
  - S: N on MEMBER_CPU exclusive cores (default 14);
  - L1: S with the sender, receiver, consensus module and service busy-spinning;
  - L2: S with a DEDICATED archive;
  - L12: L1 and L2 together.

  The order control (2026-11-11) SHALL run `Np Sp Npp`: N' on shared cores, S' on static, then N'' back on shared
  cores at the 500K setting, so static's saturation cost is measured shared, static, shared within one session.

  After each roll the arm SHALL wait for every gateway to be Ready, start the probe, and refuse unless the probe is
  running and has written a window within 180 s. The probe retries its `/resolve` for up to a minute (2026-11-11: S'
  ran its blocks with a probe that died on ECONNREFUSED behind restarting gateways, and measured no client latency).

  `arm` SHALL refuse unless every member's start line shows the arm's idle and archive threading (FR-GMR-12/13), and
  `gw-idle` unless every gateway reports the strategy. N SHALL refuse on exclusive cores. `seed` admits the load's and
  the probe's accounts and seeds the arm tickers, since a fresh epoch has neither (RI35, 2026-11-10).

- **FR-OPL-08 (FIX probe):** `fix-latency-probe.mjs` SHALL apply FR-OPL-02's method to the FIX 4.4 acceptor (port
  18130): constant rate on a wall-clock schedule, latency from the intended send time (from the write when the timer
  fired early) plus from the write, epoch-aligned `PROBE-WINDOW` lines and a `PROBE-TOTAL`. It SHALL report three stages,
  each p50/p90/p99/p99.9/max: **ack**, a NewOrderSingle to the first ExecutionReport for its ClOrdID (New, or Filled
  for an aggressor answered filled, FR-FFR-01); **fill**, the crossing sell's send to its own Filled; and **rest**,
  the same send to the resting buy's fill report, which reaches its session through the fill feed (FR-FFR-02/14). Run
  4's quiet baseline (2026-11-11) read 117 ms p50 for fill and rest together, against an ack of 835 µs, so the two
  paths are reported apart. An order that ends neither filled nor rejected SHALL be logged with its 39/150/58. It SHALL stay book- and
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
| per-agent idle and archive threading reach the contexts | 05–07 (FR-GMR-12/13) | `ClusterNodeConfigTest` 7/7, 2026-11-10; forked-JVM negative control | default unchanged; sender/service spin, the rest lowpark, archive DEDICATED |
| counter dump reads a live driver | 06 | local `AeronPingPong` driver, 2026-11-10 | max cycle, exceeded and back-pressure counters listed; Aeron's driver threshold is 100 ms, so `l1-ab.sh` sets 1 ms (`aeron.driver.*.cycle.threshold`) |
| leader-state report | 06 | `test_l1_report.py` (fixture) | deltas, own/other CPU split, SMT pair found |
| L1/L2 A/B on GKE | 05, 07 | GKE 2026-11-10/11 (slot A, `eab81f9f`), shared note `latency-l1-l2-20261110.md`, `latency-l1-20261110/` | L1 busy-spin: 300K p50 566–571 → 292–302 µs, idle commit 278 → 55 µs, capacity 478–499K → 452–472K. S (static, 14): idle p99 −27%, capacity −14% (run-order confound, N′ not run). L2 DEDICATED archive hurt (vs S: idle p99 +30%, capacity −4%; recorder stalls). 0 throttling |
| static vs shared cores, order control | 05, 07 | GKE 2026-11-11 slot A, shared note `latency-static-cpu-20261111.md`, `latency-l1-20261111/` | N′ shared 471–478K → S′ static 452–463K → N″ shared 463–491K → S″ static 405–417K at the 500K setting: static −4 to −15% in both arms, shared did not drift. Static's idle p99 −47%; at 300K within noise. FR-PRG-05's static precondition held out of production |
| each refusal fires on a real fault | 03, 05, 07 | same session | `seed` on unadmitted accounts, `gw-idle` and `arm` on a mis-declared start line (negative control), `load_ok` on a stopped load (L12 500K window 2) and with no load pods |
| session-3 harness: node sysctls and per-thread pinning | 09 | local: the sysctl derivation replaces a key and adds a new one (2026-11-11); GKE session 3, shared note `latency-s3-20261111.md` | `busy_read`/`busy_poll`=50 confirmed on the host; `pin` put each hot agent on its own core with its sibling idle, on all three members |
| session 3: busy-spin on shared cores, busy-read, wmem, pinning, C driver | 07, 09 (FR-GMR-14) | GKE 2026-11-11 slot A, `latency-s3-20261111/` | busy-spin on shared: 300K p50 601 → 373–400 µs, saturation −13%. busy-read: 300K p50 −14%, p99 −12%, capacity neutral. wmem: inconclusive (a +115 µs gateway↔leader step outlived it). Pinning: 300K p99 −16%, commit p99 −30%. C driver vs the same 12-CPU member: saturation −17%, 300K p99 +20%. The epoch reached the 2³¹ order-ref ceiling at ~2.1B orders |
| engine-node budget listed | 04 | GKE 2026-11-10, `alloc.txt` | 16 capacity, 15,890m allocatable, DaemonSets 433m, member Guaranteed 15 |
| FIX probe stages from a scripted acceptor | 08 | `test_fix_latency_probe.Stages`, local mock 2026-11-11 | 2 ms ack / 5 ms fill injected: ack p50 in [2, 4.5) ms, fill p50 in [5, 7.5) ms, two acks per fill; an aggressor answered Filled lands its first answer at 5 ms |
| FIX probe refusals | 08 | `test_fix_latency_probe.Refusals`, local mock 2026-11-11 | a session dropped after 100 orders: exit 1, lostSessions=1, skipped and unanswered > 0; an odd session count without `--accounts` refused |
| FIX probe: aggressor and resting reports apart | 08 | `test_fix_latency_probe.Stages.test_the_resting_report_is_its_own_stage`, local mock 2026-11-11 | 5 ms aggressor / 30 ms resting injected: fill p50 in [5, 7.5) ms, rest p50 in [30, 33) ms, one each per pair |
| FIX probe on GKE, quiet baseline | 08 | acceptance run 4 (3a42e515), 2026-11-11 11:10–11:20 am ET, `acceptance-450k-20261110/l0-fix-probe/baseline/` | 300,001 orders, 0 rejected: ack p50 835 / p99 1,712 / p99.9 2,188 µs; fill and resting report together p50 117 ms (split from the next run on); exit 1 on 4 orders that ended neither filled nor rejected (not logged by that build) |
| FIX probe on GKE, at rate | 08 | not yet run | acceptance run 4 stage 2 |
