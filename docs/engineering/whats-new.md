---
title: What's new
sidebar_label: What's new
description: Trading, risk controls, market history, EOD integration and the two TraderX user interfaces.
---

# What's new

This build extends FINOS TraderX through 18 YU states. Orders pass through in-memory risk checks into a sequenced matching engine; later states replicate that engine on a three-member Aeron Raft cluster. SQL services project committed events for account, order, trade and position queries.

## Trading and post-trade processing

The price-time book supports cancellation, atomic replacement and client-order-ID deduplication. Current typed orders include MARKET, LIMIT, STOP, STOP_LIMIT, ICEBERG, PEGGED and TRAILING_STOP, with an explicit time-in-force matrix. Business-day changes are sequenced commands rather than reads of each member's wall clock. Self-match groups can protect several accounts belonging to the same demo workspace.

Pre-trade controls cover credit, size, notional, price collars and restrictions. Durable control feeds carry changes into the engine. Settlement, reconciliation, transaction-cost analysis and reproducible regulatory exports operate after trading. FIX 4.4 ingress and TWAP scheduling use the trading path. Listed options account for contract multipliers; CDM instruments and OTC swaps/swaptions carry their own terms.

## Prices, history and EOD integration

Versioned closing-price snapshots feed the EOD chain. Quality checks distinguish usable, missing and stale marks; publication overrides are explicit. Historical ticks, kdb+/q capture, replay and sandbox tools support inspection of market and engine events. OpenTelemetry traces and the Grafana stack help follow requests across services.

Risk extracts freeze the portfolio at one consensus cut. YU18 builds byte-identified bundles, validates terms and dated market inputs, and checks returned results before displaying coverage. The accepted HTTP container path uses a closed synthetic Treasury-bill portfolio and an assumed curve. It does not price arbitrary live portfolios or establish production risk eligibility.

## Market reference and operator attribution

YU17 also changes the market behavior beneath OTC booking. Price bands follow the external reference, with fallback to the last trade and then the first limit. An empty book derives its tick grid from that reference. CLOSED, PRE_OPEN and OPEN are sequenced phases; eligible pre-open orders queue until open.

A configured historical-tape publisher replays an offline median reference series using an epoch-derived clock. Sampled prints can also enter through dedicated replay accounts. Price messages carry `source` and `asOf`; replayed history is neither a live market feed nor an invented current price. The offline local profile can instead use explicitly synthetic prices.

Operator-scoped order, trade, self-match and band counters exclude external replay flow. They let an operator attribute their own work while the tape runs; global counters still describe the whole venue. See [market replay](observability-and-replay.md#the-market-tape-replayed) and [proof attribution](testing-strategy.md#attribution-while-the-venue-is-active).

## Original Demo UI / console

The original console remains available through **More → Demo console** in the new Desk. It includes:

- Trading tickets, orders, executions, positions and OTC contract views, including the seven typed order modes when the gateway advertises them.
- System member/book comparisons, service health and links to metrics, logs and traces.
- EOD close, quality review, explicit overrides, publication and artifact provenance; Risk job status and validated calculation coverage.
- Accounts, administrative session driving and reconciliation, FIX session tools and reference data.
- Historical tick queries, kdb+/q tools, replay, sandbox and corpus browsing, plus access to the legacy application.

Some actions require console administrator credentials and configured services. A connected gateway indicator does not certify the entire rig. See [UI workflows](ui-workflows.md#demo-console).

## New Trader Desk

The Desk organizes the frequent trading workflow around **Overview, Markets, Orders, Positions and Risk**, with secondary tools in More and a separate admin workspace.

- Select a workspace account, inspect instruments and price freshness, sort Markets, and open the inline Angular order ticket.
- Use **Price at market** to copy a fresh mark into a limit field. The order type stays unchanged; the value is not an execution guarantee.
- See accepted, refused and unknown outcomes separately. An accepted order resets the next ticket while keeping its receipt visible.
- Create a demo account with separate SQL creation and engine admission steps; workspace accounts share self-match protection.
- Keep account selections and UI state within the local workspace. Managed reads check the active pointer and reject stale replies; historical views are read-only.
- Inspect stored risk results under their own portfolio/cut identity. The selected account's valuation is not fabricated from unrelated demo results.

At this revision, existing-order Desk mutations require confirmed managed scope, and the legacy profile leaves them disabled. Secondary tools and swap/swaption entry remain accessible through Demo console. Username-only local sessions and an optional server-checked admin password are a demo profile, not production authentication or tenant isolation.

## Recovery and validation

Managed identity binds projections to a run. Explicit recovery reconciles retained SQL with the archive; optional automatic catch-up adds a lease, fencing and transactional cursors. Automatic recovery is **default off** and has been exercised locally. It requires complete retained history and refuses conflicts rather than deleting data to make it agree.

Read the [complete state/component map](feature-map.md), [EOD and recovery boundaries](integration-and-recovery.md), [testing strategy](testing-strategy.md) and [historical measurements](measurements.md). Those pages distinguish implemented behavior, local proof, default-off features and remaining acceptance work.
