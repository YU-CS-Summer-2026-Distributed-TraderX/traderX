# ADR-048: A leader-side trade-egress bridge republishes booked trades to NATS

Status: Accepted

## Context

ADR-045 makes the consensus log the *only input* to the cluster: the deterministic engine matches
orders and books trades entirely inside the replicated state machine, and the gateway receives
egress acks over its AeronCluster session. That leaves the rest of TraderX — the SQL database,
`trade-processor`, `position-service`, and the Angular UI blotters — with no view of the cluster's
trades. The counterparty is served (acks flow back through the gateway), but nothing persists trades
to the database or drives the `/accounts/*/trades` and `/positions` UI feeds. The pre-YU12 order
matcher republished fills to NATS `/trades`; the cluster replaced that matcher and stopped doing so.

The obvious tap — the gateway's egress — is wrong for durable persistence. AeronCluster egress is
**best-effort by design** (a slow client gets drops, never the state machine's time — see the
egress bound in the service) and is delivered **only to the submitting session**, so it would miss
the resting side of every match and lose trades under load. The database must not see a lossy,
one-sided trade stream.

## Decision

A **leader-side trade-egress bridge** (`TradeNatsPublisher`) publishes every booked trade to NATS
`/trades`, the subject `trade-processor` already consumes. It taps the **deterministic apply
stream**, not egress: as the service drains its output ring, each `KIND_TRADE_BOOKED` event (one per
account/side, with account, security, side, qty, price, `tradeSeq`) is offered — **non-blocking, on
the leader only** — to a lock-free SPSC queue; a daemon thread serialises it as a
`NatsEnvelope<TradeOrder>` (envelope `type` must equal `TradeOrder` or the consumer ignores it) and
publishes it. The apply thread never blocks on NATS, preserving the determinism/latency the engine
was hardened for. Only the leader publishes, so followers never duplicate. The bridge is gated by
`TRADE_BRIDGE_NATS_URL`; default-off leaves behaviour unchanged.

From `/trades`, `trade-processor` persists Trade + Position to the SQL database and republishes
`/accounts/{id}/trades` + `/accounts/{id}/positions`, which the UI is subscribed to over its NATS
websocket — so the blotter and positions update live. GCS is never in this path (it is the DR
snapshot backup, minutes stale, and not queryable trade records).

## Consequences

Cluster trades now reach the database, positions, and the live UI. Delivery is **at-least-once**:
`id = tradeSeq + side`, and `trade-processor` keys Trade by id, so a replay dedups — effectively
exactly-once in the DB. The one gap: on an *ungraceful leader crash*, trades queued but not yet
flushed to NATS are missed; closing it fully needs a published-offset checkpoint (follow-up, e.g.
recorded in the snapshot or reported back by `trade-processor`).

The bridge publishes at cluster speed; `trade-processor` is a Spring/JPA service sized for human
trading rates, so a synthetic ~100k+ trades/s flood overwhelms it (its settlement sweep loads all
unsettled trades and OOMs). Real trading rates (hundreds/s) are unaffected. The consensus log
remains the source of truth (ADR-045); the bridge is a downstream projection, not a second writer.

Proven live: cluster-booked trades produced a persisted position (`GET /positions/42422` via
`position-service`) sourced from the cluster → bridge → NATS → `trade-processor` → database chain.
