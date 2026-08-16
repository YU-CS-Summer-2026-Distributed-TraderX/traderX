# Messaging Subject Map: YU16-cdm-instruments

Every inherited NATS subject is carried forward with its existing contract. YU16 adds no
subject, removes none, and renames none (NFR-CDM06) — the observable differences are content:
ETF and Treasury instrument keys appear on the inherited subjects as ordinary securities,
Treasury `pricing.*` payloads carry additive bond fields, and the `/trades` family may carry a
`Rejected` trade. The durable control feed keeps its `SECURITY` names while the REST routes
gained a general `/instruments` name — recorded as TD-CDM02, deliberately not fixed here.

The Aeron Cluster channels and the gateway FIX endpoint are inherited from YU12 unchanged.

## Subject Families

- `/trades`
  - producer: `trade-service` (legacy REST path); **YU12 adds the Aeron Cluster leader's trade-egress
    bridge (`TradeNatsPublisher`) as the primary producer** — every booked trade (`KIND_TRADE_BOOKED`
    on the deterministic apply stream) is published here so the cluster's fills reach `trade-processor`
    → the SQL DB → the `/accounts/*/trades` + `/positions` UI feeds. Not the gateway egress (best-effort,
    submitting-session-only). Leader-only (no follower dupes); at-least-once, keyed by `tradeSeq`+side so
    `trade-processor` (Trade JPA id) dedups. Enabled by `TRADE_BRIDGE_NATS_URL`. See ADR-048.
  - consumer: `trade-processor`
  - delivery: `point-to-point`
  - wildcard: `no`
  - scope: `global`
  - payload: `NatsEnvelope<TradeOrder>` (`type` must equal `TradeOrder`); `payload` = `{id, state,
    security, quantity, price, accountId, side}` with stamped execution price
  - note (YU16): Treasury rows carry face in `quantity` and fraction-of-par in `price`.

- `/accounts/<accountId>/trades`
  - producer: `trade-processor`
  - consumer: frontend trade blotter stream, `tick-store` capture
  - delivery: `broadcast`
  - wildcard: `no` (consumed with wildcard `/accounts/*/trades`)
  - scope: `per-account`
  - payload: processed trade (includes `price`)
  - note (YU16): may carry `state: Rejected` with `rejectionReason`/`sourceOrderId`; a rejected
    trade is never followed by a position message.

- `/accounts/<accountId>/positions`
  - producer: `trade-processor`
  - consumer: frontend position blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `per-account`
  - payload: position snapshot (includes `averageCostBasis`)
  - note (YU16): Treasury rows carry face in `quantity` and a fraction-of-par average at 6 dp.

- `pricing.<TICKER>`
  - producer: `price-publisher`
  - consumer: frontend valuation streams, `trade-processor`'s EOD closing-price source,
    `tick-store` capture, YU12 feed adapter (sequences conflated ticks as cluster ingress)
  - delivery: `broadcast`
  - wildcard: `yes` (`pricing.*`)
  - scope: `per-ticker`
  - payload: market tick (`price`, `openPrice`, `closePrice`, `asOf`, `source`)
  - note (YU16): see "YU16 change" below for Treasury payload extension.

- `/accounts/<accountId>/orders`
  - producer: `order-matcher`
  - consumer: frontend account order blotter stream, `execution-algo-engine` fill tracking
    (subscribes to NATS's catch-all `>` and filters client-side by subject prefix/suffix, since
    the literal `/`-separated subject has no `.`-token position for a real NATS wildcard)
  - delivery: `broadcast`
  - wildcard: `no` (no NATS-native wildcard is possible on this subject shape at all)
  - scope: `per-account`
  - payload: `NatsEnvelope` wrapping an order lifecycle event (`orderId`, `status`,
    `remainingQuantity`, `limitPrice`, `lastExecutionPrice`) under `payload`

- `/orders`
  - producer: `order-matcher`
  - consumer: frontend admin order blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: order lifecycle event (`orderId`, `accountId`, `status`, `remainingQuantity`, `limitPrice`)

- `eod.prices.ready` (JetStream, stream `TRADERX_EOD`)
  - producer: `trade-processor`
  - consumer: `position-service` (durable consumer `eod-pnl`)
  - delivery: `durable point-to-point` (JetStream file storage; redelivered until acked)
  - wildcard: `no`
  - scope: `global`
  - payload: gate event (`sessionDate`, `version`, `instrumentCount`, `publishedAtMillis`)

