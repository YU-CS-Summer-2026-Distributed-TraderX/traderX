# Functional Delta: YU11-aeron-replication

Parent: `YU10-fix-ingress`

| ID | Delta |
|---|---|
| FD-AR01 | Add `nats|aeron` replication selection with NATS default and coordinated peer/schema handshake. |
| FD-AR02 | Add NATS-authoritative Aeron shadow recording/consumption with contiguous sequence and payload-checksum comparison, never double injection. |
| FD-AR03 | Encode the inherited 64-byte input event through generated SBE codecs directly in an Aeron claimed buffer. |
| FD-AR04 | Decode contiguous current-epoch Aeron data directly into the follower input ring; fail closed on schema, flag, epoch, sequence, or checksum fault. |
| FD-AR05 | Expose follower `Journaler.journaledSeq()` to the ACK agent and map local ring sequences to highest contiguous primary durable sequence. |
| FD-AR06 | Preserve on-ring ACK default; add exact durable ACK coalescing for NATS and Aeron. |
| FD-AR07 | Add degraded-solo default and strict follower-loss policy; strict requires durable ACK. |
| FD-AR08 | Add per-pod Archiving Media Driver recording, retained-volume replay-to-live merge, and checksummed empty-volume snapshot bootstrap. |
| FD-AR09 | Persist epoch/input/recording/schema checkpoints and retain Archive segments behind the minimum follower checkpoint. |
| FD-AR10 | Preserve Lease-gated failover default; add direct Aeron heartbeat plus atomic NATS-KV witness fast mode. |
| FD-AR11 | Add sidecar/UDP/NetworkPolicy/PVC/runtime configuration for compose, multi-node kind, and GKE. |
| FD-AR12 | Add replication, Archive, witness, shadow-comparison, and failover health/metric surfaces. |

All YU10 REST, FIX, entitlement, risk, journal, matching, output, and messaging contracts remain
unchanged.
