# Feature Pack: YU10-fix-ingress

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

- No PowerShell parity: the scripts this pack names (`scripts/proofs/yu10-fix-session.sh`) drive a FIX session against
  a Kubernetes rig through `kubectl`. They are
  Linux/macOS tooling for a rig that is itself Linux, and there is no `.ps1` equivalent
  anywhere in this repo for `scripts/proofs` or `scripts/bench` — the repo's PowerShell
  scripts are lifecycle scripts for the numbered states. The windows badge above already
  says this in an image; this bullet is the machine-readable form of the same claim.


Status: In implementation
Track: `architecture`
Lineage role: `optional`
Previous state: `YU09-ops-hardening`

This pack gives the order-matcher a FIX 4.4 order-entry front door on top of the
`YU09-ops-hardening` baseline: an in-process QuickFIX/J acceptor whose sessions feed the same
Disruptor input ring, risk screen, and journal that the REST path uses, with asynchronous
ExecutionReports flowing back off the output disruptor. A FIX order is bit-identical to a REST
order past the front door. Session identity is fail-closed: a session logs on with a JWT and a
mapped CompID or it does not log on at all.

Primary intent:

- accept `NewOrderSingle`, `OrderCancelRequest`, and `OrderStatusRequest` over standard FIX 4.4
  sessions terminated inside the order-matcher process, translated on the session thread straight
  onto the existing multi-producer input ring — no per-order HTTP envelope, no per-order blocked
  round-trip,
- return `ExecutionReport`/`OrderCancelReject` asynchronously from a dedicated output-disruptor
  handler, with per-order ordering guaranteed by output-ring sequence,
- persist FIX session state (sequence numbers, sent messages) and a client-order correlation
  ledger on the order-matcher's existing PVC, so a reconnecting counterparty reconciles via the
  standard resend window and can always recover order state with `OrderStatusRequest`,
- authenticate every session at logon: `Password(554)` carries a JWT resolved once through the
  existing `EntitlementGate`, and `SenderCompID` maps to a trading account through committed
  configuration — unmapped or credential-less sessions are rejected at logon regardless of the
  REST entitlement flag.

Core artifacts:

- `generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/fix/` —
  acceptor lifecycle, session/identity resolution, message translation, correlation ledger,
  ExecutionReport handler
- `generation/runtime-overrides/order-matcher/build.gradle` — QuickFIX/J dependency
- `generation/runtime-overrides/kubernetes-runtime/manifests/base/order-matcher-deployment.yaml`
  / `order-matcher-service.yaml` — FIX acceptor port (18130), FIX data directory on the existing
  `lmax-runtime-data` volume
- `scripts/bench/load/fix-load.mjs`, `scripts/proofs/yu10-fix-session.sh` — throughput sender and
  session-behavior proof script
- `system/adr-034` … `adr-037` — engine/topology, correlation ledger, identity, and
  ambiguous-outcome decisions

Target runtime behavior:

- a counterparty opens a TCP connection to port 18130 (cluster-internal; NodePort on kind), logs
  on with CompID + JWT, and trades: orders in, ExecutionReports out, cancels and status queries
  served,
- the BLP thread, journaler, and risk screen are untouched; FIX session threads are ring
  producers exactly like Tomcat threads are,
- an order-matcher restart replays the journal as always; sessions reconnect after readiness and
  reconcile through the FIX resend window backed by the on-PVC store and ledger.
