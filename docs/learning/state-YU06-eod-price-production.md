---
title: "State YU06-eod-price-production: EOD Price Production + Overnight Batch Chain"
---

# State YU06-eod-price-production Learning Guide

## Position In Learning Graph

- Previous state(s): [YU05-post-trade-compliance](/docs/learning/state-YU05-post-trade-compliance)
- Dotted-line parent(s): none
- Next state(s): [YU07-historical-tick-store](/docs/learning/state-YU07-historical-tick-store)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [code/generated-state-YU06-eod-price-production](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU06-eod-price-production)
- Authoring branch (spec source): [YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU15-eod-risk-extract)

## Code Comparison With Previous State

- Compare against `YU05-post-trade-compliance`: [code/generated-state-YU05-post-trade-compliance...code/generated-state-YU06-eod-price-production](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU05-post-trade-compliance...code%2Fgenerated-state-YU06-eod-price-production)

## Plain-English Code Delta

- **Added:** Session-close trigger `POST /eod/session/close?sessionDate=` in `trade-processor`, called both by a
- **Added:** Official closing price per instrument, defined as the newest last-trade sample at or before the
- **Added:** Versioned, immutable closing-price snapshot (`eod_price_session`, `eod_price_snapshot`) keyed by
- **Added:** Data-quality classification of every price as `OK`, `STALE`, `SPIKE` or `MISSING`, from configured
- **Added:** Manual override endpoint `POST /eod/prices/{date}/override` that records a corrected price and its
- **Added:** Admin-only EOD control surface: every `/eod/*` endpoint requires an authenticated `admin` caller,
- **Added:** Publication fail-safe: `POST /eod/prices/{date}/publish` returns `409` while any instrument is an
- **Added:** Durable `EOD_PRICES_READY` event on JetStream subject `eod.prices.ready`, emitted only after the

## Run This State

```bash
./scripts/start-state-YU06-eod-price-production-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU06-eod-price-production](/specs/YU06-eod-price-production)
- Architecture: [/specs/YU06-eod-price-production/system/architecture](/specs/YU06-eod-price-production/system/architecture)
- Flows / topology: [/specs/YU06-eod-price-production/system/runtime-topology](/specs/YU06-eod-price-production/system/runtime-topology)
- Research: [link](/specs/YU06-eod-price-production/research)
- Data model: [link](/specs/YU06-eod-price-production/data-model)
- Quickstart: [link](/specs/YU06-eod-price-production/quickstart)

