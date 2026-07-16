# ADR-036: Fail-closed session identity — JWT at logon, mapped CompIDs only

**Status**: Accepted · **State**: YU10-fix-ingress

## Context

A `SenderCompID` is an identifier, not proof of identity. The existing entitlement machinery
(`EntitlementGate.resolve`) validates a JWT and returns a `ResolvedPrincipal` designed to be
resolved once and reused. The REST path's `risk.entitlement.enforced` flag defaults to false to
keep the token-less UI working — a compatibility property of an existing surface.

## Decision

1. **Logon authenticates**: `Password(554)` carries a JWT (the same tokens the dev-token
   endpoint mints); the acceptor calls the existing `EntitlementGate.resolve` once at logon and
   pins the `ResolvedPrincipal` to the session. No per-message resolution.
2. **CompID must be mapped**: `FIX_SESSION_ACCOUNTS` is a complete allowlist binding each
   CompID to exactly one account. Unmapped CompID, absent/invalid JWT, or a JWT whose principal
   is not entitled to the mapped account ⇒ logon rejected. There is no fallback account.
3. **Fail-closed regardless of the REST flag**: `risk.entitlement.enforced` continues to govern
   the REST path only. A brand-new ingress surface has no installed base to keep compatible, so
   it starts closed.
4. **Cluster-internal endpoint**: the acceptor port is not routed through ingress and has no
   public exposure; kind exposes a NodePort for test clients. The deployed session profile is
   plaintext TCP inside the cluster boundary — acceptable precisely because the endpoint does
   not cross that boundary.

## Alternatives considered

- **CompID-only identity (no credential)**: rejected — any network-reachable client could claim
  a mapped CompID.
- **A parallel FIX-specific credential store**: rejected — the JWT infrastructure, entitlement
  resolution, and account model already exist; a second credential system adds surface without
  adding strength.
- **Demo-account fallback for unmapped CompIDs**: rejected — it converts an unknown external
  identity into a valid trading account silently.

## Consequences

- Session identity strength equals the JWT secret's strength, shared with the REST path — one
  credential system to operate and rotate.
- The pinned principal lives for the session; re-authentication is a new logon.
- Any future public exposure of the port is gated on transport security (TLS/mTLS) as a
  precondition, recorded here as the boundary this state deliberately does not cross.
