# Runtime Topology: In-Memory Risk Gateway

## Inherited Runtime

All `009b` services and supporting components remain present: ingress/UI, trade-service, account-service,
reference-data, price-publisher, order-matcher/BLP, NATS, database/query projector, and LGTM
observability. Existing ports and accepted business output contracts remain unchanged.

## New / Changed Runtime Responsibilities

| Component | Delta |
| --- | --- |
| account-service | Watermarked snapshot plus durable versioned account/entitlement changes |
| reference-data | Authoritative numeric security identity/status snapshot plus deltas |
| risk administration | Versioned limits, restrictions, kill switches, provenance/audit |
| durable control stream | Retention, replay, consumer position, high watermark, gap recovery |
| trade-service/Gateway | Local replica updater, readiness, screening, submitted-command correlation |
| order-matcher Gateway | Same local screening contract for order API; no arbitrary symbol registration |
| BLP | Control state, idempotency, exact reservations, authoritative decision |
| output/observability | Decision response/diagnostic handling and new bounded metrics; topology unchanged |

The exact placement of risk administration may be a small module/service in the demo profile. Its
deployment location does not change the rule that command decisions use only local installed state.

## Demo / C2 Profile

- One instance of each existing application service.
- One active Gateway/BLP runtime; multiple-Gateway correctness exercised in component/integration tests.
- Durable control delivery may use a single-node retained stream suitable for local Compose.
- Snapshot endpoints and outboxes use inherited data stores where applicable.
- Blocking wait strategy and no core pinning inherited from `009b` demo defaults.
- All allocation gates still apply; performance latency budgets do not bind the container demo profile.

## Perf Profile

- Multiple Gateway instances may screen concurrently from independently maintained replicas.
- Active BLP plus inherited warm standby/replication model.
- Durable control stream configured for retained replicated delivery.
- BLP/critical consumers use inherited isolated cores and wait strategies.
- Tests deliberately create Gateway replica skew to prove authoritative BLP protection.

## Startup Order

1. Database/source stores and durable control stream become healthy.
2. Account/reference/risk control owners start and expose source epoch/high watermark.
3. Gateway replica consumers subscribe and begin buffering deltas.
4. Gateways fetch and verify watermarked snapshots, install, and apply buffered deltas.
5. Order-matcher loads BLP snapshot, replays the global journal, and applies control/price state.
6. Gateways reach control-stream high watermarks; BLP reaches journal high watermark.
7. JIT/no-GC warm-up completes.
8. Admission readiness becomes true.
9. UI/ingress may be live earlier for status/read-only diagnostics but risk-increasing admission remains
   unavailable until step 8.

## Health Model

| Condition | Liveness | Admission readiness | Behavior |
| --- | --- | --- | --- |
| Replica bootstrap incomplete | live | false | `503` risk-increasing admission |
| Mandatory version gap/epoch change | live | false | re-bootstrap; fail closed |
| Price stale/missing | live | policy/security-specific false | reject price-dependent risk increase |
| BLP replay/warm-up incomplete | live | false | no admission |
| Risk admin unavailable, installed policy proven | live | true | continue installed policy; no updates |
| Durable feed disconnected before stale deadline | live | true with alert | continue proven state until deadline |
| Durable feed beyond stale deadline | live | false | fail closed |
| DB/projector unavailable | live | inherited behavior | no admission bypass; projector lag visible |
| NATS output unavailable | live | inherited bounded behavior | output lag/backpressure contract applies |
| Capacity exhausted | live | may remain true | explicit `CAPACITY` reject/backpressure |

Cancel and explicitly policy-defined risk-reducing commands may remain available during selected
unready modes. This exception must be encoded/tested; it is not a generic fail-open toggle.

## Shutdown / Restart

- Stop new admission and drain in-flight correlated decisions.
- Snapshot BLP risk/order state at a known global sequence when configured.
- Persist durable consumer positions/checkpoints.
- On restart, never trust Gateway cache files as BLP authority.
- Resume readiness only after replay, replica catch-up, and warm-up.

## Persistent Data

- Inherited input journal, BLP snapshots, and projector checkpoint.
- Source transactional outbox/change-log state.
- Durable control-stream retention and consumer positions.
- Risk-policy/restriction/kill-switch authoritative source state and audit trail.
- No secrets in journal/snapshot/control payloads.

## Capacity Inputs

Configuration/preflight must validate maximum accounts, securities, entitlements, restrictions,
policies, open orders/reservations, idempotency records, bootstrap buffer, and stream retention window.
Invalid or undersized configuration fails startup rather than silently enabling unbounded allocation.

