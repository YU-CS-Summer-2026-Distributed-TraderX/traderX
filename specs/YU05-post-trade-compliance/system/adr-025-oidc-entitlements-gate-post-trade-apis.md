# ADR-025: OIDC Entitlements as the Access-Control Layer for This Bundle

**Status:** Accepted for specification (not yet implemented — deferred, see plan.md sequencing)
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

## Status in YU05

**Deferred** — specified only (FR-PTC40/41/42). Slice 1's new endpoints use the existing shared
token + operator header pattern as an explicit, documented stopgap (NFR-PTC04) until this lands.

## Validation (future)

A principal entitled to account A but not account B must be rejected (403) on any settlement/
recon/reporting/TCA API scoped to account B; the risk gateway's `traderx_gateway_blp_mismatch_total`-
style mismatch counting pattern should be mirrored for entitlement denials once real, to keep
denial telemetry auditable rather than silent.
