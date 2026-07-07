# YU04-durable-control-feeds

Adopts ADR-019's watermarked-snapshot-plus-buffered-deltas replica bootstrap protocol, replacing
YU03's one-shot REST bootstrap with real durable outbox feeds from `account-service` and
`reference-data` into NATS JetStream.

- **Parent state:** `YU03-in-memory-risk-gateway`
- **Design baseline:** ADR-019 (`specs/YU03-in-memory-risk-gateway/system/adr-019-watermarked-replica-bootstrap.md`,
  written in full during YU03, deliberately deferred there), ADR-021 (this state's own outbox
  mechanism decision), ADR-020 (control events in the global journal — unchanged by this state).
- **Read first:** `spec.md` (scope + what changes), `system/adr-021-transactional-outbox-jetstream-feeds.md`
  (the outbox mechanism decision), `requirements/functional-delta.md` (per-requirement status),
  `generation/implementation-status.md` (what is done vs deferred).

Generate:

```bash
bash pipeline/generate-state.sh YU04-durable-control-feeds
(cd generated/code/target-generated/order-matcher && ./gradlew test)
(cd generated/code/target-generated/account-service && ./gradlew test)
(cd generated/code/target-generated/reference-data && npm test)
```

This state touches three services' persistence/messaging layers — `order-matcher` (`ReplicaBootstrap`
rewrite), `account-service` (new outbox table + publisher), and `reference-data` (new persistence +
outbox table + publisher, replacing its previous CSV-only read-only design). See `data-model.md` for
the new schema and `system/architecture.md` for the end-to-end flow.
