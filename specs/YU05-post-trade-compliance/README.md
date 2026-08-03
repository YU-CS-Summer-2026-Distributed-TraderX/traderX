# Feature Pack YU05: Post-Trade Compliance Bundle

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Implemented
Track: `architecture`
Lineage role: `optional`
Previous state: `YU04-durable-control-feeds`

This pack bundles four back-office/compliance capabilities that all consume the journal's
executed-fill stream as their common source of truth: post-trade settlement + reconciliation
(including full-history orphan detection), regulatory reporting (CAT/TRACE-style), TCA (transaction
cost analysis), and real JWT auth/entitlements as the access-control layer gating the other three.

Primary intent:

- give a booked trade a real settlement lifecycle and a deterministic id linking its MariaDB row to
  the journal fill that produced it,
- reconcile the read-model projection against the authoritative journal — both a forward sweep and
  an on-demand full-history orphan sweep,
- expose a reproducible, journal-sourced regulatory audit export and per-trade TCA, never from the
  projection and never on the hot path,
- gate every new endpoint behind a real HS256-verified JWT with account-entitlement or admin checks.

Core artifacts:

- `spec.md`
- `requirements/functional-delta.md`
- `requirements/nonfunctional-delta.md`
- `research.md`
- `data-model.md`
- `quickstart.md`
- `contracts/contract-delta.md`
- `system/architecture.model.json`
- `system/architecture.md`
- `system/runtime-topology.md`
- `system/messaging-subject-map.md`
- `system/adr-022-deterministic-trade-identity-and-settlement-recon.md`
- `system/adr-023-journal-sourced-regulatory-reporting.md`
- `system/adr-024-pluggable-tca-benchmark-source.md`
- `system/adr-025-oidc-entitlements-gate-post-trade-apis.md`
- `generation/generation-hook.md`
- `generation/implementation-status.md`

Target runtime behavior:

- order-matcher hosts the replay-safe trade blotter, the shadow-replay full-history reindex and
  regulatory report, and its own JWT authenticator.
- trade-processor hosts settlement, reconciliation, TCA, dev-token minting, and its own JWT
  authenticator.
- Everything else (deploy/runtime harness, BLP admission pipeline, observability stack) is inherited
  unchanged from `YU04-durable-control-feeds`.
