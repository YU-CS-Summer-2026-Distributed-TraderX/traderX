# Functional Delta: YU10 over YU09-ops-hardening

Everything YU09 provides is retained unchanged. This state adds one new ingress surface to the
order-matcher and nothing else.

## Added

- **FIX 4.4 acceptor** (in-process, port 18130): sessions authenticated at logon
  (JWT in `Password(554)` through the existing `EntitlementGate`; `SenderCompID` mapped to one
  account via `FIX_SESSION_ACCOUNTS`; fail-closed on both). FR-FIX01, FR-FIX07, FR-FIX08.
- **Order entry over FIX**: `NewOrderSingle` and `OrderCancelRequest` translate to the exact
  input events the REST path produces and share the ring, risk screen, journal, and recovery
  unchanged. FR-FIX02, FR-FIX03.
- **Order state over FIX**: `OrderStatusRequest` answered from the in-memory read model.
  FR-FIX04.
- **Asynchronous ExecutionReports**: a dedicated output-disruptor handler translates lifecycle
  events to `ExecutionReport`/`OrderCancelReject` on the owning session, ordered per order by
  output-ring sequence, persisted for resend. FR-FIX05, FR-FIX06, FR-FIX11.
- **Durable correlation ledger** binding (session, ClOrdID) ↔ (inputSeq, orderRef): duplicate
  detection, cancel/status resolution, report correlation, restart rehydration. FR-FIX09,
  FR-FIX10.
- **Deterministic outcome semantics**: the four-outcome admission model; ambiguous post-publish
  outcomes are never converted to rejects. FR-FIX12.

## Unchanged

REST `/orders`, `/orders/batch`, `/trades`, cancel/force-fill endpoints and their contracts; the
web UI; risk controls and the operator token; `risk.entitlement.enforced` semantics for REST;
journal/snapshot/recovery; replication; all NATS subjects; all database schemas.
