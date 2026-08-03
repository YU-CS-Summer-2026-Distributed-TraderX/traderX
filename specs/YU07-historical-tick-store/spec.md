# Feature Specification: Historical Tick Store

**Feature Branch**: `YU07-historical-tick-store`
**Created**: 2026-07-09
**Status**: Implemented
**Input**: Backlog item #2 in `issues/HANDOFF-idea-INDEX.md`, parented on `YU06-eod-price-production`

## User Stories

- As a quant building the execution algo engine, I want stored historical trade prints so I can
  compute a real volume profile for VWAP instead of a synthetic one.
- As a quant researching return/scenario data for VaR/ES work, I want stored historical trades and
  quotes queryable by symbol and date range so I can build return series without hand-rolling a data
  pipeline.
- As a platform engineer, I want TraderX's own live ticks captured continuously with zero risk to
  order-matching throughput, since the capture path is a new NATS subscriber, not a hot-path change.
- As a data engineer, I want a large third-party tick dataset (NYSE TAQ) normalized into the same
  schema as TraderX's own ticks, without extracting terabytes of intermediate CSV to disk first.
- As a maintainer, I want this state's spec pack to define the tick-store schema and ingestion
  contract before code generation, consistent with the project's spec-first process.

## Functional Requirements

- FR-TS01: The state SHALL capture every message published on `pricing.*` and every message
  published on `/accounts/*/trades` into the tick store.
- FR-TS02: Capture SHALL subscribe to existing broadcast NATS subjects without modifying any
  existing publisher or consumer of those subjects.
- FR-TS03: Captured rows SHALL be written to Parquet, partitioned by source, date, and symbol.
- FR-TS04: The state SHALL provide a normalizer that ingests a NYSE Daily TAQ Consolidated Quotes
  (CQ) CSV file into the same partitioned Parquet schema as live capture.
- FR-TS05: TAQ ingestion SHALL stream the source CSV directly from its zip archive into the
  normalizer without extracting the decompressed file to disk.
- FR-TS06: The state SHALL provide a query recipe (DuckDB over the Parquet store) supporting
  symbol- and date-range-filtered aggregation across both live and TAQ data uniformly.
- FR-TS07: Every Parquet file SHALL carry `source`, `event_type`, and `symbol` fields sufficient to
  distinguish live-captured rows from TAQ-ingested rows in any query.
- FR-TS08: The state SHALL provide a normalizer that ingests a NYSE Daily TAQ Consolidated Trades
  (CT) CSV file into the same unified Parquet schema as quotes ingestion and live capture.
- FR-TS09: Ingestion SHALL support processing an arbitrary batch of TAQ files (any date range, any
  mix of quotes/trades files) as a repeatable bulk operation, not just one file at a time by hand —
  the normalizers are general-purpose against any conforming TAQ file, not scoped to a specific
  dataset.

## Non-Functional Requirements

- NFR-TS01: Capture SHALL introduce no change to the order-matching hot path, the BLP journal, or
  any existing NATS publisher.
- NFR-TS02: Capture SHALL run as an independent, restart-safe process; a capture outage SHALL cause
  no backpressure on any publisher (broadcast subjects, no ack required by the publisher).
- NFR-TS03: TAQ ingestion peak disk usage SHALL be bounded by the size of one ingested file's own
  output Parquet partition, not the size of its decompressed source CSV.
- NFR-TS04: The tick-store component SHALL use existing, proven libraries (DuckDB, `unzip`) for
  columnar storage and archive streaming rather than a hand-rolled format or query engine.

## Success Criteria

- SC-TS01: Generation hook exists and is runnable (`pipeline/generate-state-YU07-historical-tick-store.sh`).
- SC-TS02: State smoke test path is defined (`scripts/test-state-YU07-historical-tick-store.sh`).
- SC-TS03: Smoke checks validate that the shared `kustomization.yaml` retains every ancestor state's
  resource entries alongside this state's two additions.
- SC-TS04: A runnable self-check demonstrates the capture writer and the TAQ quotes normalizer both
  produce Parquet files that a single DuckDB query can read back uniformly by `source`.
