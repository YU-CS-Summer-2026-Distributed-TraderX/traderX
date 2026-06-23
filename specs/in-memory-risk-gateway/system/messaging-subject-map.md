# Messaging Subject Map: In-Memory Risk Gateway

Parent: `009b-lmax-sequencer-architecture`

Final physical subject names are locked during implementation against the selected retained-stream
configuration. This document defines the required logical subjects and guarantees.

## Durable Control Subjects

| Logical subject | Producer | Gateway consumer | Sequencer adapter | Retention |
| --- | --- | --- | --- | --- |
| `control.account` | account-service outbox | account replica | account control event | durable/replayable |
| `control.entitlement` | account-service outbox | entitlement replica | entitlement event | durable/replayable |
| `control.security` | reference-data change log | security replica | security event | durable/replayable |
| `control.security-status` | reference/risk control | security-status replica | status event | durable/replayable |
| `control.risk-policy` | risk administration | policy replica | policy event | durable/replayable |
| `control.restriction` | risk administration | restriction replica | restriction event | durable/replayable |
| `control.kill-switch` | risk administration | kill-switch replica | kill-switch event | durable/replayable |

Each message carries schema version, source epoch/version, aggregate key, effective event time,
provenance, operation, and complete decision value. Consumers require explicit ack/position and access
to stream high watermark.

## Delivery Rules

- At-least-once delivery is acceptable because source epoch/version makes apply idempotent.
- Reordering beyond the declared source version is detected and not silently applied.
- Version gaps and epoch changes trigger unready/re-bootstrap behavior.
- Retention must exceed maximum expected outage plus snapshot/recovery window.
- Core NATS best-effort delivery is insufficient unless backed by an equivalent retained authoritative
  log and gap recovery path.
- Subject ACLs restrict control publication to authoritative owners and read access to Gateway/control
  adapter identities.

## Snapshot Surfaces

Snapshots are request/response control-plane contracts rather than per-command calls. They may use HTTP
or a durable request/reply transport, but must return the `SnapshotEnvelope` defined in
`contracts/contract-delta.md`. Snapshot retrieval occurs only during bootstrap/re-bootstrap.

## Global Input Stream

Control adapters convert durable subject deltas to inherited globally sequenced input events. The
global journal, not the external subject timing, establishes deterministic order relative to prices and
commands.

Submitted commands add:

- `ORDER_SUBMITTED`
- `TRADE_SUBMITTED`

Decision-relevant controls add the event types in `contracts/contract-delta.md`.

## Existing Business Subjects (Unchanged)

- `/orders`
- `/accounts/<accountId>/orders`
- `/trades`
- `/accounts/<accountId>/trades`
- `/accounts/<accountId>/positions`
- `pricing.*`

Only BLP-accepted commands produce accepted order/trade/position business outputs. Rejections are
returned on the correlated request/response path and may emit bounded diagnostics; they do not publish
fake accepted business events.

## Failure / Backpressure

- Control publisher failure retains the source outbox record for retry.
- Consumer lag is bounded by retained stream capacity and observed via replica lag metrics.
- Bootstrap buffer overflow fails bootstrap and restarts from a new snapshot; it does not drop deltas.
- Global input/output ring backpressure remains the inherited `009b` contract.
