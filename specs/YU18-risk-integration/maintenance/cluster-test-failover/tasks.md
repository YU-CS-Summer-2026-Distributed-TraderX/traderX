# Cluster test failover race: tasks and evidence

Status: done 2026-10-30. Local Mac (11 cores) only. A render of YU18 into a temp generated root.

- [x] Name the failing wait. Probe copy of the test, not committed: per-wait elapsed time, a 20 ms sampler
  of each member's ConsensusModule role, election state, module state, term, service role and applied
  sequence, plus the client's leader at each offer and its session/NewLeader events.
- [x] Verdict criteria written before the first run. Test timing: states keep moving and lifting load or
  the deadline rescues it. Real stall: a member stuck in one election state, not rescued.
- [x] Baseline, alone: 24 s. Both failovers about 11 s (the 10 s heartbeat timeout).
- [x] 14 busy loops, alone and in the full suite (first in its fork): unchanged. CPU load alone does not
  reproduce it.
- [x] Concurrent JVMs (4 copies of the test, run directly through the JUnit launcher): 5 of 8 failed in the
  first two rounds. Each failure was an **egress** wait (`awaitEgress` after failover 1 or 2), never the
  election, cleared after a **sub-second** failover (82–406 ms). Every run with only 11 s failovers passed
  (30+ JVMs). In each failure the order was accepted while the client still named the killed member as
  leader, with the publication connected. The NewLeader event came 50–130 ms later. The applied sequence
  never moved on any member for 120 s, and the client's session was closed by the timeout.
- [x] Forced fast failover (probe: close the leader's ConsensusModule, 300 ms for end-of-stream, then the
  driver; skip the single pre-offer egress poll). Unfixed: 3 of 5 valid runs failed with the same
  signature. The 2 that passed had their first offer refused, and the retry's poll delivered the event. A
  sixth run hit a port collision from the probe's own `java.io.tmpdir`. Fixed: 0 of 6 failed.
- [x] Fixed test through Gradle: alone, pass (31 s wall). Full order-matcher `test` (allocation gates
  skipped): 889 run, 0 failed, 9 skipped; this test 24 s.

Verdict: test harness, not a stall. Elections finished, and survivors followed, in every run. The lost order
is Aeron's documented ingress behaviour across a failover; production gateways handle it (ADR-090/091).

Not done:
- A fast failover in a GKE or kind run.
- The cause of a 20 s client blackout after a *slow* failover in 2 probe runs. There the client received
  no NewLeader, its offers were refused, and it reconnected when the session closed. The test
  survived through `offerIngress`'s reconnect.
