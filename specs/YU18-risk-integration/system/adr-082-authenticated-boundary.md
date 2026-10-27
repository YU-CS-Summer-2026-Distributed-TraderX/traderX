# ADR-082: An authenticated boundary on every route (ENGINE-REVIEW S1–S6)

Status: accepted, 2026-10-25 to 2026-10-27. Pack: [engine-review hardening](../maintenance/engine-review-hardening/spec.md).
The Aeron ingress behind these routes is ADR-079 §4.

## Context

The engine review found that the gateway's order, operator, FIX and registration routes trusted the
request body. The body's `accountId` was the whole claim, on a listener the GKE tier exposed through a
LoadBalancer. Anyone who reached it could trade as any account, halt or open the venue, re-anchor price
bands, or exhaust the 1,024 replicated symbol slots for an entire epoch.

## Decision

- **S1. The order routes need a JWT bound to the account.** `/orders`, `/orders/batch`, `/cancel`,
  `/replace`, `/trades`, `/swaps` and `/swaptions` validate the members' HS256 JWT (`AUTH_JWT_SECRET`,
  ADR-025). The account a request acts on must be one the principal is entitled to; a single-entitlement
  principal may omit it; an admin may name any account. The decision is the pure function
  `GatewayAccess.accountFor`.
- **S2. Operator routes need the operator pair.** `/session`, the business-day roll and `/seed` require
  `X-Risk-Control-Token` (compared in constant time) plus a non-blank `X-Risk-Operator`, or an admin JWT.
- **S3. FIX logons carry credentials.** `FIX_SESSION_AUTH="COMPID:password:acct1|acct2,..."` binds each
  CompID to a `Password(554)` and to the accounts it may name; `*` names every account. A request outside
  the binding is rejected, never ignored. `FIX_SSL_KEYSTORE` turns on QuickFIX/J TLS. Authentication is the
  default: a gateway with `FIX_ACCEPTOR_PORT` set and `FIX_SESSION_AUTH` unset refuses to start, under the
  same contract as S5, and `ALLOW_DEV_SECRETS=true` is the only opt-out (kind sets it; the acceptor then
  logs INSECURE and keeps the CompID-only behaviour). On GKE the gateway builds
  `FIX_SESSION_AUTH=CLIENT1:$(FIX_SESSION_PASSWORD):*` from `auth-secrets/fix-session-password`, a
  required key, and `prepare.py` refuses a CompSci candidate whose FIX acceptor lacks that wiring or
  whose workloads carry `ALLOW_DEV_SECRETS`. CLIENT1's one client there is the console's FIX ticket. It
  presents the same key, only for a signed-in operator, and the panel's copy of the Logon masks
  `554` (2026-10-27).
- **S4. Only trusted paths register symbols.** Order entry, `/trades` and `/resolve` only look tickers
  up, through the owner-thread cache refreshed from the member's read-only `/symbols`. Three paths
  register: `/seed`, the reference-data control feed (bbe9b040), and operator `/risk/control action=security`
  (8ea15b88). All three are trusted-infrastructure or operator-only.
- **S5. No development secrets in production.** Gateway and member refuse to start when a secret is unset
  or a known development value, unless `ALLOW_DEV_SECRETS=true`. On GKE the kustomization generates
  `auth-secrets` and `gateway-secrets` from git-ignored `secrets/*.env`.
- **S6. Bounded bodies and batches.** Bodies are read through `readBounded()` up to
  `GATEWAY_MAX_BODY_BYTES` (8 MiB), and batches are capped at `GATEWAY_MAX_BATCH_ORDERS` (100,000).
  Either limit answers 413.
- **Callers sign.** The console attaches its admin JWT to the S1 routes only for a signed-in operator
  (12a0a21b), and its `/fixorder` ticket is likewise behind operator sign-in. trade-service mints a 60 s, one-account, non-admin bearer over `AUTH_JWT_SECRET` for the
  legacy UI's `/trades` and propagates the gateway's 4xx (951ed3c9).

**Rejected:** in-process TLS on the gateway. TLS terminates at the ingress in front of the LoadBalancer.

## Consequences

The startup contract changes on every tier: a rig without real secrets, or without the explicit dev
opt-in, does not come up. That includes a FIX acceptor without session credentials, and on GKE an
`auth-secrets` without `fix-session-password`. Every S item is gateway-only and rolls with a gateway restart. No engine,
wire or snapshot change.
