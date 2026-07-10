# Feature Pack: YU07-historical-tick-store

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Implemented
Track: `architecture`
Lineage role: `optional`
Previous state: `YU06-eod-price-production`

This pack defines a historical tick store — a new `tick-store` component persisting TraderX's own
live ticks and a normalized slice of NYSE TAQ data into one partitioned Parquet schema, queryable
uniformly through DuckDB — on top of the `YU06-eod-price-production` baseline.

Primary intent:

- capture TraderX's own live ticks (`pricing.*` price ticks, `/accounts/*/trades` fills) with zero
  change to any existing publisher, consumer, or the order-matching hot path,
- normalize a real NYSE TAQ Consolidated Quotes CSV sample into the same schema, streamed directly
  from its zip archive without extracting the decompressed file to disk,
- serve both uniformly through a DuckDB query recipe over the partitioned Parquet store.

Core artifacts:

- `spec.md`
- `requirements/functional-delta.md`
- `requirements/nonfunctional-delta.md`
- `research.md`
- `data-model.md`
- `quickstart.md`
- `contracts/contract-delta.md`
- `system/architecture.model.json`
- `system/architecture.md`
- `system/runtime-topology.md`
- `system/messaging-subject-map.md`
- `system/adr-029-tick-store-component-and-streaming-taq-ingestion.md`
- `generation/generation-hook.md`
- `generation/implementation-status.md`

Target runtime behavior:

- `tick-store` (new component) runs `capture.py` continuously against the NATS broker, and
  `ingest_taq_quotes.py` on demand against a TAQ CSV piped through `unzip -p`.
- Everything else (deploy/runtime harness, observability stack, every existing service) is inherited
  unchanged from `YU06-eod-price-production`.
