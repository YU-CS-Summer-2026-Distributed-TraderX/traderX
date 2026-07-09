# ADR-028: Producer/Consumer Service Split + Fail-Safe Halt-and-Alert

**Status:** Accepted, implemented (slice 1)
**Date:** 2026-07-08
**State:** `YU06-eod-price-production` (parent `YU05-post-trade-compliance`)

## Context

Two placement questions and one safety question:

1. Where does the EOD price *producer* live?
2. Where does the first *consumer* (EOD position marks / P&L) live?
3. What happens when a closing price is missing or quality-flagged?

## Decision

**Producer → trade-processor.** It already owns everything the producer needs: the `pricing.*`
price feed (`PriceHistoryStore` = last-trade source), MariaDB, real JWT auth, and a scheduler +
controller surface. A separate `eod-price-service` microservice would duplicate all of it for zero
gain.

**Consumer → position-service.** EOD marks/P&L need positions × closing prices; positions live in
position-service. It gets a genuinely new durable JetStream subscriber — a *separate process*
gated by the durable event, which is exactly the pattern being demonstrated. Putting the consumer
in trade-processor (subscribing to its own event in-process) would be a weaker demo and would still
need position data it doesn't own.

**Fail-safe halt-and-alert at both ends** (deck 07 s43–45 — never proceed on a stale/missing
price):

- *Producer:* `publish` refuses (409) if any instrument is unresolved `STALE`/`SPIKE`/`MISSING`; no
  event is emitted. An all-clean close auto-publishes.
- *Consumer:* a held security missing/flagged in the snapshot halts *that account's* marking,
  increments `eod_pnl_halted_total`, logs an alert, and writes no P&L rows for that account. Other
  accounts continue — one bad instrument doesn't fail the whole batch, but no account is ever
  silently mispriced.

## Alternatives Considered

- **New standalone eod-price microservice** — rejected (duplicates trade-processor infra; ponytail).
- **Consumer in trade-processor** — rejected (in-process self-subscription is a weak demonstration
  of an event *gate*, and trade-processor doesn't own positions).
- **Mark missing/flagged instruments at last-known/zero** — rejected outright: silently proceeding
  on a bad price is the exact failure the deck's fail-safe exists to prevent.

## Consequences

Positive: minimal new infra (producer is a few classes in an existing service); the consumer is a
real cross-process, durable-event-gated job — the honest version of the pattern. Fail-safe means a
data-quality problem surfaces as a halted, alerted account, never as a wrong number downstream.

Costs: position-service gains its first messaging dependency (NATS client + `PubSubConfig`) — the
one genuinely new piece of infrastructure, and it *is* the feature, so it's warranted.
