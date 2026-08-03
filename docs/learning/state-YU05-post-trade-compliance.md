---
title: "State YU05-post-trade-compliance: Post-Trade Compliance Bundle"
---

# State YU05-post-trade-compliance Learning Guide

## Position In Learning Graph

- Previous state(s): [YU04-durable-control-feeds](/docs/learning/state-YU04-durable-control-feeds)
- Dotted-line parent(s): none
- Next state(s): [YU06-eod-price-production](/docs/learning/state-YU06-eod-price-production)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [code/generated-state-YU05-post-trade-compliance](https://github.com/finos/traderX/tree/code/generated-state-YU05-post-trade-compliance)
- Authoring branch (spec source): [main](https://github.com/finos/traderX/tree/main)

## Code Comparison With Previous State

- Compare against `YU04-durable-control-feeds`: [code/generated-state-YU04-durable-control-feeds...code/generated-state-YU05-post-trade-compliance](https://github.com/finos/traderX/compare/code%2Fgenerated-state-YU04-durable-control-feeds...code%2Fgenerated-state-YU05-post-trade-compliance)

## Plain-English Code Delta

- **Added:** Deterministic trade identity: every MariaDB trade row carries the id derived from the journal fill
- **Added:** A replay-safe in-memory trade blotter in order-matcher, rebuilt from journal replay on recovery,
- **Added:** Reconciliation that classifies each trade as `MATCHED`, `MISSING_IN_PROJECTION` or
- **Added:** Reconciliation reporting through a `GET /recon/status` summary and Prometheus counters labelled
- **Added:** An on-demand full-history sweep (`POST /recon/full-history/reindex`, then `POST
- **Added:** Settlement and reconciliation writes that land in MariaDB only, never mutating journal or BLP
- **Added:** The full-history reindex and the regulatory export as read-only shadow replays that never touch the
- **Added:** A journal-sourced regulatory audit export (`GET /regulatory/report?fromSeq=&toSeq=`) covering every

## Run This State

```bash
inherits YU04-durable-control-feeds runtime harness
```

## Canonical Spec Links

- State spec pack: [/specs/YU05-post-trade-compliance](/specs/YU05-post-trade-compliance)
- Architecture: [/specs/YU05-post-trade-compliance/system/architecture](/specs/YU05-post-trade-compliance/system/architecture)
- Flows / topology: [/specs/YU05-post-trade-compliance/system/runtime-topology](/specs/YU05-post-trade-compliance/system/runtime-topology)
- Research: [link](/specs/YU05-post-trade-compliance/research)
- Data model: [link](/specs/YU05-post-trade-compliance/data-model)
- Quickstart: [link](/specs/YU05-post-trade-compliance/quickstart)

