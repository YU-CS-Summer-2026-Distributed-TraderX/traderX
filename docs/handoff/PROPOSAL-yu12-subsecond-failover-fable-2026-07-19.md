# YU12 failover: sub-200ms system / sub-500ms client — fable's proposal

Targets: system-facing (leader dead -> new leader serving) < 200 ms consistent;
client-facing (last ack -> first post-failover ack at the client) < 500 ms consistent.
Current proven: 653-716 ms system (idle), 724/778 ms under flood; client 838-1657 ms bimodal.

## Where the time goes today (measured)

system = detection (<= 400 ms heartbeat timeout) + election (~250-300 ms incl. joinLogAsLeader)
client = system + stall-detect (500 ms threshold) + connect-cycle (burns a 1 s connect timeout
         when it tries the dead endpoint first — THE bimodality) + first ack RTT

## System-facing: path to ~200-300 ms (sub-200 is the stretch goal)

1. **Tighten the consensus knobs one more notch, with soak gates**: interval 100->25 ms,
   heartbeatTimeout 400->150 ms, electionTimeout 200->100 ms. Detection floor is ~4-6 intervals
   for jitter margin; GKE pod-to-pod jitter is sub-ms and the service hot path is
   allocation-free, so the classic false-election drivers (GC pauses, packet loss) are small
   here. The "aggressive timeouts destabilize" fear was disproven once already (500 ms
   "instability" was a measurement artifact). Gate each step on: 5-min idle soak with zero
   spurious role changes, then a flood soak — snapshots run during floods, and although
   heartbeats come from the consensus thread (not the stalled service thread), the 8 s snapshot
   barrier is the most likely trigger of false positives at very tight timeouts. Verify
   explicitly before/after a snapshot boundary.
2. **Shave election, not just detection**: electionTimeout 100 ms; the followers already hold
   the full log so leaderInit/joinLogAsLeader is the fixed cost (~50-100 ms observed). Measure
   the split (detection vs election) by diffing the killed-at timestamp against the survivor's
   first CANVASS/candidate transition (add one more `ELECTION-PHASE atMs=` log line) so tuning
   attacks the right term.
3. **Planned failovers should not pay detection at all**: for maintenance (rollouts), use a
   graceful step-down (ClusterTool/ConsensusModule control toggle) — leadership transfers
   without waiting for a timeout. Crash failover keeps the detection cost; ops failover
   becomes ~election-only. Cheap to add to the rollout runbook.
4. Floor honesty: detection(150) + election(~100-150) lands ~250-300 ms consistent. True
   sub-200 requires either interval 10-25 ms + timeout ~100 ms (needs a long stability soak to
   trust) or external death signals (k8s watch -> poke followers), which Aeron has no API for
   and which would put k8s back in the failover path — recommend NOT pursuing that.

## Client-facing: sub-500 ms is very achievable — stop reconnecting entirely

The client should never tear down its session on failover. Aeron Cluster sessions are
replicated state: the SAME session survives a leader change, and the new leader announces
itself on egress (`EgressListener.onNewLeaderEvent`) with the new ingress endpoints.

1. **Native leader tracking (the main fix)**: connect once with the FULL
   `ingressEndpoints` list; on `onNewLeaderEvent` the client re-points its ingress publication
   to the new leader and continues the same session — no close, no re-connect, no
   session-open round trip. Client-facing becomes system-facing + ~1 RTT + resubmit of
   in-flight (un-acked offers to the dead leader are lost; idempotency keys make blind
   resubmit safe). Expected: client lag over system-facing drops from 200-900 ms to ~10-50 ms.
   - Landmine from history: the endpoint-cycling workaround exists because multi-endpoint
     connect WEDGED on kind (follower-redirect leg never completed; egress redirect lost).
     That was diagnosed on Docker-Desktop networking BEFORE the /dev/shm term-length root
     cause was found — there is a real chance the redirect wedge was the same egress-buffer
     bug in disguise. First step: RE-TEST multi-endpoint connect + redirect on GKE with the
     term-length fix in place. If it works, the whole cycling machinery (and its 1 s
     bimodality) is deletable.
2. **If the redirect wedge is real on GKE too, fix the cycling path's tail instead**:
   (a) stall threshold 500 -> ~200 ms (grace-after-connect already prevents churn);
   (b) connect timeout 1 s -> ~250 ms; (c) cycle starting from the last-known FOLLOWERS
   (never re-try the endpoint we just saw die); (d) race two connects in parallel, first
   session wins. Worst case then ~200 (stall) + ~250 (one failed race leg) + connect ~ 500 ms.
3. **Gateway = same client**: apply the same change to ClusterGatewayMain's owner loop
   (it only reconnects on isClosed today, which also leaves it exposed to silent session
   death — hit live during the bench). REST/FIX customers then see gateway failover
   transparency ~= system-facing.
4. **Measurement upgrade to match the targets**: proof client submit cadence 50 ms and ack
   timestamps quantize the gap; move to 10-20 ms cadence + nanoTime deltas for the sub-500
   claim, and add `ELECTION-PHASE` logging for the system split.

## Recommended sequence

1. Multi-endpoint + onNewLeaderEvent spike on GKE (falsifies the redirect wedge) — biggest
   client win, possibly deletes code.
2. Timeout ladder 25/150/100 with idle+flood soak gates; measure detection/election split.
3. Client + gateway native tracking; re-run the kill matrix (idle + flood).
4. Only if numbers demand: 10 ms interval experiments; parallel-connect race as fallback.

Risks: false elections under snapshot barriers at tight timeouts (soak explicitly across
snapshot boundaries); redirect wedge reproducing on GKE (fallback path 2); session-survival
assumption across leader change must be proven live (kill leader, verify SAME sessionId acks
after failover — first thing the spike checks).
