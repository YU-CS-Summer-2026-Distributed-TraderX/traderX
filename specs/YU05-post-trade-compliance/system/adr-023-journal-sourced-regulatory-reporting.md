# ADR-023: Regulatory Reporting Sourced From the Journal, Not the Projection

**Status:** Accepted for specification (not yet implemented — deferred, see plan.md sequencing)
**Date:** 2026-07-06
**State:** `YU05-post-trade-compliance` (parent `YU03-in-memory-risk-gateway`)

## Context

Real markets require CAT/TRACE-style audit trails: a reproducible record of every order and trade
lifecycle event (submission, accept/reject with reason, fill, cancel), not just final state. The
MariaDB `TRADES`/`OrderBook` tables are a mutable, current-state projection (rows get updated in
place) — they cannot reconstruct "what happened and when" for a regulator, and per the inherited
FR-IMRG41 invariant, MariaDB is explicitly not authoritative for anything.

## Decision

Regulatory reporting will be generated from the **input journal** (via `JournalReader`, which
already exists and replays every sequenced event byte-for-byte) plus the output-side trade blotter
(ADR-022) for execution details, over a requested date/sequence range — never from the MariaDB
projection. Report generation runs offline/on-demand, never on the BLP's command path.

## Alternatives Considered

- **Generate reports from MariaDB history tables (add audit triggers):** rejected — reintroduces
  MariaDB as a source of truth for compliance-critical data, violating the inherited projection
  invariant, and triggers add write-path overhead to a service not designed for it.
- **Stream every event to an external audit system in real time:** deferred as a much larger
  scope than "generate a report for a date range" — worth reconsidering once/if a real regulatory
  deadline exists, not needed for this state's goal of demonstrating the capability.

## Consequences

Positive: reports are reproducible byte-for-byte from immutable journal history, consistent with
every other "journal is truth" decision in this lineage (ADR-020).

Costs: report generation must resolve securityId → ticker and orderRef → accountId the same way
recovery does (via `SymbolTable` and order snapshot state at the relevant point in history), which
is more involved than a simple SQL query over a projection table.

## Status in YU05

**Implemented** (FR-PTC20/21/22). `LmaxEngine.generateRegulatoryReport(fromSeq, toSeq)` reuses the
shadow-engine replay skeleton with a new `AuditLogHandler` capturing every reportable output kind
in range, exposed via `GET /regulatory/report`. Own token namespace
(`regulatory.control.token`) — real OIDC (ADR-025) still pending.

## Validation (future)

Reports for a given range must be reproducible bit-for-bit across repeated generation runs against
the same journal state (mirrors YU03's `RiskReplayDeterminismTest` discipline).
