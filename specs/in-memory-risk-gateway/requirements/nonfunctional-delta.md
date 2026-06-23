# Non-Functional Delta: in-memory-risk-gateway

Parent state: `009b-lmax-sequencer-architecture`

## Runtime / Operations

- The runtime inherits the `009b` stack. Account-service, reference-data, and risk administration add
  versioned control publication and watermarked snapshot surfaces. Gateways add local replica updaters;
  order-matcher adds BLP risk/control state.
- Admission readiness is stricter than liveness. A node remains live for diagnostics but unready for
  risk-increasing commands until mandatory replicas and BLP replay are complete (NFR-IMRG06).
- Durable control consumers persist/restore consumer positions or prove catch-up from snapshot
  watermark plus retained deltas. Epoch change or version gap forces re-bootstrap.
- The output-disruptor and projector runtime topology remains unchanged (FR-IMRG45).
- All capacities are explicit and bounded: maximum accounts, securities, restrictions, policies,
  idempotency records, and open-order reservations.

## Security / Control Baseline

- Risk policies, restrictions, and kill switches require authenticated transport, authorized source
  identity, monotonic version, operator/provenance id, and audit record (FR-IMRG30).
- Client `accountId` is never sufficient authorization; the authenticated principal must be entitled to
  act for that account (FR-IMRG06).
- Replica snapshots, deltas, BLP journal records, and audit diagnostics are sensitive trading/control
  data and inherit the database/journal trust boundary. Credentials and bearer tokens are excluded.
- SEC Rule 15c3-5 informs the control inventory only. Legal applicability, supervisory procedures,
  annual review, access control governance, and certification remain outside this state.

## Performance / Scalability

Performance-profile budgets:

| Stage | p99 budget | Notes |
| --- | --- | --- |
| Gateway replica lookup + screen | `< 25 us` | No remote lookup; primitive/pre-sized state |
| Input claim + submitted-command encode | inherited `< 5 us` | Existing `009b` ring |
| BLP validation + reserve + decision emit | `< 25 us` | Single writer, stable check order |
| Gateway ingest -> decision/output emit | inherited `< 150 us` | Excludes external network and durable ack |
| Control-event apply in BLP | `< 25 us` | Prevalidated/versioned update |

- Multiple Gateways scale preliminary screening horizontally without coordinating with each other.
  Aggregate correctness remains serialized by the active BLP (FR-IMRG25).
- No-GC constraints apply to Gateway screen, control decode/apply, BLP decision/reservation, and decision
  output. See `requirements/no-gc-conformance.md`.
- Report HdrHistogram p50/p99/p99.9/max, never averages only. Compare with the same environment,
  profile, fixture, and event mix used for `009b`.

## Reliability / Recovery

- Snapshot/subscription handoff must be gap-free. TTL is a silence detector, not proof of completeness.
- The BLP journal remains authoritative for decisions. Gateway replicas are disposable caches and are
  never used as the BLP recovery source.
- Control-stream loss or stale mandatory state fails closed for risk-increasing commands while keeping
  health/diagnostics and explicitly safe control/cancel paths available.
- Replay must not query current account/reference/risk services because doing so could change a historic
  decision (NFR-IMRG03).
- Invalid updates retain the last proven version, emit diagnostics, and block affected admission where
  correctness is uncertain.

## Observability Contract

Required metrics in addition to inherited `009b` families:

| Metric | Type | Meaning / cardinality rule |
| --- | --- | --- |
| `traderx_gateway_validation_latency_seconds` | histogram | Local Gateway screen duration |
| `traderx_gateway_rejections_total{reason}` | counter | Stable bounded reason enum only |
| `traderx_replica_source_version{replica}` | gauge | Last applied version per replica type |
| `traderx_replica_high_watermark{replica}` | gauge | Observed durable high watermark |
| `traderx_replica_lag{replica}` | gauge | High watermark minus applied version |
| `traderx_replica_ready{replica}` | gauge | `1` only when snapshot and catch-up complete |
| `traderx_replica_gap_total{replica}` | counter | Version/epoch gaps detected |
| `traderx_replica_rebootstrap_total{replica,reason}` | counter | Bounded re-bootstrap reason |
| `traderx_risk_decision_latency_seconds` | histogram | BLP check + reserve + decision emit |
| `traderx_risk_decisions_total{decision,reason}` | counter | Bounded decision/reason enums |
| `traderx_risk_reserved_notional_total` | gauge | Platform aggregate; account detail excluded |
| `traderx_risk_policy_version` | gauge | Active installed policy version |
| `traderx_gateway_blp_mismatch_total{reason}` | counter | Preliminary/final disagreement category |
| `traderx_idempotency_duplicate_total` | counter | Duplicate commands returning prior decision |
| `traderx_control_update_rejected_total{type,reason}` | counter | Invalid/gapped control updates |

Account, principal, security, `clientOrderId`, and command sequence SHALL NOT be Prometheus labels.
Detailed correlation belongs in sampled/rate-limited structured audit records.

## Grafana / Alerting

- Replica readiness, lag, source/high watermarks, gaps, and re-bootstrap counts.
- Gateway screen and BLP decision p50/p99/p99.9/max.
- Decisions/rejections by stable reason and Gateway/BLP mismatch rate.
- Active policy version and control-update rejection rate.
- Existing input/output ring headroom, projector lag, allocation, and GC panels retained.
- Alerts: mandatory replica unready, version gap, stale price/control feed, mismatch spike, policy-update
  rejection, risk-decision latency budget breach, and any steady-state hot-path allocation.

## Compatibility / Delivery

- `C2` image namespace, immutable SHA tags, `latest`, GHCR run bundle, deployment bundle, LGTM stack,
  and dependency gates carry forward unchanged through `009b` (NFR-IMRG11).
- The UI metadata changes only to the new state id. Accepted business payloads and realtime subjects
  remain compatible (FR-IMRG42, FR-IMRG44).

