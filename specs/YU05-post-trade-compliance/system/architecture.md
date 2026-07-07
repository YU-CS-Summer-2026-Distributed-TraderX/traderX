# Architecture: YU05 Post-Trade Compliance Bundle

Parent: `YU03-in-memory-risk-gateway`. This state adds a back-office layer downstream of the BLP —
it never sits on the order admission path and never mutates journal/BLP state (FR-PTC07/NFR-PTC01).

## Slice-1 data flow

```
                          BLP (order-matcher, unchanged matching/risk pipeline)
                                          │  KIND_TRADE_BOOKED (output ring)
                                          ▼
                 ┌────────────────────────────────────────────────────────┐
                 │  output-ring handler chain (existing + 1 new handler)   │
                 │  marshaller | natsBridge | accountTrade | positionUpdate│
                 │  | projector | ── NEW: TradeBlotterHandler ──           │
                 │  (all but the new handler suppress side-effects on     │
                 │   readModel.isReplaying(); the new one does NOT —      │
                 │   it must rebuild from replay)                         │
                 └───────────────┬──────────────────────┬─────────────────┘
                                  │ NATS TradeOrder        │ in-memory
                                  │ (id = trd-09b-<seq>,   │ TradeBlotter
                                  │  now deterministic)     │ (bounded, replay-rebuilt)
                                  ▼                        │
                   trade-processor: TradeService            │
                   idempotent booking by id                 │
                   ──► TRADES.ID = trd-09b-<seq>             │
                   ──► SettlementService (T+N sweep)          │
                                  │                            │
                                  ▼                            │
                   MariaDB TRADES (read-model projection,      │
                   never authoritative)                        │
                                  ▲                              │
                                  │ GET /recon/trades/blotter (auth'd)
                   ReconciliationService  ◄──────────────────────┘
                   (trade-processor, scheduled sweep)
                   ──► classification: MATCHED / MISSING_IN_PROJECTION / FIELD_MISMATCH
                   ──► GET /recon/status, Prometheus counters
```

## Components

| Component | Location | Role |
|---|---|---|
| `OrderSnapshot.tradeIdFor` (existing, now actually used) | order-matcher | Deterministic trade id from the BLP's global trade counter. |
| `TradeOrder.fromEvent` (fixed) | order-matcher | NATS trade-feed payload builder; now sets `id` from `tradeIdFor`, not `orderIdFor`. |
| `TradeBlotter` / `TradeBlotterHandler` (new) | order-matcher, `lmax/` | Bounded, replay-rebuilt in-memory record of every booked trade. |
| `ReconController` (new) | order-matcher, `controller/` | `GET /recon/trades/blotter` — authenticated forward-paginated read. |
| `TradeService` (extended) | trade-processor | Idempotent booking by deterministic id; sets initial settlement date. |
| `SettlementService` (new) | trade-processor | T+N sweep + manual force override. |
| `ReconciliationService` (new) | trade-processor | Scheduled sweep against the order-matcher blotter; classification + metrics. |
| `ReconStatusController` (new) | trade-processor | `GET /recon/status`. |

## Determinism / correctness boundary (unchanged invariant, extended)

The BLP's decision path is untouched by this state — `TradeBlotterHandler` only reads
`OutputEvent` fields already computed deterministically by the BLP and writes to its own
in-process structure on the existing output-ring consumer thread (single-writer for the blotter,
same threading model every other output handler already uses). MariaDB remains a downstream
projection; settlement and reconciliation are computed entirely on the projection side and never
feed back into journal/BLP state (FR-PTC07).

## Deferred capabilities (specified only, see requirements/functional-delta.md)

Regulatory reporting, TCA, and real auth/entitlements are not yet built; their architecture is
sketched in ADR-023/024/025 but no code exists for them in slice 1.
