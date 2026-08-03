# Plan: YU12-aeron-cluster

## Goal

Replace the YU11 hand-built HA machinery — Lease election, custom MDC replication, fast-witness
CAS, and snapshot-bundle recovery — with Aeron Cluster Raft consensus hosting the unchanged
deterministic matching/risk core, and prove the three acceptance gates: strict no-ID-reuse
recovery, client-observed failover under one second through a leader-following gateway, and GKE
throughput at or above the stored YU11 Aeron HA baseline.

## Workstreams

1. **Clustered service core**: host `MatchingEngine` plus two-tier risk inside
   `ClusteredService.onSessionMessage` on a single-node cluster; reuse the SBE ingress codecs;
   prove an order round-trips the consensus log and matches.
2. **Snapshot completeness**: serialize the full deterministic state — book, ID generators, trade
   counters, idempotency, risk reservations, symbol identity, control versions — in
   `onTakeSnapshot` bound to the applied log position; restore on `onStart`; assert generators
   exceed every ID ever issued; restart and promotion proofs.
3. **Three-member cluster on kind**: StatefulSet member identity, headless discovery, per-pod
   log/snapshot PVCs, leader election, leader-kill re-election, wiped-member rejoin via snapshot
   retrieval plus log replay.
4. **Ingress gateway**: FIX/REST termination tier speaking the Aeron Cluster client inward;
   leader-follow on role change with counterparty session survival; the risk gateway's
   control-feed admission state and its readiness contract.
5. **Consensus-log inputs**: feed adapter publishing price ticks and control/policy updates as
   cluster ingress; removal of every side-channel input path.
6. **Runtime and operations**: kind multi-node profile and GKE `blp-pool` overlays, cluster
   health/metrics surfaces, snapshot/log disk management.
7. **Proof**: the no-ID-reuse recovery matrix, client-observed failover timing, allocation and
   Epsilon gates, and the three-run GKE comparison labelled `aeron-cluster`.

## Key decisions

- `adr-044`: Aeron Cluster Raft replaces the hand-built election/replication/recovery stack.
- `adr-045`: the consensus log is the only input path into the deterministic state machine.
- `adr-046`: snapshot completeness covers every future-output generator, with a strict
  no-ID-reuse recovery assertion.
- `adr-047`: a stateless-forward gateway tier terminates FIX/REST and follows the cluster leader.

## Exit Criteria

SC-AC01…06 are recorded in `generation/implementation-status.md`; generation exits zero with all
ancestor markers intact; the single-node and three-member cluster proofs pass; the recovery
matrix, failover timing, and GKE throughput gates are satisfied with stored evidence.
