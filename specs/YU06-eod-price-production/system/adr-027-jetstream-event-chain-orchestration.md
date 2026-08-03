# ADR-027: Lightweight JetStream Event-Chain Orchestration (Not a Workflow Engine)

**Status:** Accepted, implemented
**Date:** 2026-07-08
**State:** `YU06-eod-price-production` (parent `YU05-post-trade-compliance`)

## Context

The overnight workflow is a dependency chain: session close → produce/quality-check/publish closing
prices → downstream jobs (position marks/P&L, then further overnight jobs). Something must sequence
the stages and let ops see where a delay is — a delay at any stage compresses all downstream
processing time.

The gate event must also be **durable**: an overnight batch that boots after the event fired must
still receive it.

## Decision

Orchestrate with a **lightweight NATS JetStream event chain**, reusing the durable-publish
infrastructure YU04 already stood up (`JetStreamControlFeedPublisher`). Each stage publishes a
durable completion event on the `TRADERX_EOD` stream that the next stage's durable consumer
subscribes to:

```
session-close ─▶ [produce · quality · publish] ─▶ eod.prices.ready ─▶ position marks/P&L ─▶ eod.pnl.done
```

Ops observes the chain via per-stage Micrometer metrics and event timestamps (chain latency =
pnl-done − session-close), surfaced in a Grafana panel. Durability comes from JetStream file storage
+ durable consumers, so restart/late-boot is covered by redelivery.

## Alternatives Considered

- **Airflow / Control-M** — a full workflow engine would dwarf the system it orchestrates (more
  infra than TraderX itself), for a chain currently two stages long. Rejected.
- **Plain k8s Jobs + an ordering controller** — viable, but needs a new controller and doesn't
  reuse the JetStream infra already present; the event chain is lazier and lets each job simply
  subscribe to the prior job's completion event. Rejected for v1.
- **Core NATS (non-durable) events** — rejected: a consumer down at publish time would miss the
  gate entirely, violating the durability requirement (NFR-EOD02).

## Consequences

Positive: no new orchestration infrastructure; durability and redelivery come for free from the
existing JetStream setup; the chain extends by adding a new subscriber to an existing subject
without touching existing stages.

Costs: there is no central DAG view — the "workflow" is implicit in who-subscribes-to-what. Accepted
at this scale; if the chain grows past a handful of stages, revisit with a lightweight DAG
descriptor before reaching for a full engine.
