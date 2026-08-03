# Feature Specification: Durable Control Feeds for the Risk Gateway

**Feature Branch**: `YU04-durable-control-feeds`
**Created**: 2026-07-06
**Status**: Implemented
**Input**: Adoption of ADR-019
(`specs/YU03-in-memory-risk-gateway/system/adr-019-watermarked-replica-bootstrap.md`), the
watermarked replica-bootstrap protocol YU03 specified in full but did not build. The `FR-IMRG*`
requirement namespace is inherited verbatim; per-requirement status is tracked in
`requirements/functional-delta.md` and `requirements/nonfunctional-delta.md`.

This state replaces `ReplicaBootstrap`'s one-shot REST fetch — safe in YU03 only because there was a
single co-located Gateway with no live external delta stream to race — with the full ADR-019
subscribe-buffer-snapshot-catchup protocol, backed by real durable outbox feeds on `account-service`
and `reference-data`. It is the first state to modify those two services' persistence layers;
everything through YU03 was order-matcher-only. The two-tier Gateway+BLP admission pipeline
(ADR-018), the journal/replication wire format, and the snapshot format are all unchanged — this
state only replaces what feeds `GatewayReplicaStore`'s existence/identity records at the edge.

## User Stories

- As a risk operator, I want an account or security control change to reach the Gateway replica
  durably, so a change I make while a replica is briefly offline is still applied once it reconnects
  rather than silently lost.
- As a risk operator, I want a replica to declare itself ready only once it has caught up to the
  latest control state from every source, so it never admits orders against a stale universe.
- As a platform engineer, I want each source's control feed to carry a version and epoch so the
  replica can detect a gap, a regression, or a resync and recover from it automatically.
- As a platform engineer, I want a corrupted or out-of-order control update to be quarantined and
  trigger a fresh bootstrap of that one source, without disturbing the other source's feed.
- As a service owner, I want the control feed to be published from the same transaction that writes
  the business record, so the feed can never diverge from the source of truth.

## Functional Requirements

- FR-IMRG04: `order-matcher`'s replica bootstrap SHALL, per source, subscribe to the durable stream
  and buffer, fetch the watermarked snapshot, verify and atomically install it, then apply buffered
  deltas above the snapshot watermark (same epoch, in version order) before continuing live
  consumption.
- FR-IMRG05: A source SHALL be considered ready only once the replica has caught up to that source's
  observed high watermark; the Gateway SHALL be marked ready only once every source is ready.
- FR-IMRG03: Each control record SHALL carry a real per-source epoch and monotonic version, not an
  internally-assigned version only.
- FR-IMRG32: `account-service` and `reference-data` SHALL each publish their control changes to a
  durable JetStream stream (`TRADERX_CONTROL_ACCOUNT` / `TRADERX_CONTROL_SECURITY`) with retention
  and replay, via a transactional outbox written in the same transaction as the business record.
- FR-IMRG33: Each source SHALL expose a watermarked-snapshot endpoint (`GET /account/control-snapshot`,
  `GET /stocks/control-snapshot`) carrying schema version, source epoch, watermark, count, and a
  checksum, so a bootstrapping replica can verify and install a consistent baseline.
- FR-IMRG34: A gap, version regression, epoch change, or failed checksum/count verification on a
  source SHALL quarantine that source, revoke overall Gateway readiness, and force a fresh bootstrap
  of that source only — never the other source.
- FR-IMRG-OUTBOX-ORDER: The outbox publisher on each source SHALL publish unpublished rows in strict
  version order and SHALL NOT skip ahead past a row whose publish failed.

## Non-Functional Requirements

- NFR-IMRG03: This state SHALL not change the BLP decision path, journal/replication wire format, or
  snapshot format; `GatewayReplicaStore` is edge-only runtime state (rebuilt on every boot, never
  journaled or snapshotted), so replay determinism is unaffected by construction.
- NFR-IMRG-DURABLE: Control feeds SHALL use JetStream (not core NATS) so a replica that is offline
  when a delta is published still receives it, and the watermark-catchup step has retention to
  replay from.
- NFR-IMRG-ISOLATION: Each source SHALL keep independent epoch, watermark, and quarantine state so a
  fault on one source cannot force re-bootstrapping the other.
- NFR-IMRG-DEDUPE: Published deltas SHALL carry a stable `Nats-Msg-Id` (`account:<version>` /
  `security:<version>`) so a redelivered or re-published row is de-duplicated by JetStream.
- NFR-IMRG-NONREGRESSION: The existing non-control-plane read/write APIs of `account-service` and
  `reference-data` (positions, trades, people, existing `GET /account/` and `GET /stocks` shapes)
  SHALL remain backward-compatible; only additive endpoints and fields are introduced.

## Success Criteria

- SC-IMRG04-01: `account-service` unit tests validate outbox/business-row atomic commit and
  rollback, watermark/checksum correctness, and publisher order-preservation with no skip-ahead.
- SC-IMRG04-02: `reference-data` unit tests validate the same outbox atomicity, checksum stability,
  and publisher ordering against its new MariaDB persistence.
- SC-IMRG04-03: `order-matcher` unit tests (`ControlFeedBootstrapStateTest`) cover ADR-019's full
  validation list — buffered-during-window replay, duplicate/reorder/gap/epoch-change,
  buffer overflow, checksum/count mismatch, readiness gating, and re-bootstrap after quarantine.
- SC-IMRG04-04: The full order-matcher suite (including `RiskReplayDeterminismTest` and snapshot-v3
  tests) passes unchanged, confirming no journal/snapshot format impact.
- SC-IMRG04-05: `bash pipeline/generate-state.sh YU04-durable-control-feeds` exits 0 and the
  generated `order-matcher`/`account-service`/`reference-data` trees build and test green.
- SC-IMRG04-06: Generated shared files retain every ancestor state's content alongside this state's
  additions.
