# ADR-039: Generated SBE records with epoch and contiguous sequence

Status: Accepted

## Context

The inherited NATS record is a hand-coded fixed 64-byte layout. It carries a local Disruptor
sequence for ACK correlation but has no generated schema identity. Aeron requires a deterministic
binary contract that rejects stale leaders, incompatible peers, gaps, and unknown required data
before follower ring injection.

## Decision

YU11 uses generated SBE codecs with dependency-locked Aeron/Agrona/SBE versions.

- The input message remains 64 bytes: the 8-byte SBE header plus a 56-byte fixed root block.
- Logical identity is `(clusterId,leaderEpoch,inputSeq)`; `inputSeq` is contiguous within an epoch.
- Input, durable ACK, hello/challenge, heartbeat, snapshot manifest/chunk, and replay status use
  separate templates.
- The primary encodes directly in an `ExclusivePublication.tryClaim` buffer.
- The follower decodes directly into a claimed input-ring slot after schema/template/version,
  flags, epoch, sequence, and checksum validation.
- CI stores N/N-1 golden vectors and a schema checksum embedded in both application and sidecar.

## Consequences

Named generated fields replace magic-offset evolution without changing the compact payload.
Stale epochs and sequence gaps are explicit protocol faults. Additive SBE changes remain
decodable through schema version rules, while the serving pair still requires matching checksums
for its coordinated cutover.
