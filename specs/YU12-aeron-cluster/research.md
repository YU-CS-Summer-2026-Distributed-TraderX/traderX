# Research: YU12-aeron-cluster

## Measured starting point

The parent state settled the transport question: the production Aeron MDC topology sustains
520,520 events/s against the File-backed NATS HA baseline of 10,561 (~49×), and the deployed
Aeron HA pair reaches 25,149 booked/s on GKE — single-BLP parity, against File-NATS HA's ~46% of
parity (`scripts/bench/results/yu11-transport-2026-07-17.md`). Replication speed is no longer the
constraint; the submit rate reached 48,761/s accepted in the same runs, placing the end-to-end
bottleneck at the REST ingress/output edges.

What the parent state did not settle is correctness cost. Its HA machinery — Lease election,
custom MDC publication management, fast-witness CAS fencing, cross-epoch snapshot-bundle
recovery — is hand-built, and its E2E campaign shows what that costs: eight compensating fixes on
the replication path, a cross-epoch recovery design that took five committed slices to prove, a
demonstrated ID-reuse defect (`ord-013-0008` reissued after promotion), and a fencing story that
must hold under adversarial timing rather than by construction
(`docs/handoff/PROOF-yu11-cross-epoch-recovery-2026-07-17.md`,
`docs/handoff/ISSUES-yu11-e2e-2026-07-17.md`).

## Why Aeron Cluster

Aeron Cluster packages exactly the machinery the parent state hand-built: Raft leader election,
one replicated committed log, deterministic single-threaded service hosting, snapshotting bound
to log position, and automatic member catch-up. The mapping onto this codebase is a deletion
list: the k8s Lease election, the NATS KV witness, the MDC publication machinery, the journal
reader/recovery orchestration, and the entire bundle-transfer path are replaced by consensus
primitives, while the crown-jewel logic — `MatchingEngine`, two-tier risk, the SBE codecs, the
CQRS/projector side — is reused as-is (`docs/handoff/HANDOFF-aeron-cluster-migration.md`).

Three properties decide it:

- **Split-brain is impossible by construction.** A partition minority cannot win a majority vote,
  so it cannot elect a leader or extend the committed log. The parent state's equivalent is a
  fencing proof over Lease/witness timing.
- **Election leaves the Kubernetes control plane.** Raft elects internally; Kubernetes only
  schedules pods. The parent state's measured promotions ran ~17 s on kind through
  Lease/heartbeat paths; Raft election is bounded by heartbeat/timeout tuning at the transport
  layer that already measured 520k events/s.
- **It shares the proven transport.** The cluster replicates over the same Aeron/Agrona stack
  already locked at 1.51.0 in the parent state, with the same zero-allocation discipline the
  inherited gates enforce.

## Alternatives considered

- **Generic JVM Raft libraries hosting the engine.** These provide a replicated log with
  install-snapshot hooks, but the service-hosting discipline — snapshot bound to an exact applied
  position, resume-after-boundary, client session routing to the leader, commit-path
  backpressure — remains hand-wired, which is precisely the defect class the parent state's
  recovery campaign spent its effort proving out. None are allocation-disciplined enough for the
  inherited exact-zero gates. Aeron Cluster is the implementation whose hosting container is the
  product.
- **An external ordered log as sequencer (broker-based).** The repository's own measurements
  close this: the File-backed JetStream replication path measured 10,561 events/s at replication
  factor one, and a replicated broker log adds the broker to every commit. The measured in-process
  consensus-transport budget is ~49× that. A broker in the commit path re-creates the bottleneck
  the parent state removed.
- **A sequencer service with deterministic replicas.** The sequencer itself requires election,
  fencing, and log handoff — the machinery under discussion. Aeron Cluster is this pattern with
  the consensus log as the sequenced stream and members as the replicas.
- **Deepening the two-node hand-built path.** The recovery bundle works and is proven, but each
  further requirement (fast-witness enablement, retention contracts, gateway repointing) adds
  hand-built machinery that consensus provides primitively. With a production matching engine as
  the destination, the two-node path is the waypoint, not the vehicle.

## What transfers from the parent state's recovery proof

The recovery campaign distilled four invariants that are requirements here, not history:

1. A snapshot is valid only when bound to exactly one applied replicated-log position —
   `onTakeSnapshot` state corresponds to the service's applied position by construction, which is
   the cluster-native form of the parent state's dedicated marker register.
2. Recovery resumes strictly after that boundary — no re-application, no gap.
3. Snapshot completeness covers every future-output generator and admission dependency:
   `nextOrderRef`, trade counters, idempotency state, risk reservations, symbol identity,
   control/policy versions. The parent state's ID-reuse defect (`12 orders warm, nextRef 8`) came
   from keeping a monotonic generator outside replicated state; inside the service the generator
   is ordinary snapshotted state and the defect class dissolves.
4. Acceptance is adversarial about completeness: issue orders after a snapshot, recover, promote,
   and assert the next ID strictly exceeds every ID ever issued — not every ID still retained,
   because terminal-order eviction can drop the highest historical reference.

## Determinism constraints of cluster hosting

Replicated state machines make hidden nondeterminism a divergence fault rather than a latent
bug, so cluster hosting imposes:

- **One input path.** The parent state's campaign already demonstrated the failure shape:
  followers locally injecting NATS price ticks produced double-applied prices and mixed-space
  journals. Under the cluster, every input — orders, cancels, ticks, control updates — enters as
  a sequenced ingress message or does not reach the state machine at all.
- **Cluster time only.** Time-driven logic uses cluster time and `onTimerEvent`; wall-clock reads
  inside transitions diverge replicas.
- **No cross-thread mutable reads.** The parent state's ID-reuse defect was exactly a
  cross-thread atomic read inside what should be deterministic state transition.
- **Admission state is explicit.** The parent state observed a recovered follower reporting
  Kubernetes-ready while its gateway control replicas trailed the recovery boundary
  (`watermark 954 vs 2897`). Readiness here distinguishes cluster-recovered deterministic state
  from asynchronously refreshed admission state and opens admission only when both are valid.

## Ingress model

Aeron Cluster clients speak the cluster protocol; counterparties speak FIX and REST. A gateway
tier terminates the counterparty protocols and forwards through the cluster client, which
follows the leader natively. This also closes the parent state's FIX-session gap: the in-process
QuickFIX/J acceptor dies with its pod, forcing reconnect and sequence renegotiation, while a
gateway-terminated session survives the leader change. The cost is one in-cluster hop on the
order path against multi-second failover outages removed from the client's view.