- SC-TS05: The TAQ quotes normalizer is verified against an actual sample file, not a synthetic
  fixture.
- SC-TS06: The TAQ trades normalizer is verified against an actual sample CT file, not a synthetic
  fixture.
- SC-TS07: The bulk-ingestion path is verified at real production scale — a multi-file, multi-GB
  batch job, not just a single hand-run file — using whatever real TAQ files are available as the
  verification corpus; any future TAQ files use the same path unchanged.

## Addendum: KDB-X analytical layer

Added to this state after its original implementation. KDB-X is a query layer over the corpus this
state already writes and a home for TraderX's own captured flow, so it extends `YU07` rather than
standing as a state of its own. Nothing here changes the ingestion contract above: the Parquet
store, its partition layout and its schema are unmodified, and no existing publisher, consumer or
hot path is touched.

Where the pieces live, because they do not all live in one layer: the q store and its gates are
this state's (`generation/runtime-overrides/tick-store/kdb/`). The leader-side capture tap that
feeds `txOrder`/`txTrade` is `KdbTapWriter`, and it ships in the `YU13-limit-order-book` layer
because it sits in the clustered `order-matcher` that only exists from `YU12-aeron-cluster` onward.
On this state and its pre-cluster descendants the store loads captured sessions from file, and its
gates run against the committed fixture without a cluster.

### Functional Requirements

- FR-TS10: The KDB-X layer SHALL read the existing partitioned Parquet corpus in place. It SHALL
  NOT convert, rewrite or copy the corpus into a second on-disk format.
- FR-TS11: The market tape SHALL be exposed as `quote` and `trade`, and TraderX's own captured
  flow as `txOrder` and `txTrade`. A tape print and an engine execution SHALL NOT share a table
  name, so no aggregate can silently span both.
- FR-TS12: TraderX's own order lifecycle and executions SHALL be captured off the running cluster
  by a leader-side tap that writes outside the consensus path.
- FR-TS13: Captured rows SHALL be qualified by cluster epoch, since `orderRef` restarts at 1 on a
  fresh incarnation and a bare reference would merge two different orders.
- FR-TS14: A security whose ticker was never registered SHALL be captured under a synthetic
  identifier rather than dropped, so an unresolved symbol cannot thin the store unnoticed.
- FR-TS15: Both stores SHALL be verified by runnable q gates that exit non-zero on failure.

### Non-Functional Requirements

- NFR-TS05: The capture tap SHALL NOT sit in the apply path. The service thread SHALL do no more
  than enqueue a record with a non-blocking offer; every file system call SHALL happen on a
  separate thread. A stalled sink SHALL drop rows rather than block apply.
- NFR-TS06: Dropped rows SHALL be counted and reported — on the first drop, periodically
  thereafter, and in totals at shutdown — so a partial capture can never be read as a census.
- NFR-TS07: Capture disk SHALL be bounded by configuration (`KDB_TAP_MAX_MB`, default 256), so a
  long session cannot consume the disk the Aeron Archive needs.
- NFR-TS08: The KDB-X layer SHALL hold no authoritative state. Deleting it entirely SHALL leave
  cluster recovery byte-identical; the consensus journal remains the only replay source of truth.
- NFR-TS09: The tap SHALL be inert unless configured (`KDB_TAP_DIR` unset), costing one null check
  per output event.

### Success Criteria

- SC-TS08: `selfcheck.q` gates the tape store with 17 checks over a real corpus sample, every
  expected value computed independently in a second engine (DuckDB) over the same files, so the
  store is checked against something other than itself.
- SC-TS09: `txselfcheck.q` gates the session store with 18 checks over a fixture the cluster itself
  produced under real consensus, and runs with no cluster, corpus or network. It is falsifiable:
  removing one side of a cross from the fixture fails the gate rather than quietly halving volume.
- SC-TS10: A full-corpus aggregate completes well inside KDB-X Community's 16 GiB limit — measured
  peak 768 MiB over 47.8M quote rows, because the reader works a row group at a time.
