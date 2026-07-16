# Runtime Topology: YU10-fix-ingress

## Entrypoints

| Entrypoint | Transport | Consumer |
|---|---|---|
| `order-matcher:18110` | HTTP (REST + UI) | unchanged from YU09 |
| `order-matcher:18130` | FIX 4.4 over TCP | FIX initiators (cluster-internal; kind NodePort for test clients) |

## Components

- **order-matcher** (single replica, single-BLP mode): gains the in-process QuickFIX/J acceptor,
  identity gate, translator, correlation ledger, and ExecutionReport handler. The FIX data
  directory (`/var/lib/traderx-lmax/fix`) lives on the existing `lmax-runtime-data` volume next
  to the journal.
- All other services are unchanged from YU09.

## Networking

- The FIX port is exposed on the order-matcher Service (ClusterIP) and, on kind, a NodePort for
  external test clients. It is not routed through ingress-nginx and has no public exposure; the
  deployed session profile is plaintext TCP inside the cluster boundary.
- The endpoint addresses the single order-matcher replica directly (the state deploys in
  single-BLP mode); it is not routed through the HA primary-election Service.

## Startup / Health Order

1. Journal replay and ledger rehydration run during startup (readiness-targeted startup probe,
   existing budget).
2. The FIX acceptor binds and accepts logons only after the application reports readiness — a
   counterparty connecting during replay is refused exactly as HTTP traffic is.
3. Shutdown stops the acceptor (sessions receive normal FIX logout) before the engine stops.

## Degraded Behavior

| Condition | Behavior |
|---|---|
| Input ring full | The publishing session thread blocks (TCP flow control to that counterparty); bounded claim timeout → session-level reject, no order exists. Other sessions and the BLP proceed. |
| Ledger unavailable (disk failure/full) | FIX order admission fails closed with session rejects; REST is unaffected; disk-watermark alerts fire ahead of exhaustion. |
| Counterparty disconnects | Undelivered ExecutionReports persist in the FIX store; the resend window delivers them at next logon. |
| Order-matcher restart | Sessions drop; journal replay + ledger rehydration; sessions reconnect after readiness and reconcile via ResendRequest; `OrderStatusRequest` recovers any order's state; a same-`ClOrdID` retry is answered as a duplicate. |
| Invalid logon (bad JWT / unmapped CompID) | Logon rejected; no session state is created. |
| JWT expires mid-session | The pinned principal remains valid for the session's lifetime; a new logon re-resolves. |
