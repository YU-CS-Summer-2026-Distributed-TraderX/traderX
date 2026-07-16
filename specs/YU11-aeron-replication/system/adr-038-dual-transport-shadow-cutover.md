# ADR-038: Dual transport with shadow validation and coordinated cutover

Status: Accepted

## Context

File-backed NATS is the measured YU02 replication baseline and the deployed recovery path. Aeron
changes transport, schema, sidecar, networking, and catch-up behavior at once. Replacing the NATS
classes in their owner layer would remove a known rollback and force mixed-version pods to infer
compatibility from connection behavior.

## Decision

The YU11 order-matcher contains both NATS and Aeron replication implementations behind the
existing delegating handler seam.

- `BLP_REPLICATION_TRANSPORT=nats` is the default and rollback value.
- `aeron` is authoritative only when both ordinal peers authenticate the same transport, cluster,
  schema checksum, and epoch.
- `BLP_REPLICATION_AERON_SHADOW=true` with NATS authoritative publishes/records/consumes Aeron
  data and compares contiguous sequence plus payload checksum, but never injects a second event
  into the follower BLP and never gates the primary.
- Transport selection changes through a coordinated pair restart. Mixed peers refuse readiness.

## Consequences

The deployed NATS path remains intact and measurable. Shadow evidence exercises encoding,
networking, recording, decoding, and checksum comparison before Aeron becomes authoritative.
The binary and tests carry two transports, so the transport interface and configuration matrix
must remain explicit and small.
