# Plan: YU11-aeron-replication

## Goal

Add a rollback-safe Aeron + SBE replication transport to the YU10 order-matcher, backed by an
Archiving Media Driver sidecar and exact follower-journal durability watermarks. NATS remains the
default transport and shadow-validation authority until a coordinated pair cutover satisfies the
stored throughput, recovery, allocation, and failover gates. The default availability policy
degrades to the primary journal on follower loss; strict durability and fast-witness failover are
explicit opt-ins.

## Workstreams

1. **Schema and transport seam**: dependency-locked Aeron/Agrona/SBE toolchain, fixed input/ACK/
   handshake/control templates, golden N/N-1 vectors, `ReplicationTransport` selection, and
   exact-zero primary/follower agents.
2. **Shadow and cutover**: NATS-authoritative dual publishing, Aeron sequence/checksum comparison,
   schema-checksum handshake, coordinated pair selection through
   `BLP_REPLICATION_TRANSPORT`, and one-value NATS rollback.
3. **Durability policy**: expose the follower `Journaler.journaledSeq()` watermark to the ACK
   agent, preserve on-ring mode by default, implement durable ACK coalescing, and apply
   degraded-solo versus strict follower-loss policy without changing the primary journal's
   authority.
4. **Archive recovery**: Archiving Media Driver sidecar, live recording, snapshot manifest/chunk
   stream, retained-volume replay-to-live merge, empty-volume bootstrap, retention behind the
   minimum follower checkpoint, and fail-closed disk/catalog/schema handling.
5. **Failover**: unchanged Lease-gated default path plus an opt-in direct-heartbeat/NATS-KV
   witness path; epoch/session fencing, atomic witness claim, asynchronous Lease reconciliation,
   and immediate demotion on foreign proof.
6. **Runtime and operations**: compose pair, dedicated multi-node kind profile, GKE sidecar/UDP/
   NetworkPolicy/anti-affinity overlays, one-core sidecar budget, PVC expansion runbook, health,
   metrics, and alert rules.
7. **Proof**: deterministic transport/loss/replay harness, allocation and Epsilon-GC gates,
   on-ring versus true-durable ACK comparison, NATS/Aeron A/B/A, same-day single-BLP controls,
   failure matrix, and three-run GKE booked-order comparison.

## Key decisions

- `adr-038`: dual-capable transport with NATS default, shadow validation, and coordinated flip.
- `adr-039`: generated fixed SBE schema with epoch and contiguous sequence in every record.
- `adr-040`: Archive provides replication catch-up while journal recovery remains authoritative.
- `adr-041`: exact post-force journal watermark; degraded-solo default and strict opt-in.
- `adr-042`: separate Archiving Media Driver sidecar within a hard one-core budget.
- `adr-043`: direct heartbeat plus atomic NATS KV witness for the opt-in fast failover path.

## Exit Criteria

SC-AR01…08 are recorded in `generation/implementation-status.md`; generation exits zero with
all ancestor markers intact; the compose transport/recovery matrix passes; the default Lease path
and opt-in fast-witness path have separate evidence; GKE results satisfy both throughput gates or
the state keeps NATS selected.
