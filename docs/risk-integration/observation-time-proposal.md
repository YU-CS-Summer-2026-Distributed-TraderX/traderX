# Proposal: preserve observation time and replay provenance through EOD

Status: DESIGN ONLY, pending coordinator review. Inspected 2026-09-16 at integration commit
89719d60, after the accepted P1 chain. No runtime, database, wire schema, deterministic-core or
lineage change is implemented by this document. I1 observation packages and existing W0 contracts
remain unchanged. No live deployment claim follows from this source review.

## Decision requested

Approve designing upstream provenance capture plus an immutable, versioned EOD mark companion,
then a new opt-in bundle version referencing it. Do not try to reconstruct old observation times
from arrival timestamps. Keep a future CSV version as an alternative if consumers need flat files.
Agree separate policies for current-market valuation, historical replay and synthetic demonstration
before allowing any new input profile to claim suitability. A recently delivered historical mark
can be operationally fresh while unsuitable for current-market valuation.

## Verified path and where information disappears

Paths below are relative to the repo. For Java paths, the package prefix is
`src/main/java/finos/traderx/<component-without-hyphens>/` under the named runtime override.

| Stage | Operative source and inspected behavior |
| --- | --- |
| Publisher | `specs/YU16-cdm-instruments/generation/runtime-overrides/price-publisher/src/{main.js,taq-replay.js}`. `priceAt` emits original tape-window end as `asOf`, source and held state. `toPayload` preserves quote asOf/source for non-Treasury ticks, but does not forward held/replay context. Walked quotes use generation time; Treasury `asOf` uses treasury clock and has separate simulated/source fields. These timestamps do not all describe original market observations. |
| Typed consumer | YU16 trade-processor `model/PriceTick.java` retains asOf/source strings and tolerates unknown wire properties. It has no explicit replay, simulated or underlying-input provenance fields. |
| First loss | YU05 trade-processor `service/PriceTickHandler.java:onMessage` calls `record(ticker,price,System.currentTimeMillis())`. Both asOf and source are dropped. |
| History | YU06 trade-processor `service/PriceHistoryStore.java` stores only `PriceSample(price,timestampMillis)`. Its selection/TWAP APIs use that timestamp, currently arrival time. Replacing it with event time would also change TCA behavior and is not this proposal. |
| Quality / close | YU15 `service/EodQualityChecker.java` evaluates sample age against closeMillis. YU06 `service/EodPriceService.java` selects an at-or-before sample, produces a versioned close, copies sourceTickMillis into overrides and publishes after quality gates. Feed-age OK is not evidence of market recency. |
| Persistence | YU06 `model/EodPrice.java` and `repository/EodPriceSnapshotRepository.java` persist price, quality, source_tick_millis and override_reason per session_date/version/security. The model/data-model text calls sourceTickMillis event time, but the operative handler now supplies arrival. Historical rows have ambiguous semantics unless their producer version is known. |
| Extract reader | YU18 order-matcher `cluster/RiskExtractMain.java:loadMarks` queries only security/closing_price/quality from the exact published session/version. It drops even the stored timestamp. This supersedes the similar YU17 file. |
| Export | YU17 order-matcher `cluster/RiskExtractCsv.java` is operative; Mark contains only price/quality. Schema 3 records markSource/markQuality but no observation or arrival timestamp. Missing official closes may use CLUSTER_LAST_TRADE_AT_N; sequence membership does not establish market observation time. |
| Bundle / worker | YU18 `eod-risk-bundles/bundle.py` preserves CSV bytes and cut identity; v1/v2 marketInputs remain NOT_SUPPLIED. W0 accepts its pinned terms-v1 profile and performs no market computation. I1 packages separately preserve dated observations but cannot restore marks already stripped upstream. |

Last-wins ownership was checked across spec paths. Database creation also appears in YU06
`kubernetes-runtime/manifests/base/database-init-configmap.yaml`; any implementation must audit
all later same-name manifests and actual migration ownership before edits. This proposal does not
assume changing an initial-create script migrates an existing database.

## Proposed capture semantics

Capture distinct fields at ingestion, retaining the legacy arrival-indexed API for TCA until a
separate behavior change is agreed:

- `receivedAt`: trade-processor arrival, explicit offset-aware instant. Its meaning is delivery,
  not the instant the observation was true. The publisher may separately report emittedAt.
- `observationTime`: original observation instant when supplied with known semantics; otherwise
  null plus an allowlisted reason such as not-supplied, invalid-source-time or legacy-unavailable.
  Preserve the raw source timestamp privately where permitted; never substitute receivedAt.
- `timestampMeaning`: original-observation, derived-quote-generation or synthetic-generation.
  Treasury derived quotes must retain input observation references and valuation/generation context;
  their generated `asOf` alone cannot establish the underlying curve's observation time.
- `sourceClass`: observed, derived, synthetic or unknown, independent of delivery mode.
  `deliveryMode`: current-feed, historical-replay or unknown. A replay of observed data stays
  observed + historical-replay; it does not become synthetic just because delivery is simulated.
- Versioned source/dataset/series identifiers, original artifact hash where available, and source
  record/window identity. For replay: recorded epoch/run identity, tape business date/window,
  held-at-end indicator and replay transport revision where actually emitted. Do not mint run IDs
  independently at consumers or deduce replay provenance from a ticker or free-form source name.

