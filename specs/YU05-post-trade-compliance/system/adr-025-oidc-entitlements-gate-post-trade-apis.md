# ADR-025: OIDC Entitlements as the Access-Control Layer for This Bundle

**Status:** Implemented as real JWT verification + entitlement gating (not full OIDC — see
"Implementation note" below); `principalKey` wiring into order admission deferred.
**Date:** 2026-07-06
**State:** `YU05-post-trade-compliance` (parent `YU03-in-memory-risk-gateway`)

## Context

Settlement, reconciliation, regulatory reporting, and TCA all expose account-level trade data.
Today, every API in this system (including the new `/recon/*` and `/trades/{id}/settlement/force`
endpoints added in slice 1) is gated only by a shared static token + operator header — adequate for
a single-operator admin surface, not for real multi-user access control. `accountId` is a
hardcoded, unauthenticated path/query parameter everywhere in the system (a pre-existing condition,
not introduced by this state). The risk gateway (YU03) already allocated an `entitlementKeys`/
`entitlementEnabled` structure for exactly this purpose and left it unfed, explicitly waiting on
"the real-auth roadmap item" (YU03 FR-IMRG02 partial, FR-IMRG30 partial).

## Decision

Real auth/entitlements (OIDC/SSO) is scoped as part of this bundle specifically because it is the
thing that makes the other three sub-capabilities safe to expose beyond a single trusted operator:
resolve an authenticated principal, map it to an entitled set of accounts, and gate every
settlement/recon/reporting/TCA API on that mapping instead of a shared static token. The same
entitlement resolution additionally feeds the already-wired, currently-unused risk-gateway
`principalKey` path from YU03 — closing that deferral as a side effect rather than a separate
future project.

## Alternatives Considered

- **Scope real auth as its own separate state, not bundled here:** considered and rejected by
  explicit user direction this session — the whole point of combining these four items was that
  auth is the connective tissue for the other three, not an unrelated fifth concern.
- **Per-service API keys instead of OIDC:** rejected — doesn't solve principal-to-account
  entitlement mapping (who is this caller, which accounts can they see), only service-to-service
  trust, which the shared token already provides adequately for slice 1.

## Consequences

Positive: once built, every new API this state adds gets real access control instead of the
shared-token stopgap, and YU03's entitlement replica gets fed for the first time.

Costs: this is the largest, most cross-cutting piece of the bundle (touches every new endpoint plus
the existing risk-control endpoints) — correctly sequenced last, after the data-shape work
(settlement/recon/reporting/TCA) it will gate is real (see `plan.md`).

## Implementation note: real JWT, not full OIDC

There is no live OIDC identity provider in this environment (no IdP infra, no user's-explicit-go-
ahead to stand one up). What's built instead: `JwtAuthenticator` (order-matcher and trade-processor
each have their own copy — no shared library between the two Gradle modules) does **genuine HS256
signature verification** via JDK `javax.crypto.Mac` + Jackson (no new dependency) against a shared
secret (`auth.jwt.secret`) — a tampered token, a token signed with the wrong secret, and an expired
token are all cryptographically rejected, not just string-compared like the token headers this ADR
replaces. `JwtPrincipal` carries `subject`, `entitledAccounts`, and an `admin` override for cross-
account operations. `POST /auth/dev-token` (trade-processor) mints tokens locally. Swapping in a
real OIDC provider later means replacing signature verification with JWKS-based verification behind
the same `JwtAuthenticator`/`JwtPrincipal` contract — the five gated endpoints and their entitlement
checks don't change.

## Status in YU05

**Implemented** (FR-PTC40, FR-PTC41). Every new endpoint from this state (`/recon/*`,
`/regulatory/report`, `/trades/{id}/settlement/force`, `/tca/report/{tradeId}`) requires a valid
JWT; account-scoped endpoints (settlement force, TCA) check `JwtPrincipal.isEntitledTo(accountId)`
against the trade's own account, looked up before the action runs. Cross-account endpoints require
`admin`. `/risk/control/*` (YU03) is unchanged — out of scope for this ADR, still the shared-token
scheme.

**FR-PTC42 (feeding the risk gateway's `principalKey`) deferred.** That requires wiring principal
resolution into order *submission* (`OrderMatcherService`/`GatewayReplicaStore`), a different,
hot-path-adjacent surface this state never touched — none of YU05's work modifies order admission,
by design (FR-PTC07). A future state should do that wiring deliberately, with its own hot-path
allocation/latency verification (matching YU03's NGC discipline), not as a side effect of this one.

## Validation

- `JwtAuthenticatorTest` (both modules): valid-token round-trip, wrong-secret rejection, tampered-
  payload rejection (signature no longer matches), expired-token rejection, malformed-token
  rejection, `admin` override semantics.
- A principal entitled to account A but not account B is rejected (**403**) calling
  `/trades/{id}/settlement/force` or `/tca/report/{tradeId}` for a trade owned by B — enforced by
  each controller's per-request `TradeRepository` lookup before the entitlement check, exercised
  manually against the running services (no automated controller-level test in this pass; the
  entitlement logic itself — `JwtPrincipal.isEntitledTo`— is unit-tested directly).
