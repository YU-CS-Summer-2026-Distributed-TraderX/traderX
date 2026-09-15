# YU18-eod-risk-bundles architecture

Local EOD bundles, durable single-host coordination, strict mock ingestion and recovery.

- Inherits architectural baseline from: `YU17-otc-rates`
- Generated from: `system/architecture.model.json`
- Canonical flows: `architecture.md`

## Architecture Diagram

```mermaid
flowchart LR
  exports["YU17 position and OTC exports"]
  builder["Local bundle builder"]
  bundle["Private immutable bundle"]
  validator["Bundle validator"]
  mock["Transport mock"]
  result["Private mock result: no prices"]
  coordinator["Local coordinator"]
  jobs["Private SQLite jobs and attempts"]
  status["Local status: no financial results"]
  exports -->|"same-cut files"| builder
  builder -->|"manifest and original bytes"| bundle
  bundle -->|"validate hashes and identities"| validator
  mock -->|"echo identities; priced count zero"| result
  validator -->|"validated private input snapshot"| coordinator
  coordinator -->|"persist job and attempt"| jobs
  coordinator -->|"execute local adapter"| mock
  result -->|"validate identities and non-pricing semantics"| coordinator
  jobs -->|"history and current cut"| status
```

## Node Catalog

| Node | Kind | Label | Notes |
| --- | --- | --- | --- |
| `exports` | store | YU17 position and OTC exports | YU17 position and OTC exports |
| `builder` | service | Local bundle builder | Local bundle builder |
| `bundle` | store | Private immutable bundle | Private immutable bundle |
| `validator` | service | Bundle validator | Bundle validator |
| `mock` | service | Transport mock | Transport mock |
| `result` | store | Private mock result: no prices | Private mock result: no prices |
| `coordinator` | service | Local coordinator | Discovery, serialized execution and restart recovery |
| `jobs` | store | Private SQLite jobs and attempts | Durable logical workloads, attempts and accepted hashes |
| `status` | service | Local status: no financial results | Version-scoped selection, history and integrity |

