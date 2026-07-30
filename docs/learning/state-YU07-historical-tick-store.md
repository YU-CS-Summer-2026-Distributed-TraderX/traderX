---
title: "State YU07-historical-tick-store: Historical Tick Store"
---

# State YU07-historical-tick-store Learning Guide

## Position In Learning Graph

- Previous state(s): [YU06-eod-price-production](/docs/learning/state-YU06-eod-price-production)
- Dotted-line parent(s): none
- Next state(s): [YU08-execution-algo-engine](/docs/learning/state-YU08-execution-algo-engine)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [YU07-historical-tick-store](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU07-historical-tick-store)
- Authoring branch (spec source): [YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU15-eod-risk-extract)

## Code Comparison With Previous State

- Compare against `YU06-eod-price-production`: [YU06-eod-price-production...YU07-historical-tick-store](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/YU06-eod-price-production...YU07-historical-tick-store)

## Plain-English Code Delta

- **Added:** A `tick-store` component that subscribes to the existing `pricing.*` and `/accounts/*/trades` NATS
- **Added:** Capture is an extra subscriber on broadcast subjects that carry no ack back to a publisher, so a
- **Added:** A Parquet store partitioned as `source=<live|taq>/dt=<date>/symbol=<SYM>/`, ZSTD-compressed and
- **Added:** A unified row schema carrying `source`, `event_type` and `symbol` on every row, so live-captured
- **Added:** `ingest_taq_quotes.py`, a normalizer that turns a NYSE Daily TAQ Consolidated Quotes (CQ) CSV into
- **Added:** `ingest_taq_trades.py`, the equivalent normalizer for TAQ Consolidated Trades (CT) files, verified
- **Added:** Ingestion that streams a source CSV straight out of its zip archive, `unzip -p` piped into
- **Added:** Peak ingestion disk is one output Parquet partition rather than a day's ~76 GiB decompressed CSV,

## Run This State

```bash
inherits YU06-eod-price-production runtime harness
```

## Canonical Spec Links

- State spec pack: [/specs/YU07-historical-tick-store](/specs/YU07-historical-tick-store)
- Architecture: [/specs/YU07-historical-tick-store/system/architecture](/specs/YU07-historical-tick-store/system/architecture)
- Flows / topology: [/specs/YU07-historical-tick-store/system/runtime-topology](/specs/YU07-historical-tick-store/system/runtime-topology)
- Research: [link](/specs/YU07-historical-tick-store/research)
- Data model: [link](/specs/YU07-historical-tick-store/data-model)
- Quickstart: [link](/specs/YU07-historical-tick-store/quickstart)

