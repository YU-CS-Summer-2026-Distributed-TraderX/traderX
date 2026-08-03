# Feature Specification: EOD Price Production and Overnight Batch Chain

**Feature Branch**: `YU06-eod-price-production`
**Created**: 2026-07-08
**Status**: Implemented
**Input**: Backlog item #1 in `issues/HANDOFF-idea-INDEX.md`, parented on `YU05-post-trade-compliance`

## User Stories

- As an operations user, I want to trigger an end-of-day session close so official closing prices
  are produced for every traded instrument on a predictable schedule or on demand.
- As an operations user, I want a closing price that is stale, has moved too far, or is missing to
  be held back from publication so no downstream job ever consumes a price I haven't signed off on.
- As an operations user, I want to override a flagged closing price with a reason so I can resolve
  a data-quality exception without editing any previously published price.
- As a downstream batch job owner, I want a durable "prices ready" signal so my job still runs
  correctly even if it was offline when the event fired.
- As a downstream batch job owner, I want to mark positions against one exact published price
  version so my results can never disagree with another job's numbers for the same session.
- As a downstream batch job owner, I want an account with a missing or flagged holding to be held
  back rather than marked with a guessed price.
- As a platform engineer, I want the EOD chain's health (sessions published, quality flags, accounts
  marked/halted, end-to-end latency) visible in Grafana so a stuck or degraded chain is caught quickly.
- As a maintainer, I want this state's spec pack to define the EOD contract before code generation,
  consistent with the project's spec-first process.

## Functional Requirements

- FR-EOD01: The state SHALL provide a session-close trigger (`POST /eod/session/close`) usable both
  from a scheduled job and from an on-demand operator call.
- FR-EOD02: The state SHALL define the closing price for an instrument as its last trade price from
  the existing price feed.
- FR-EOD03: Re-running production for a session date SHALL create a new version rather than mutating
  a previously written one.
- FR-EOD04: EOD production SHALL run entirely off the order-matching hot path, reading only the
  existing price feed and writing only its own tables.
- FR-EOD05: Every `/eod/*` endpoint SHALL require an authenticated admin caller.
- FR-EOD06: The state SHALL detect an expected instrument with no price sample and classify it as
  missing.
- FR-EOD10: The state SHALL classify an instrument's price as stale when its newest sample is older
  than a configured threshold at session close.
- FR-EOD11: The state SHALL classify an instrument's price as a spike when it moves beyond a
  configured percentage from the prior published close.
- FR-EOD12: The state SHALL provide a manual override endpoint that supplies a corrected price for
  one instrument and writes it as a new version.
- FR-EOD13: An override SHALL record its reason, and prior versions SHALL remain unchanged.
- FR-EOD20: The state SHALL persist closing prices as a versioned, immutable snapshot keyed by
  session date and version.
- FR-EOD21: The state SHALL emit a durable gate event carrying the session date and version once a
  snapshot is published.
- FR-EOD22: The gate event SHALL be emitted only after its snapshot version is fully committed.
- FR-EOD23: Publication SHALL be blocked while any instrument in the version is unresolved
  stale, spiked, or missing.
- FR-EOD30: The state SHALL provide a downstream consumer that marks positions only in response to
  the gate event.
- FR-EOD31: The consumer SHALL read prices only from the exact snapshot version named in the gate
  event, never from live ticks.
- FR-EOD32: An account with any holding missing or unresolved in the snapshot SHALL be held back
  from marking rather than marked with an incomplete or stale price.
- FR-EOD33: The consumer SHALL emit a completion event once it finishes processing a session.
- FR-EOD40: The state SHALL expose metrics for sessions published, quality flags, accounts
  marked/halted, and chain latency, visualized in a provisioned Grafana dashboard.

## Non-Functional Requirements

- NFR-EOD01: Every consumer of a published snapshot version SHALL see identical prices for that
  version; a correction SHALL always produce a new version, never an in-place edit.
- NFR-EOD02: The gate event SHALL be durable so a consumer that is offline when it is published
  still receives it once it reconnects.
- NFR-EOD03: EOD production and consumption SHALL introduce no changes to the order-matching hot
  path or its event schema.
- NFR-EOD04: Chain metrics SHALL remain bounded-cardinality aggregate counters/gauges, not
  per-account or per-instrument label series.
- NFR-EOD05: Consumer processing of a redelivered gate event SHALL be idempotent.
- NFR-EOD06: EOD endpoints SHALL reuse the existing authentication mechanism rather than introduce
  a new one.
- NFR-EOD07: Chain orchestration SHALL be implemented as a lightweight event chain, not a workflow
  engine.
- NFR-EOD08: Every generated file this state shares with an ancestor state SHALL retain that
  ancestor's content alongside this state's additions.

## Success Criteria

- SC-EOD01: Generation hook exists and is runnable (`pipeline/generate-state-YU06-eod-price-production.sh`).
- SC-EOD02: State smoke test path is defined (`scripts/test-state-YU06-eod-price-production.sh`).
- SC-EOD03: Smoke checks validate that shared generated files retain both this state's and every
  ancestor state's content.
- SC-EOD04: Unit tests validate producer quality classification, versioning/immutability, the
  publish fail-safe gate, and auto-publish behavior.
- SC-EOD05: Unit tests validate consumer mark-to-close computation, the fail-safe halt on a
  missing/flagged holding, and idempotent reprocessing of a redelivered event.
- SC-EOD06: Grafana dashboard is provisioned for the EOD chain metric set.
- SC-EOD07: Generated snapshot branch and tag strategy are defined in the state catalog.
