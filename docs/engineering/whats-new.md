---
title: What's new
sidebar_label: What's new
description: What this build adds to upstream FINOS TraderX across YU01–YU18, plus market data, trading interfaces and recovery.
---

# What's new

FINOS TraderX provides a reference trading application. This build adds a single-threaded matching engine, pre-trade risk controls and a replicated order book, then connects them to settlement, reconciliation, regulatory exports and external order entry.

The eighteen YU states below cover that progression, from the LMAX sequencer to a three-member Aeron cluster, historical market data, execution algorithms, listed options, bonds, OTC rates and EOD risk integration. Each state has a short summary and a link to its spec pack.

Further additions extend those states: tracing, kdb+/q capture and replay, market-reference prices, typed orders, managed recovery, the original Demo console and the new Trader Desk. They are listed after YU18 under [Other additions](#other-additions).

## The eighteen states

### YU01: LMAX Sequencer Architecture

Replaces the request/response matcher with the LMAX pattern: a single-threaded, in-memory sequencer
fed by a Disruptor ring buffer and journaled to disk. Order handling becomes deterministic and
replayable, and the database stops being the source of truth.

[Spec pack →](/specs/YU01-lmax-sequencer)

### YU02: LMAX on Kubernetes

Runs that engine as the deployed order-matcher, recovering from journal plus periodic snapshots and
held un-ready until replay finishes. Adds the approval-gated build and deploy path: a push builds an
image, but nothing reaches the cluster without a human saying so.

[Spec pack →](/specs/YU02-lmax-kubernetes)

### YU03: In-Memory Risk Gateway

Pre-trade controls on the hot path: credit, order size, notional and price-collar checks
decided in memory, with no database round trip per order. A rejected order never reaches the book.

[Spec pack →](/specs/YU03-in-memory-risk-gateway)

### YU04: Durable Control Feeds

Makes risk control changes durable. A limit, restriction or new security is written to a
transactional outbox in the same commit as the row it describes, then published in strict version
order; so a change survives the consumer being offline instead of being lost.

[Spec pack →](/specs/YU04-durable-control-feeds)

### YU05: Post-Trade Compliance

The post-trade bundle: a real settlement lifecycle, reconciliation of the journal against the SQL
projection, a reproducible regulatory export, transaction-cost analysis, and real JWT auth where
account scope is checked against the trade's own account rather than assumed.

[Spec pack →](/specs/YU05-post-trade-compliance)

### YU06: EOD Price Production

End-of-day closing prices as a versioned, immutable snapshot, with a quality gate that blocks
publication on stale, spiking or missing marks until a human overrides it; and a durable overnight
chain that computes end-of-day P&L from the published cut.

[Spec pack →](/specs/YU06-eod-price-production)

### YU07: Historical Tick Store

A columnar store of real historical market data, verified by cross-implementation gates whose
expected values were computed independently in a second engine; so the store is checked against
something other than itself.

[Spec pack →](/specs/YU07-historical-tick-store)

### YU08: Execution Algo Engine

Large orders become TWAP parents sliced into child orders on a schedule, submitted through the same
risk-gated ingress as any other order rather than around it.

[Spec pack →](/specs/YU08-execution-algo-engine)

### YU09: Ops Hardening

Adds operational controls: credentials from Kubernetes secrets rather than literals, probe and durability
fixes, memory limits that stop a warm-up OOM, and continuous delivery for the remaining services.

[Spec pack →](/specs/YU09-ops-hardening)

### YU10: FIX Order-Entry Ingress

A standard FIX 4.4 session for external counterparties; new orders and cancels arriving over the
protocol the industry actually uses, mapped onto the same sequenced path as REST.

[Spec pack →](/specs/YU10-fix-ingress)

### YU11: Aeron + SBE Replication

Replaces warm-standby replication with Aeron transport and SBE encoding, carrying sequenced events to replicas and supporting recovery across an epoch boundary.

[Spec pack →](/specs/YU11-aeron-replication)

### YU12: Aeron Cluster Consensus

High availability becomes Raft consensus across three members, decided by the cluster itself with
Kubernetes out of the decision path. The published failover drills reported recovery under 200 ms and no order-ID reuse.
Recovery time depends on the client path and cluster configuration.

[Spec pack →](/specs/YU12-aeron-cluster)

### YU13: Crossing Limit-Order Book

A crossing book: price-time priority, marketable orders filled at the resting price, market
orders that cannot rest at an undefined price, self-trade prevention, atomic replace, idempotent
client order IDs; and the whole resting book carried in the cluster snapshot.

[Spec pack →](/specs/YU13-limit-order-book)

### YU14: Listed Equity Options

Listed equity options trade as ordinary securities on the unchanged book, with no second matching
path. The risk math becomes contract-multiplier aware, so a $2.50 option controlling 100 shares
consumes $250 of credit rather than $2.50.

[Spec pack →](/specs/YU14-listed-equity-options)

### YU15: EOD Risk Extract

A portfolio extract for an external risk engine, with every account frozen at the same consensus
instant; sampling accounts at different moments could describe a portfolio that never existed. Rows are un-netted with the counterparty attached, and the
bytes are identical every time for a given identifier.

[Spec pack →](/specs/YU15-eod-risk-extract)

### YU16: CDM Instruments

The instrument model becomes CDM-shaped: reference data serves security types and asset identifiers,
and five ETFs and five fixed-rate U.S. Treasuries join a universe that had only equities in it. A
Treasury is quoted as a fraction of par with a solved yield, orders in it are validated by face
amount rather than share count, and the risk extract carries its coupon, maturity and an accrued
interest fraction derived from the session date rather than from any new reference data.

[Spec pack →](/specs/YU16-cdm-instruments)

### YU17: OTC Interest-Rate Swaps

Vanilla fixed-float interest-rate swaps and swaptions book through the same consensus log as orders,
but are applied beside the matching engine rather than through it; a swap has no book to rest in
and no counterparty order to cross. Contracts are held in replicated state and exported as a second
end-of-day artifact carrying terms and no valuation of any kind, cut at the same consensus instant
as the position extract.

The market data underneath them was rebuilt in the same state. A book's price band now follows the
market's own reference rather than whichever order happened to arrive first; an empty book derives
its tick size from that reference, so a sub-par bond and a several-hundred-dollar share are each
quoted at a usable granularity; and the trading session gained explicit closed, pre-open and open
phases, with orders accepted and queued in consensus until the open rather than refused. The
external reference is no longer invented: it is a licensed historical market tape, resampled offline
and replayed on a clock derived from the epoch, and prints from it enter as sampled order flow so the
engine matches, fills and moves positions on historical market activity.

[Spec pack →](/specs/YU17-otc-rates)

### YU18: Risk Integration

Connects the portfolio and OTC extracts to an external EOD worker. Immutable bundles bind the portfolio cut, instrument terms and market inputs; returned results are checked for matching identity, coverage, units and calculation status before they appear in the Risk UI. Original request and response bytes are retained so a result can be traced back to its inputs.

Submission status survives restarts. An uncertain submission is reconciled through read-only checks rather than automatically submitted again. The container demo supports a fixed synthetic Treasury-bill portfolio with an assumed curve; it does not provide general live-portfolio pricing or production risk measures.

[Spec pack →](/specs/YU18-risk-integration)

## Other additions

These features extend the states above. They include infrastructure and market-data work as well as the trading and operational interfaces.

### OpenTelemetry tracing

An order's trace follows it across the Raft cluster, exported asynchronously so telemetry never sits
in front of a trade; a producer copies a few longs into a ring buffer and returns, and a full ring
drops the span rather than slowing the order. Grafana, Prometheus, Loki and Tempo deploy with the
platform. Built into [YU13](/specs/YU13-limit-order-book); the long version is in
[Observability and replay](observability-and-replay.md#opentelemetry-a-trace-across-consensus).

### KDB-X tick store (kdb+/q)

The platform stores and replays market history in kdb+/q; VWAP, spreads, a session pulled out by time window
and replayed at real time or as fast as the machine will go, all in q. It holds the NYSE TAQ tape
and our own engine's flow, the latter captured by a leader-side tap that sits off the consensus
path. Built into [YU07](/specs/YU07-historical-tick-store); the long version is in
[Observability and replay](observability-and-replay.md#kdb-x-tick-store-kdbq).

### The market tape as the venue's reference

The NYSE TAQ corpus the tick store already holds is now also the venue's external price reference:
resampled offline into one median price per symbol per interval, and replayed on a clock whose
position is derived rather than stored, so a publisher restart resumes at the right point with
nothing persisted. Prints from the same tape enter as sampled order flow through dedicated accounts.
A tick carries a `source` and an `asOf` beside the `simulated` flag, because a real price at a
fabricated time is neither live nor invented and a boolean cannot say which. Shipped with
[YU17](/specs/YU17-otc-rates) over [YU07](/specs/YU07-historical-tick-store)'s corpus; the long
version is in [Observability and replay](observability-and-replay.md#the-market-tape-replayed).

### Operator-scoped attribution

A continuously replaying tape is a second writer on the venue, so a venue-wide counter answers a
question about the whole venue rather than about whoever is asking it. Five of the counters replayed
flow moves gained an **operator-scoped twin** that excludes externally-generated flow by
construction, separating operator activity from the replay publisher's traffic; and the two that matter across a rebuild are carried in the snapshot, so a scoped claim
survives one. Shipped with
[YU17](/specs/YU17-otc-rates); the long version is in
[Testing strategy](testing-strategy.md#auditing-the-proofs-against-a-live-writer).

### Order types and lifecycle

The book supports MARKET, LIMIT, STOP, STOP_LIMIT, ICEBERG, PEGGED and TRAILING_STOP orders, with supported time-in-force combinations checked at entry. Stop and trailing orders wait for their triggers, iceberg orders expose a display quantity, and pegged orders follow the configured reference. DAY expiry follows a sequenced business-day change so members reach the same result.

Cancellation, atomic replacement and client-order-ID deduplication apply across the lifecycle. Self-match groups extend protection across accounts in the same workspace. See the [order-types component](/specs/YU18-risk-integration/components/order-types).

### Managed run identity and projection recovery

A managed run binds the engine epoch to its SQL projections. Readers reject stale or mismatched scope, and historical runs remain read-only. Explicit archive catch-up validates the retained rows before applying missing events. Optional automatic catch-up adds a lease, fencing and transactional cursors; it is disabled by default and requires complete retained history.

Recovery refuses missing archive data and conflicting rows instead of deleting them to force agreement. See [integration and recovery](integration-and-recovery.md).

### Original Demo UI / console

The original console remains available through **More → Demo console** in the new Desk. It includes:

- Trading tickets, orders, executions, positions and OTC contract views, including the seven typed order modes when the gateway advertises them.
- System member/book comparisons, service health and links to metrics, logs and traces.
- EOD close, quality review, explicit overrides, publication and artifact provenance; Risk job status and validated calculation coverage.
- Accounts, administrative session driving and reconciliation, FIX session tools and reference data.
- Historical tick queries, kdb+/q tools, replay, sandbox and corpus browsing, plus access to the legacy application.

Some actions require console administrator credentials and configured services. A connected gateway indicator does not certify the entire rig. See [UI workflows](ui-workflows.md#demo-console).

### New Trader Desk

The Desk organizes the frequent trading workflow around **Overview, Markets, Orders, Positions and Risk**, with secondary tools in More and a separate admin workspace.

- Select a workspace account, inspect instruments and price freshness, sort Markets, and open the inline Angular order ticket.
- Use **Price at market** to copy a fresh mark into a limit field. The order type stays unchanged; the value is not an execution guarantee.
- See accepted, refused and unknown outcomes separately. An accepted order resets the next ticket while keeping its receipt visible.
- Create a demo account with separate SQL creation and engine admission steps; workspace accounts share self-match protection.
- Keep account selections and UI state within the local workspace. Managed reads check the active pointer and reject stale replies; historical views are read-only.
- Inspect stored risk results under their own portfolio/cut identity. The selected account's valuation is not fabricated from unrelated demo results.

Existing-order Desk mutations require confirmed managed scope, and the legacy profile leaves them disabled. Secondary tools and swap/swaption entry remain accessible through Demo console. Username-only local sessions and an optional server-checked admin password are a demo profile, not production authentication or tenant isolation.

## See how it is verified

In-process suites check engine, gateway, risk and post-trade behavior. Container-backed tests exercise database and broker contracts. End-to-end scripts submit orders and inspect the committed engine state, projected rows and returned results. Cluster recovery and timing have separate checks that need a suitable running environment.

- [Testing strategy](testing-strategy.md): the testing tiers, runnable commands and what each check proves.
- [Test coverage](test-coverage.md): coverage by module, integration suite, gate and proof script.
