# Architecture: YU04 Durable Control Feeds

Parent: `YU03-in-memory-risk-gateway`. This state replaces how the Gateway replica's
account/security universe is populated and kept current; it does not touch the two-tier
Gateway+BLP admission pipeline itself (ADR-018), the journal/replication wire format, or snapshot
format (all unchanged from YU03).

## End-to-end flow (new)

```
account-service                                   reference-data
┌─────────────────────────┐                       ┌─────────────────────────┐
│ POST/PUT /account/       │                       │ POST /stocks             │
│  (JdbcTemplate, same tx) │                       │  (mysql2, same tx)       │
│   accounts  +            │                       │   stocks  +              │
│   account_control_outbox │                       │   stocks_control_outbox  │
└──────────┬───────────────┘                       └──────────┬───────────────┘
           │ poll unpublished rows, version order              │ poll unpublished rows, version order
           ▼                                                    ▼
   outbox publisher (@Scheduled)                        outbox publisher (@Interval)
           │ publish, Nats-Msg-Id="account:<v>"                 │ publish, Nats-Msg-Id="security:<v>"
           ▼                                                    ▼
  JetStream: TRADERX_CONTROL_ACCOUNT                   JetStream: TRADERX_CONTROL_SECURITY
  subject traderx.control.account.deltas               subject traderx.control.security.deltas
           │                                                    │
           └───────────────────┬────────────────────────────────┘
                                ▼
                 order-matcher: ControlFeedSubscriber (x2, one per source)
                 ADR-019 5-step protocol per source:
                 1. ephemeral pull consumer (DeliverPolicy.New) + buffer
                 2. GET .../control-snapshot (epoch E, watermark W, checksum)
                 3. verify checksum/count/schema, atomically install
                 4. apply buffered deltas > W, same epoch, in order
                 5. continue live consumption; per-source ready at high watermark
                 gap / regression / epoch change → quarantine + re-run from step 1
                                │
                                ▼
                 GatewayReplicaStore.applyAccount/applySecurity(..., sourceVersion)
                 (existing 2/3-arg overloads still used by /risk/control/* admin API)
                                │
                                ▼
                 GatewayReplicaStore.markReady() only once BOTH sources ready (FR-IMRG05)
```

The BLP decision path (`BlpRiskState.decideAndReserve`/`decideMarketTrade`, ADR-018) and the
journaled control-event path (`/risk/control/*` → `TYPE_{ACCOUNT,SECURITY}_CONTROL`, ADR-020) are
entirely unaffected — this diagram only replaces what feeds `GatewayReplicaStore`'s
existence/identity records at the edge.

## Components (new/changed)

| Component | Location | Role |
|---|---|---|
| `account_control_outbox` + poller | `account-service` | Transactional outbox + background publisher for account existence/identity changes (ADR-021). |
| `stocks` table + `stocks_control_outbox` + poller | `reference-data` | New persistence (previously CSV-only) + transactional outbox + publisher for security existence/identity changes. |
| `ControlFeedSubscriber` | `order-matcher`, new class in `risk` package, one instance per source | Implements the ADR-019 5-step protocol against one JetStream stream + one snapshot endpoint; owns per-source epoch/watermark/quarantine state. |
| `ReplicaBootstrap` | `order-matcher`, `risk` package (rewritten) | Now orchestrates two `ControlFeedSubscriber`s instead of two one-shot REST fetches; unchanged responsibility of gating readiness and feeding `GatewayReplicaStore`. |
| `GatewayReplicaStore` | `order-matcher`, `risk` package (extended) | `AccountRecord`/`SecurityRecord` gain `sourceVersion`; new `applyAccount`/`applySecurity` overloads. Screening logic (`screen()`) is untouched. |

## Determinism boundary (unchanged from YU03)

The BLP decision path still makes no external call, reads no clock/randomness, and its only
side-effect channel is the inherited output ring (NFR-IMRG04/ADR-020). Everything this state adds —
outbox tables, pollers, JetStream streams, `ControlFeedSubscriber` — lives entirely off the BLP
thread, in the same place YU03's `ReplicaBootstrap` already lived (background daemon threads /
edge-request threads), so replay determinism (NFR-IMRG03) is unaffected by construction.

## Why two independent streams, not one shared control-feed stream

`account-service` and `reference-data` are separate services with separate deploy lifecycles and
separate failure domains; a schema change or resync (epoch bump) on one must not force
re-bootstrapping the other. Separate streams also keep each source's retention/replay policy
independently tunable (see `data-model.md`).
