# Functional Delta: in-memory-risk-gateway

Parent state: `009b-lmax-sequencer-architecture`

This document records functional changes introduced after `009b`. Accepted business events retain the
existing external order/trade/position behavior; admission gains explicit replica, risk, idempotency,
and rejection semantics.

## Added

- Versioned Gateway replicas for security identity/status, account status, principal entitlements,
  restrictions, risk limits, kill switches, and price freshness (FR-IMRG02..FR-IMRG05).
- Gap-free bootstrap protocol: subscribe and buffer deltas, fetch a complete snapshot at watermark
  `W`, atomically install, apply versions above `W`, then report ready (FR-IMRG03..FR-IMRG05).
- Local Gateway screening for entitlement, security status, restrictions, kill switches, price
  freshness, order bounds, and obvious limit failures (FR-IMRG06).
- Sequenced control events for account, entitlement, security, restriction, policy, and kill-switch
  changes, ordered with commands and prices (FR-IMRG10..FR-IMRG12).
- Authoritative BLP aggregate-risk checks against exact positions, open-order reservations, and policy
  effective at the command sequence (FR-IMRG12..FR-IMRG13).
- Required `clientOrderId`, bounded idempotency state, and original-decision replay on retry
  (FR-IMRG14).
- Stable decision contract carrying decision/reason, command sequence, policy version, and control
  versions (FR-IMRG15).
- Exposure reservation lifecycle: reserve on acceptance, convert on fill, release on cancel/reject/
  expiry, exactly once (FR-IMRG13, FR-IMRG16).
- Explicit fail-closed, degraded-mode, control-gap, and risk-reducing-operation rules
  (FR-IMRG18, FR-IMRG34..FR-IMRG35).
- Authenticated/versioned risk administration for limits, restrictions, and kill switches
  (FR-IMRG30..FR-IMRG31).
- Gateway/BLP mismatch audit and telemetry (FR-IMRG19).

## Changed

- `trade-service` and order Gateway validation changes from blocking reference/account REST lookups and
  shape-only checks to local replica screening (FR-IMRG01, FR-IMRG06).
- Symbol registration changes from first-seen client ticker to reference-data-authoritative assignment
  (FR-IMRG08).
- `ORDER_NEW`/`TRADE_NEW` admission becomes submitted-command then authoritative accepted/rejected
  decision. Only accepted commands become executable (FR-IMRG10..FR-IMRG15).
- Synchronous success moves from "transport/forward succeeded" to "BLP accepted"; risk failures use a
  stable 4xx body. Async admission, if added, uses `202` (FR-IMRG20).
- Price handling changes from implicit/zero fallback to explicit known/missing/stale state with source
  timestamp and version (FR-IMRG09, FR-IMRG17).
- BLP snapshots expand to include policies, control versions, reservations, idempotency, and price
  freshness (FR-IMRG21..FR-IMRG22).
- Policy updates affecting resting orders must carry an explicit retain/reduce-only/cancel treatment
  rather than silently mutating the book (FR-IMRG24).

## Removed

- Blocking `GET /stocks/{ticker}` and `GET /account/{id}` from the market-trade admission path.
- Any direct account/reference/price/risk REST, JPA, JDBC, or database lookup from order admission.
- Client-driven symbol-table insertion.
- Numeric zero as the representation of missing validation price.
- Gateway-only aggregate-risk authority.
- Generic fail-open behavior for risk-increasing commands.

## Preserved

- `009b` global sequencing, journal-before-BLP gating, input/output rings, matching policy, fused
  booking/position state, output handler topology, NATS subjects, projector, and relational read model.
- Accepted order/trade/position payloads and UI journeys, except for the required `clientOrderId` on
  new admission (FR-IMRG40..FR-IMRG45).
- Cancel and operationally approved risk-reducing paths during degraded admission, subject to explicit
  policy rather than a generic bypass.

## Flow Impact

- **Order submission:** client -> Gateway local screen -> sequenced `ORDER_SUBMITTED` -> BLP check and
  reserve -> `OrderAccepted|OrderRejected` -> inherited output fan-out.
- **Market trade:** client -> trade-service local screen -> sequenced `TRADE_SUBMITTED` -> BLP decision
  and booking -> inherited trade/position outputs.
- **Control update:** source transaction/outbox -> durable control stream -> Gateway replica update and
  sequenced BLP control event -> policy/control state effective at a known sequence.
- **Startup:** subscribe/buffer -> snapshot at `W` -> apply `>W` -> high-watermark catch-up -> readiness.
- **Recovery:** BLP snapshot -> journal replay of controls/prices/commands -> warm-up -> live admission.

