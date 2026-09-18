# Architecture and ownership — proposed

The intended dependency direction is:

```text
Producer -> producer adapter -> canonical engine request/service -> engine models
                    |                    |
              identity/validation   canonical result
                    |                    |
                    +---- result envelope / attempt store ----> consumer
```

| Responsibility | Owner |
|---|---|
| Export positions, contracts, reference terms and input provenance | Producer |
| Validate producer format, join identities, map units and construct canonical requests | Producer adapter |
| Instrument representation, schedules, model conventions, curves, pricing and Greeks | Engine |
| Request validation, attempt ownership, execution dispatch, persistence and lookup | Engine integration service |
| Validate returned identity/schema/coverage and preserve received bytes | Consumer |
| Independent reference calculations used only for acceptance | Test code |

An adapter may convert a documented percent into a decimal or map an enum. It must not invent missing financial terms or maintain an alternative schedule/pricer. Independent test formulas are intentional and must never become a runtime fallback.

## Required reuse audit before refactoring

Alex fills in a concrete map before implementation:

| Operation | Current integration symbol | Canonical engine symbol | Differences in conventions/units | Decision and proof |
|---|---|---|---|---|
| Bill valuation | Audit required | Audit required | Audit required | Reuse/consolidate with evidence |
| Note schedule and valuation | Audit required | Audit required | Audit required | Reuse/consolidate with evidence |
| Accrued reconciliation | Audit required | Audit required | Audit required | Separate exported accrual from model accrual |
| Rate sensitivity | Audit required | Audit required | Audit required | Preserve bump size, units and sign |
| Market resolution | Audit required | Audit required | Audit required | One provenance-preserving path |

Observed entry points worth tracing at e4ca50b: `engine/integration/pipeline.py`, `engine/instruments/treasury.py`, `engine/portfolio/request.py` and `engine/api/eod_routes.py`. Their existence does not establish which code is duplicated or which implementation is authoritative. Do not delete either path until the map and discriminating tests establish equivalence or explain differences.

If the canonical engine cannot express the agreed economics, add or correct that capability there. Record any intentional multiple models with explicit selection and separate capability identities; do not hide a second model behind an adapter.
