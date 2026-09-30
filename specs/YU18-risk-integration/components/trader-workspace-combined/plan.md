# Plan

Selected design is implemented in a separate Angular entrypoint and connected through the existing console API server. The original console remains available at port 4321 from More. No financial or engine logic is duplicated.

Current delivery uses local retained kind services, explicit run-scoped projection contracts with a labelled legacy fallback, gateway commands and NATS prices. Cloud archives are disabled. Runtime owner is the standalone console source, not a generated override.

The authorized fresh local YU18 rig now advertises typed-order capability, admits the eight approved demo accounts and passes 31 live order-type cases. Existing-order mutations still require managed run identity. Next: complete trader authentication/account authorization and remaining per-tool migration. Cloud rollout is separately authorized.

## Managed integration

The console session module authorizes workspace membership and delegates order preflight to
`web-front-end-console/desk-orders.mjs`. It consumes the accepted run registry, selected pointer,
scoped order reader and gateway status. The operative YU18 gateway adds an optional immutable
identity precondition; all deterministic matching, snapshot-v13 STP state and recovery remain in
the accepted engine implementation. The Desk fences reads and actions by its account/run context.

The disposable proof launcher under `scripts/desk-managed-recovery/` builds generated Java services,
uses separate single-member Aeron storage and named local SQL/NATS containers, runs actual
transition/catch-up HTTP endpoints, and checks the shipped Desk in Chrome. An empty isolated
kubeconfig and ports 28800–28999 prevent accidental retained-cluster selection. It stops owned
resources, preserving SQL dumps, archive storage, screenshots and failed attempts. It makes no HA,
production authentication, cloud deployment, latency or financial valuation claim.
