# Missed-event recovery specification

- FR-ER01: Explicit local operator recovery binds the registered managed descriptor and selected ACTIVE or DRAINING scope. Never infer legacy attribution, activate, unfreeze, or mutate sealed/unselected scopes.
- FR-ER02: Obtain complete archive-generated production events at a stable applied boundary. Refuse missing recording prefixes, discontinuities, truncation, changing boundaries, descriptor mismatch, or configured capacity exhaustion. NATS is a low-latency notification path, not completeness authority.
- FR-ER03: Before changes, validate every event identity, ordered sequence/ordinal, trade counter and every retained trade/order against source. Refuse duplicate/conflicting source events and orphan/conflicting SQL history. Never overwrite immutable trade economics.
- FR-ER04: Restore missing trades and final order states idempotently. Recompute derived positions in authoritative event order to handle interior gaps and out-of-order delivery; preserve already retained trades and settlement state. Historical rejected bookings or unavailable bond metadata must refuse the whole transaction; never silently reinterpret them.
- FR-ER05: Apply recovery rows, position correction, and hashed complete-boundary checkpoint in one transaction using the existing global write lock. Rollback leaves no checkpoint or notifications. Lost post-commit reply is safely retryable. The maximum observed live sequence is not a completeness checkpoint.
- FR-ER06: Keep existing scoped read routes and notification contracts; operator API is additive. Validate current scope/phase after obtaining archive and again under the SQL write lock. Events ahead of witness cause refusal/retry.
- NFR-ER01: Explicit bounded full replay, not scheduled/unbounded background work. Default maximum 10000 events and 16 MiB peer response; existing archive replay deadline/caps also apply. Full replay holds a SQL transaction during application, no throughput or latency guarantee. Requires a sufficiently quiet applied boundary; busy sources can refuse and be retried later.
- NFR-ER02: Local trusted loopback gateway/archive plus descriptor custody; no claim against malicious authorized archive operator, financial valuation, HA, or lost authoritative storage. No raw externally supplied recovery payload endpoint.

Acceptance: actual consumer disconnect and restart loses NATS projections before recovery; full SQL trade/order/position convergence after recovery; publisher/NATS interruption and partial delivery; retries, duplicate/out-of-order controls, conflict/missing-source refusals; transaction rollback and post-commit retry; selected/frozen/sealed run isolation. Source/generated/live results reported separately.

## O1-R1 correction and approved reader

- FR-ER07: Before all recovery writes, require retained per-key position quantities to equal the signed sum of retained nonrejected trades. Missing position with any retained trades refuses; no retained trades plus absent or zero-quantity/zero-basis position remains recoverable. Unexplained balances/basis must not be erased. Preserve legitimate interior-gap and out-of-order recovery. Validate source-order lifecycle account/security/side and trade-to-order provenance.
- FR-ER08: Add read-only GET /v2/projections/active returning the actual selected registered scope as {"projectionScope":"..."}; missing/dangling pointer503; preserve registry list shape. Unknown scoped orders404; known-empty200[].

SC-ER-R1: Real MariaDB pre-fix repro records17/basis999 overwritten to20/basis150 plus checkpoint. Post-fix controls require unchanged SQL state and zero notifications on quantity, missing-position, initial-basis and source-order provenance refusals. SC-ER-R2: real MariaDB pointer switches with two ACTIVE registry rows, missing/dangling pointer refusal and unchanged list shape; MockMvc verifies mapped HTTP contracts.

## SQL timestamp precision contract (2026-09-25)

Order event digests bind the complete archive event, including millisecond wire timestamps.
Retained order fields must equal the event's SQL representation: createdAt/updatedAt are cast
using the actual orderbook column datetime precision and the persistence connection's conversion
rules. The generated ConfigMap stores DATETIME seconds; a legitimate round-trip loses subsecond
bits. Fractional-precision installations retain those bits as declared. No timestamp is ignored:
a difference representable in the declared SQL type refuses, as do altered economics, provenance,
or event digest. Missing/unsupported precision metadata refuses. This requires no schema migration
and does not rewrite retained rows or recover precision that the schema never stored.

The initial unchanged combined live proof refused a legitimate retained order. A regression using
the actual generated ConfigMap order-table DDL isolates updatedAt 1005 -> 1000 ms; exact digest
comparison remains intact. Precision 0/3/6, repeated catch-up/checkpoint stability and genuine stored
timestamp/economic/provenance corruption are separate controls. Final execution results are recorded
in the combined acceptance report; this paragraph defines the contract, not a pass claim.
