# Feature Specification: Post-Trade Compliance Bundle

**Feature Branch**: `YU05-post-trade-compliance`
**Created**: 2026-07-06
**Status**: Implemented
**Input**: Combines four production-realism capabilities into one spec-kit state at the user's
request — post-trade settlement + reconciliation, regulatory reporting, TCA, and real
auth/entitlements. Parented on `YU04-durable-control-feeds` (lineage `YU03 → YU04 → YU05`). New
requirement namespace `PTC` (`FR-PTCxx`, `NFR-PTCxx`); per-requirement status in
`requirements/functional-delta.md` and `requirements/nonfunctional-delta.md`.

The four capabilities are three views over one underlying record — an executed fill (symbol, price,
qty, side, account, timestamp, deterministic trade id) sourced from the order-matcher journal —
plus the access-control layer that makes exposing that record as real reports/APIs safe. This state
is a back-office layer strictly downstream of the BLP: it never sits on the order-admission path and
never mutates journal or BLP state.

## User Stories

- As an operations user, I want a booked trade to move through a real settlement lifecycle
  (`Processing → Settled` on a T+N schedule, with a manual override) instead of being marked settled
  the instant it books.
- As an operations user, I want reconciliation to flag any trade that is in the journal but missing
  or mismatched in the projection — and, on demand, any projection row with no journal fill behind
  it at all — so a divergence between the authoritative journal and the read model is caught.
- As a compliance reviewer, I want a reproducible, journal-sourced audit export of every order/trade
  lifecycle event in an input-sequence range, so a regulator query can be answered byte-for-byte
  from the source of truth rather than the projection.
- As an execution analyst, I want per-trade transaction-cost analysis (arrival price, benchmark,
  signed slippage) so execution quality can be measured without touching the trading hot path.
- As a security owner, I want every one of these endpoints gated by a verified token and an
  entitlement check, so account-scoped data is only visible to an entitled caller and cross-account
  data only to an admin.

## Functional Requirements

- FR-PTC01: A MariaDB trade row SHALL carry the deterministic trade id derived from the journal fill
  that produced it (`OrderSnapshot.tradeIdFor(tradeSeq)`), on both the live projector path and the
  legacy NATS booking path.
- FR-PTC02: A booked trade SHALL start `Processing` with a computed settlement date and advance to
  `Settled` by a scheduled T+N sweep or a manual force.
- FR-PTC03: order-matcher SHALL maintain a replay-safe in-memory trade blotter, rebuilt from journal
  replay on recovery (no snapshot-format change).
- FR-PTC04: Reconciliation SHALL classify each blotter entry against the local trade row as
  `MATCHED`, `MISSING_IN_PROJECTION`, or `FIELD_MISMATCH`, and (full-history sweep)
  `ORPHAN_IN_PROJECTION`.
- FR-PTC05: Reconciliation SHALL expose a status summary (`GET /recon/status`) and bounded Prometheus
  counters with no per-trade labels.
- FR-PTC06: The settlement date SHALL default to T+1 business day (`settlement.t-plus-days`) and be
  overridable via `POST /trades/{id}/settlement/force`.
- FR-PTC07: Settlement and reconciliation SHALL never mutate journal or BLP state; their writes are
  MariaDB-side only, and full-history reindex and regulatory reports are read-only shadow replays.
- FR-PTC08: Trade booking SHALL be idempotent on the deterministic trade id — a duplicate delivery
  is a no-op.
- FR-PTC10: A full-history sweep SHALL replay the entire journal on demand (`POST
  /recon/full-history/reindex`) into an unbounded index and cross-check every local trade id
  (`POST /recon/orphan-sweep`), flagging `ORPHAN_IN_PROJECTION` with full confidence.
- FR-PTC20: `GET /regulatory/report?fromSeq=&toSeq=` SHALL return every order/trade lifecycle event
  (accept, reject, partial-fill, fill, cancel, trade-booked) in that input-sequence range, sourced
  from journal replay, never from the MariaDB projection.
- FR-PTC21: The audit export SHALL be a pure function of (journal range, seed) — no wall-clock, no
  external query — so identical inputs always produce identical records.
- FR-PTC22: The audit export SHALL require an admin caller and never run on the BLP admission path.
- FR-PTC30: TCA SHALL compute arrival price, a TWAP benchmark, and signed slippage-in-bps per trade
  (`GET /tca/report/{tradeId}`).
- FR-PTC31: TCA SHALL be read-side only, entirely in trade-processor, never calling the admission
  path.
- FR-PTC32: TCA SHALL compute its benchmark against a pluggable historical price source — a
  `PriceHistoryStore` fed by price-publisher's existing `pricing.*` feed — with no new data source
  and no BLP involvement.
- FR-PTC40: Every endpoint this state adds SHALL require a real HS256-verified JWT (`JwtAuthenticator`,
  JDK crypto only), replacing the shared-token stopgap; `POST /auth/dev-token` mints tokens for local
  dev/testing.
- FR-PTC41: Account-scoped endpoints (settlement force, TCA) SHALL check the caller's entitlement
  against the trade's own account; cross-account endpoints (blotter, full-history, orphan-sweep,
  regulatory report) SHALL require an `admin` claim.

## Non-Functional Requirements

- NFR-PTC01: Blotter capture SHALL happen on the existing output-ring consumer thread, off the BLP
  decision path; recon and settlement SHALL make no synchronous call into the admission path.
- NFR-PTC02: Reconciliation classification SHALL be a pure function of (blotter state, DB state) at
  sweep time and reproduce the same result on an unchanged rerun.
- NFR-PTC03: Metric cardinality SHALL stay bounded — classification labels only, no
  trade-id/account/security labels.
- NFR-PTC05: The live blotter SHALL be bounded (`recon.blotter.capacity`, default 500,000) with
  oldest-first eviction; the on-demand full-history index is unbounded but populated only on demand.
- NFR-PTC06: This state SHALL touch order-matcher and trade-processor overrides only; the inherited
  build/publish/deploy harness is unchanged.
- NFR-PTC07: No new runtime dependency SHALL be added (JDK crypto/HttpClient, existing
  Jackson/Micrometer/Spring).
- NFR-PTC09: No file in this state's scope SHALL modify order submission/admission
  (`OrderMatcherService`, `GatewayReplicaStore`, `BlpRiskState`), verified by review.

## Success Criteria

- SC-PTC01: order-matcher unit tests validate the replay-safe blotter (capture, bounded eviction,
  replay-safety), the deterministic-id wiring, the audit-log handler (kind coverage, range filtering),
  and the JWT authenticator.
- SC-PTC02: trade-processor unit tests validate idempotent booking, the T+N settlement transition,
  reconciliation classification, the full-history orphan sweep, TWAP/slippage computation, and the
  JWT authenticator.
- SC-PTC03: A Grafana dashboard (`traderx-post-trade-compliance.json`) visualizes the recon,
  settlement, TCA, and regulatory-report metric set.
- SC-PTC04: `bash pipeline/generate-state.sh YU05-post-trade-compliance` exits 0 and the generated
  order-matcher/trade-processor trees test green.
- SC-PTC05: Generated shared files retain every ancestor state's content alongside this state's
  additions.
