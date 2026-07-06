# Architecture: YU03 In-Memory Risk Gateway

Parent: `YU02-lmax-kubernetes`. This state adds a pre-trade risk admission tier to the LMAX BLP
without changing the inherited ring topology, journal-before-BLP gate, matching policy, output
handlers, projector, or NATS subjects (FR-IMRG40/45).

## Two tiers (ADR-018)

```
                         order / market-trade (REST, optional clientOrderId)
                                        │
                                        ▼
                        ┌───────────────────────────────┐
                        │  Tier 1 — Gateway screening    │   in-process, memory-only,
                        │  GatewayReplicaStore.screen()  │   NO REST/DB (FR-IMRG01/06)
                        │  fail-closed until ready       │   reject → 422 / 503 (stale)
                        └───────────────┬───────────────┘
                                        │ preliminary PASS (never final — FR-IMRG07)
                                        ▼
                 input disruptor  ──►  journaler + replicator  ──►  BLP (single writer)
                 (global sequence)     (durable before BLP)          │
                                                                     ▼
                        ┌───────────────────────────────────────────────────────┐
                        │  Tier 2 — authoritative decision + reservation         │
                        │  BlpRiskState.decideAndReserve / decideMarketTrade     │
                        │  ordered pipeline, exact aggregate exposure,           │
                        │  check+reserve as ONE single-threaded op (FR-IMRG12/13)│
                        └───────────────┬───────────────────────────────────────┘
                       ACCEPTED ──► book / execute        REJECTED ──► journaled only,
                       reserve exposure                    no book, no market event (FR-IMRG23)
                                        │
                                        ▼
                 output disruptor ──► marshaller (read model + acks) | NATS bridges | projector
```

Control events (account/security/policy/restriction) enter the SAME input sequence as commands and
prices (ADR-020), so the BLP applies them deterministically and replay reproduces every decision.

## Components (all under order-matcher; `risk` package unless noted)

| Component | Role |
|---|---|
| `GatewayReplicaStore` | Tier-1 replica: seeded + control-fed account/security/restriction/kill-switch/limits/price-freshness state; `screen()` preliminary validation; fail-closed readiness. |
| `BlpRiskState` | Tier-2 authoritative state: ordered decision pipeline, per-account credit/executed exposure, per-(account,security) reserved qty, bounded idempotency with retention frontier, snapshot capture/restore. |
| `ReservationHolder` (+ `RestingOrder` impl) | Per-order live reservation, riding the pooled order entry so its lifetime matches order addressability. |
| `RiskReason` / `RiskMetrics` | Stable bounded reason codes; bounded-cardinality Prometheus metrics. |
| `MatchingEngine` (lmax) | Invokes Tier 2 before book entry, consumes on fill, releases on cancel, applies control events. |
| `InputEvent` / `OutputEvent` (lmax) | Type-discriminated payload slots for keys/control; `KIND_TRADE_ACCEPTED/REJECTED` + `FLAG_REJECT` correlation. |
| `SnapshotStore` (lmax) | Format v3: order rows + risk sections (policy, account, security, idempotency). |
| `ReplicaBootstrap` | Startup journal-sequenced fetch of the account/security universe (ADR-019 slice-1 stand-in). |
| `RiskControlController` | `/risk/control/*` versioned control admin (token + operator); sequences control events. |
| `OrderMatcherService` | Edge: screening, `clientOrderId` hashing, 422/503 rejection bodies, price feed, risk metrics. |
| `RiskExceptionHandler` / `RiskRejectedException` / `RiskRejectionBody` | Stable 4xx rejection surface. |

## Determinism boundary

The BLP decision path makes no external call and reads no clock or randomness — decision time is
event-carried, ids derive from the order reference, iteration is over preallocated arrays
(NFR-IMRG02/04). Its only side-effect channel remains the inherited output ring (FR-IMRG27). This
is what lets snapshot + journal replay reproduce byte-equivalent decisions (ADR-020, NFR-IMRG03).
