# Contract Delta: YU10 over YU09-ops-hardening

All existing REST + NATS + UI contracts are retained unchanged. This state adds one new wire
contract — a FIX 4.4 session endpoint — and its configuration surface. No NATS subject, no HTTP
endpoint, and no database schema changes.

## 1. FIX session endpoint (new)

- **Transport**: TCP, port 18130 (`FIX_ACCEPTOR_PORT`), cluster-internal; kind exposes a
  NodePort for test clients. Plaintext inside the cluster boundary.
- **Protocol**: FIX 4.4. Server CompID `TRADERX` (`FIX_TARGET_COMP_ID`).
- **Logon (35=A)**: `Password(554)` = JWT (the same tokens the REST dev-token endpoint mints);
  `SenderCompID(49)` must appear in `FIX_SESSION_ACCOUNTS`. Any failure → logon rejected.
- **Application messages in**: `NewOrderSingle (D)`, `OrderCancelRequest (F)`,
  `OrderStatusRequest (H)` — field mapping in `data-model.md`.
- **Application messages out**: `ExecutionReport (8)` (New / PartialFill / Fill / Canceled /
  Rejected / Status), `OrderCancelReject (9)`, session-level `Reject (3)`.
- **Outcome semantics**: pre-publish failures and malformed messages are rejected at the session
  level; application rejections arrive as Rejected ExecutionReports with the risk reason in
  `Text(58)`; a post-publish timeout produces no reject — the eventual ExecutionReport is the
  outcome and `OrderStatusRequest`/same-`ClOrdID` retry reconcile it. A duplicate `ClOrdID` on a
  session is rejected deterministically.
- **Recovery**: sequence numbers and sent messages persist across restarts; `ResendRequest`
  replays stored ExecutionReports; `OrderStatusRequest` recovers any order's current state.

## 2. Environment variable surface (new, `order-matcher`)

| Var | Effect |
|---|---|
| `FIX_ACCEPTOR_PORT` | Acceptor TCP port (default 18130). |
| `FIX_SESSION_ACCOUNTS` | `COMPID:accountId[,…]` — the complete logon allowlist. Empty ⇒ no session can log on. |
| `FIX_DATA_DIR` | FIX store + correlation ledger directory (default `/var/lib/traderx-lmax/fix`, on the existing PVC). |
| `FIX_TARGET_COMP_ID` | Server CompID (default `TRADERX`). |
