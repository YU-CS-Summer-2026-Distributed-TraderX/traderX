---
title: What's new
sidebar_label: What's new
description: What Yeshiva University's build adds to FINOS TraderX, state by state — an LMAX matching engine on Raft consensus, pre-trade risk, a crossing order book, listed options and an end-of-day risk extract.
---

# What's new

FINOS TraderX is a reference trading platform: correct, readable and deliberately simple. This
build asks what it would take to make it behave like a **sell-side order management system** — and
answers in fifteen runnable states, each one a working system rather than a branch of half-finished
work.

The matching engine moved in-memory and single-threaded, then onto Raft consensus across three
members. Risk moved in front of the book. Settlement, reconciliation, regulatory export, FIX
ingress, listed options and an end-of-day risk extract were added on top. Every state below still
generates, deploys and runs — and each links to the spec pack that defines it.

## The fifteen states

### YU01 — LMAX Sequencer Architecture

Replaces the request/response matcher with the LMAX pattern: a single-threaded, in-memory sequencer
fed by a Disruptor ring buffer and journaled to disk. Order handling becomes deterministic and
replayable, and the database stops being the source of truth.

[Spec pack →](/specs/YU01-lmax-sequencer)

### YU02 — LMAX on Kubernetes

Runs that engine as the deployed order-matcher, recovering from journal plus periodic snapshots and
held un-ready until replay finishes. Adds the approval-gated build and deploy path: a push builds an
image, but nothing reaches the cluster without a human saying so.

[Spec pack →](/specs/YU02-lmax-kubernetes)

### YU03 — In-Memory Risk Gateway

Pre-trade risk on the hot path (SEC 15c3-5): credit, order size, notional and price-collar checks
decided in memory, with no database round trip per order. A rejected order never reaches the book.

[Spec pack →](/specs/YU03-in-memory-risk-gateway)

### YU04 — Durable Control Feeds

Makes risk control changes durable. A limit, restriction or new security is written to a
transactional outbox in the same commit as the row it describes, then published in strict version
order — so a change survives the consumer being offline instead of being lost.

[Spec pack →](/specs/YU04-durable-control-feeds)

### YU05 — Post-Trade Compliance

The post-trade bundle: a real settlement lifecycle, reconciliation of the journal against the SQL
projection, a reproducible regulatory export, transaction-cost analysis, and real JWT auth where
account scope is checked against the trade's own account rather than assumed.

[Spec pack →](/specs/YU05-post-trade-compliance)

### YU06 — EOD Price Production

End-of-day closing prices as a versioned, immutable snapshot, with a quality gate that blocks
publication on stale, spiking or missing marks until a human overrides it — and a durable overnight
chain that computes end-of-day P&L from the published cut.

[Spec pack →](/specs/YU06-eod-price-production)

### YU07 — Historical Tick Store

A columnar store of real historical market data, verified by cross-implementation gates whose
expected values were computed independently in a second engine — so the store is checked against
something other than itself.

[Spec pack →](/specs/YU07-historical-tick-store)

### YU08 — Execution Algo Engine

Large orders become TWAP parents sliced into child orders on a schedule, submitted through the same
risk-gated ingress as any other order rather than around it.

[Spec pack →](/specs/YU08-execution-algo-engine)

### YU09 — Ops Hardening

The unglamorous pass: credentials from Kubernetes secrets rather than literals, probe and durability
fixes, memory limits that stop a warm-up OOM, and continuous delivery for the remaining services.

[Spec pack →](/specs/YU09-ops-hardening)

### YU10 — FIX Order-Entry Ingress

A standard FIX 4.4 session for external counterparties — new orders and cancels arriving over the
protocol the industry actually uses, mapped onto the same sequenced path as REST.

[Spec pack →](/specs/YU10-fix-ingress)

### YU11 — Aeron + SBE Replication

Replaces warm-standby replication with Aeron transport and SBE encoding, lifting replication
capacity by a large multiple and proving recovery across an epoch boundary.

[Spec pack →](/specs/YU11-aeron-replication)

### YU12 — Aeron Cluster Consensus

High availability becomes Raft consensus across three members, decided by the cluster itself with
Kubernetes out of the decision path. Leader failover under 200 ms, and no order ID is ever reused
across one.

[Spec pack →](/specs/YU12-aeron-cluster)

### YU13 — Crossing Limit-Order Book

A genuine crossing book: price-time priority, marketable orders filled at the resting price, market
orders that cannot rest at an undefined price, self-trade prevention, atomic replace, idempotent
client order IDs — and the whole resting book carried in the cluster snapshot.

[Spec pack →](/specs/YU13-limit-order-book)

### YU14 — Listed Equity Options

Listed equity options trade as ordinary securities on the unchanged book, with no second matching
path. The risk math becomes contract-multiplier aware, so a $2.50 option controlling 100 shares
consumes $250 of credit rather than $2.50.

[Spec pack →](/specs/YU14-listed-equity-options)

### YU15 — EOD Risk Extract

A portfolio extract for an external risk engine, with every account frozen at the same consensus
instant — a portfolio assembled from accounts sampled at different moments is one the firm never
held, and its risk number means nothing. Rows are un-netted with the counterparty attached, and the
bytes are identical every time for a given identifier.

[Spec pack →](/specs/YU15-eod-risk-extract)

## See how it is verified

Every claim above is checked by something that can fail. The engine, cluster, gateway, risk and
post-trade logic run as machine-verified tests on every push; container-backed suites check the
properties that only a real database or broker can disagree about; and end-to-end proof scripts
drive the deployed system and print an explicit pass or fail per step.

- **[Testing strategy](testing-strategy.md)** — which tier proves what, and what stays manual and why.
- **[Test coverage](test-coverage.md)** — what is tested, how much runs automatically, and how every number was counted.