Publisher support is needed to make these fields trustworthy enough to validate structurally. The
existing free-form source/asOf pair is an incomplete compatibility input, not automatic current-feed
classification. Unknown fields must remain unknown until a reviewed mapping exists. This is provenance
custody, not proof of vendor authenticity or permission to redistribute values.

Persist the selected observation and its metadata atomically with each immutable EOD snapshot row.
A manual override gets its own override origin, operator/action reference and creation time, while
retaining the superseded observation lineage separately. Copying the old sourceTickMillis must not
claim the override amount came from that tick. Existing authorization and quality gates stay intact;
no blanket historical-mark approval is introduced.

## Eligibility and unknown handling

Evaluate two independent dimensions: delivery freshness (receivedAt age) and market-input suitability
(original observation time, declared valuation context, source class and intended run mode).

| Input | Current-market request | Explicit replay/demo request |
| --- | --- | --- |
| Recent delivery of old replay observation | Not current-market eligible | May be eligible only for the declared historical context and agreed replay policy |
| Held-at-end replay | Cannot become current by republication | Retain original window/time and held flag; reject if outside agreed historical age window |
| Synthetic/generated mark | Not silently eligible as observed input | Only under explicitly allowed provenance and appropriate model/input assumptions |
| Missing/invalid observation time or legacy ambiguous sourceTickMillis | Unknown, fail required-current-input suitability | Explicitly unknown; accept only a separately agreed partial/demo policy, never relabel |
| Future observation or arrival after requested availability cutoff | Reject suitability | Reject suitability for that cut; retrospective availability is a separately named policy |
| Last trade fallback | Sequence proves it is in the cut, not when an external market observed it | Preserve LAST_TRADE lineage and unknown time unless authoritative event metadata exists |

Observation/publication/retrieval are distinct for I1. This mark proposal adds arrival/generation
concepts without treating arrival as publication. A valuation business date is not a timestamp.
Source timezones and date-only observations need an explicit calendar policy before comparisons;
no implicit midnight timestamps or universal tenor/freshness settings. Missing required inputs must
fail or report explicitly agreed partial coverage. W0 remains unusable for financial risk.

## Companion versus future extract version

| Choice | Benefit | Cost / constraint |
| --- | --- | --- |
| Versioned mark-provenance companion | Preserve exact schema-3 CSV and frozen v1/v2 files; richer source/replay/override lineage without repeating it per position | Only meaningful after upstream capture. Must be written from the same immutable selected marks, not an independent latest-feed lookup. Needs explicit join/completeness/hash checks and a new bundle/profile reference. |
| Future positions CSV version | Self-contained flat rows for consumers; explicit changed schema admission | Repeats instrument provenance per account; changes artifact hashes, CSV readers, golden fixtures and consumer versions. Still requires upstream capture and does not repair old exports. Complex derived/override lineage fits poorly in flat columns. |

Recommended candidate: companion entries keyed by security within a manifest-bound
clusterEpoch/sessionDate/priceSnapshotVersion/cutSha256, with per-entry mark amount/unit/source and
lineage. Include all exported securities, enforce one matching entry per security and agreement with
every corresponding position row. Keep account/position identity in the CSV. Include explicit unknown
entries for legacy/fallback marks, never omit them so coverage appears complete. Pin the original
positions/contracts hashes plus companion bytes and schema in a **new opt-in bundle version**; do not
attach unrecognized files to existing v1/v2 bundles. Exact field names and version number require review.
The companion must be produced atomically with the export/receipt or from retained immutable snapshot
metadata bound to that exact cut; no mutable latest provenance joins.

A companion built today can truthfully enumerate unknowns and copied export markSource/markQuality.
It cannot claim original timestamps, replay flags or dataset identity that history/persistence already
lost. Old snapshots remain unchanged. Any external reconstruction needs independently pinned evidence,
explicit inferred/reconstructed provenance and separate review; arrival alone is insufficient.

## Bounded migration and review gates

1. Agree intended run modes, unknown handling, source timestamp semantics and source/replay IDs with
   coordinator; review Alex consumer requirements separately. Choose owner state/ADR through the
   project's state process before substantial implementation; no lineage allocation in this proposal.
2. Add versioned publisher/consumer capture and immutable snapshot storage via an actual migration.
   Preserve the legacy sourceTickMillis behavior for existing readers, add explicit fields, and label
   old rows legacy-unavailable without backfilling observation time from that ambiguous column.
3. Dual-write and prove original observation, arrival and replay metadata survive close/override/
   publish/restart. Audit every operative override; generated-source proofs are required. No change
   to deterministic-core events, recovery or snapshot format is assumed by this path.
4. Add and validate the companion plus new opt-in bundle/profile. Preserve every frozen v1/v2 and
   shared fixture byte/hash; new synthetic fixtures get new directories and explicit versions.
   Existing W0 intake must continue rejecting unsupported versions instead of guessing compatibility.
5. Obtain Alex's schema/capability agreement, then perform independent numerical/readiness checks.
   Deployment/image/mount migration is a later separately authorized operation.

Proposed adversarial tests: old payload + fresh delivery; date-only/invalid/missing offset; future
observation; arrival after cut; paused/seeked/held replay with recorded revision; walk and derived
Treasury input time distinctions; duplicate and missing companion joins; changed source bytes;
manual override retaining original lineage without attributing its new value to the old tick;
last-trade fallback with no timestamp; snapshot persistence/restart; newer-cut/older-result rejection;
and unchanged original v1/v2 fixtures and W0 hashes. These are planned tests, not measured results.
