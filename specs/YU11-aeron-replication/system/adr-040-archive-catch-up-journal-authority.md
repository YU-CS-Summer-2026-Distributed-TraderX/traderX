# ADR-040: Aeron Archive provides catch-up; the application journal remains authority

Status: Accepted

## Context

Reliable live UDP retransmission does not provide offline store-and-forward after a subscriber
has been absent beyond the sender window. The inherited follower uses JetStream history for
catch-up, while each order-matcher journal and snapshot define business recovery.

## Decision

Each order-matcher pod runs an Archiving Media Driver sidecar that records replication data and
snapshot bundles on the pod's persistent volume.

- A retained-volume follower recovers its journal, replays Archive from its checkpoint, and
  merges into the live stream.
- An empty-volume follower installs a complete checksummed snapshot bundle and replays the
  Archive tail from the manifest position.
- Checkpoints persist epoch, input sequence, recording identity/position, and schema version.
- Archive retention stays behind the minimum follower checkpoint.
- Missing/corrupt/schema-incompatible data, catalog failure, unavailable replay positions, and
  disk-watermark faults keep the follower unready and unpromotable.
- The application journal remains the order/fill recovery authority; Archive is a replication
  catch-up index and transport recording.

## Consequences

Follower absence no longer depends on a live Aeron retransmission window. Snapshot and replay
state becomes an explicit operator surface with catalog, disk, lag, and checksum diagnostics.
Archive loss cannot invent or override business state.
