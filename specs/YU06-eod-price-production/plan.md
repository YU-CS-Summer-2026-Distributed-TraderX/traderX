# Implementation Plan: YU06-eod-price-production

## Goal

Produce a versioned, immutable end-of-day closing-price snapshot, gate it behind a durable event,
and drive one downstream consumer off that event fail-safely — reusing existing services and
infrastructure rather than standing up new ones.

## Workstreams

1. Schema
   - Add the EOD tables to the real runtime database-init ConfigMap, preserving every ancestor
     state's existing columns and tables.
2. Producer (trade-processor)
   - Versioned snapshot repository, quality classifier, produce/override/publish service.
   - Durable JetStream publisher for the gate event, reusing the existing outbox publish pattern.
   - Admin-JWT-gated REST control surface.
3. Consumer (position-service)
   - Durable JetStream subscriber, snapshot reader, idempotent P&L writer.
   - Fail-safe per-account halt on a missing/flagged holding; completion event on finish.
4. Trigger + observability
   - Scheduled session-close CronJob alongside the existing on-demand endpoint.
   - Micrometer metrics on both services and a Grafana chain-status dashboard.
5. State registration
   - Spec pack, generation hook + render script, catalog entry, runtime harness registration.
6. Validation
   - Unit tests for producer classification/versioning/fail-safe gate.
   - Unit tests for consumer marking/fail-safe halt/idempotent redelivery.
   - Generation-propagation check confirming every ancestor state's shared-file content survives.

## Key decisions

- Producer lives in trade-processor (existing price feed, database access, and auth); consumer
  lives in position-service (owns positions). Neither introduces a new microservice.
- Closing price is the last trade price from the existing feed; the snapshot is versioned and
  immutable so every consumer reads identical prices for a session.
- Orchestration is a lightweight JetStream event chain, reusing the durable-publish pattern already
  established for control feeds.
- Publication is blocked while any instrument is unresolved stale, spiked, or missing; a consumer
  halts an account rather than mark it with an incomplete price.

## Exit Criteria

- Spec and tasks are complete and reviewed.
- Generation hook produces expected artifacts and exits successfully.
- Unit test suites pass for both services.
- Generated shared files (database-init ConfigMap, application properties, Grafana dashboards
  ConfigMap) retain every ancestor state's content alongside this state's additions.
- State can be published to `code/generated-state-YU06-eod-price-production`.