- `eod.pnl.done` (JetStream, stream `TRADERX_EOD`)
  - producer: `position-service`
  - consumer: `risk-extract` (durable consumer `risk-extract`, inherited from YU15)
  - delivery: `durable point-to-point` (JetStream file storage; redelivered until acked)
  - wildcard: `no`
  - scope: `global`
  - payload: completion event (`sessionDate`, `version`, `accountsMarked`, `accountsHalted`, `completedAtMillis`)

- `algo.events.>` (JetStream, stream `TRADERX_ALGO_ENGINE`)
  - producer: `execution-algo-engine`
  - consumer: `execution-algo-engine` (durable consumer `algo-engine-state`, itself — no other
    subscriber in this state)
  - delivery: `durable point-to-point` (JetStream file storage; explicit ack after applying to
    in-memory state, so a crash between append and ack redelivers)
  - wildcard: `yes` (subject per event carries `algo.events.<parentOrderId>`)
  - scope: `global`
  - payload: parent-order lifecycle event (`type`, `parentOrderId`, plus type-specific fields)

- `traderx.control.security.deltas` (JetStream, stream `TRADERX_CONTROL_SECURITY`)
  - producer: `reference-data` (outbox publisher, inherited from YU04)
  - consumer: `order-matcher` risk replica bootstrap (`ControlFeedSubscriber`)
  - delivery: `durable broadcast` (JetStream file storage)
  - wildcard: `no`
  - scope: `global`
  - payload: security delta (`ticker`, `companyName`)
  - note (YU16): content only — the ten new instrument keys flow as ordinary rows; the snapshot
    bootstrap URL defaults to `/instruments/control-snapshot` at this state's layer. Stream and
    subject names unchanged (TD-CDM02).

- `risk.extract.cut`
  - producer: `order-matcher-cluster` **leader only**
  - consumer: `risk-extract`
  - delivery: `broadcast` (core NATS; one message per extract)
  - wildcard: `no`
  - scope: `global`
  - payload: the canonical position cut as US-ASCII text — a `#cut` header carrying
    `schema`, `seq`, `sessionDateEpochDay`, `priceVersion`, `rows`, then one row per
    `(accountId, security)` with quantity, average cost ticks, contract multiplier, and last
    trade price ticks. Unchanged in YU16 (`#cut schema=1`) — the cut is engine state.

- `risk.extract.ready`
  - producer: `risk-extract`
  - consumer: the external pricing/risk engine
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: delivery record — `schema` (**2 from this state**), `uri`, `consensusSequence`,
    `sessionDate`, `priceSnapshotVersion`, `rows`, `sha256`, `cutSha256`,
    `quiesceWitnessSequence`

## FIX session endpoint (non-NATS)

| Endpoint | Transport | Producer -> Consumer | Payload | Scope |
|---|---|---|---|---|
| gateway FIX port | FIX 4.4 / TCP (point-to-point session) | FIX initiator <-> `fix-gateway` acceptor | FIX 4.4 messages: A/0/1/2/3/4/5 session-level; D/F/H in; 8/9 out | cluster-internal |

The acceptor terminates on the gateway tier; the session survives BLP leader changes.

## Aeron Cluster channels (non-NATS, inherited from YU12/YU15)

| Endpoint | Transport | Producer -> Consumer | Payload | Scope |
|---|---|---|---|---|
| cluster ingress UDP | Aeron Cluster client protocol | gateway tier / feed adapter / `risk-extract` -> leader consensus module | SBE `InputEventMessage` ingress commands; SBE `RiskExtractMessage` (template 8) | cluster |
| cluster egress UDP | Aeron Cluster client protocol | leader -> gateway tier / feed adapter / `risk-extract` | committed admission responses, session events, marker acks | cluster |
| consensus/log UDP | Aeron Cluster consensus protocol | leader <-> followers | log replication, votes, heartbeats, commit positions | member pods |
| catch-up/snapshot UDP | Aeron Archive + cluster catch-up | serving member -> rejoining member | snapshot retrieval and committed-log replay | member pods |

## YU16 change: `pricing.<instrumentKey>` carries Treasury payload extensions

The subject, producer, consumers and every inherited field are unchanged; Treasury payloads add
`assetClass`, `cleanPrice` (fraction of par, equal to `price`),
`priceSemantics: "CLEAN_FRACTION_OF_PAR"`, `ytmPercent`, `quoteTimestamp` (= `asOf`),
`maturityDate`, `matured`, `simulated`, `officialSeedCleanPrice`. The binary companion
`pricing-tick-bin.<instrumentKey>` carries `round(fraction × 1e6)` for a Treasury — six-decimal
precision; the inherited 3-dp rounding remains the equity/option contract. A matured Treasury's
payloads are suppressed. `UST-*` keys contain no `.`, so each remains one NATS token.
