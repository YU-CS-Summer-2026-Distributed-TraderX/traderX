# Functional Delta: YU05-post-trade-compliance (vs YU04-durable-control-feeds)

The parent's BLP decision path and risk-screening logic carry forward unchanged, as do the journal
and replication wire format, the snapshot format, and the inherited build, deploy and observability
harness. The one admission-path change is the entitlement gate below; everything else this state
adds is a back-office compliance layer downstream of the trading path — settlement, reconciliation,
regulatory reporting and transaction-cost analysis, all reading the journal's executed-fill stream,
plus a real token-based access-control layer over the endpoints that expose them. Only order-matcher
and trade-processor change; new requirements use the `PTC` namespace.

## Added

- Deterministic trade identity: every MariaDB trade row carries the id derived from the journal fill
  that produced it, which makes booking idempotent — a duplicate delivery of the same trade is a
  no-op instead of a second row.
- A replay-safe in-memory trade blotter in order-matcher, rebuilt from journal replay on recovery,
  giving reconciliation a journal-side view of every fill with no snapshot-format change.
- Reconciliation that classifies each trade as `MATCHED`, `MISSING_IN_PROJECTION` or
  `FIELD_MISMATCH`, so divergence between the authoritative journal and the read model is caught.
- Reconciliation reporting through a `GET /recon/status` summary and Prometheus counters labelled
  only by classification, keeping metric cardinality bounded no matter how many trades run through.
- An on-demand full-history sweep (`POST /recon/full-history/reindex`, then `POST
  /recon/orphan-sweep`) that cross-checks every projected row against a full journal replay, flagging
  `ORPHAN_IN_PROJECTION` where no journal fill exists.
- Settlement and reconciliation writes that land in MariaDB only, never mutating journal or BLP
  state, so the compliance layer cannot corrupt the record it audits.
- The full-history reindex and the regulatory export as read-only shadow replays that never touch the
  live BLP or journal, which is what keeps an expensive full replay safe.
- A journal-sourced regulatory audit export (`GET /regulatory/report?fromSeq=&toSeq=`) covering every
  order and trade lifecycle event in a sequence range, and reproducible byte-for-byte because it is a
  pure function of the journal range and seed rather than of the projection or the wall clock.
- The audit export restricted to an `admin` caller and never run on the BLP admission path, so
  answering a regulator query costs the trading path nothing.
- Per-trade transaction-cost analysis (`GET /tca/report/{tradeId}`) reporting arrival price, a TWAP
  benchmark and signed slippage in basis points, computed read-side entirely inside trade-processor
  so execution quality can be measured without touching the trading hot path. The benchmark source is
  pluggable and currently fed by the existing price feed; VWAP is not implemented, as that feed
  carries no per-tick volume (FR-PTC32, partial).
- Real HS256-verified JWT authentication on every endpoint this state adds, replacing the
  shared-token stopgap: account-scoped endpoints check the caller against the trade's own account,
  and cross-account endpoints require an `admin` claim.
- An entitlement gate on order admission that rejects a caller not entitled to the order's account
  before the command is screened or sequenced.
- The gate as a memory-only check against the token claim, adding no synchronous lookup to admission
  and closing the parent's entitlement-resolution and command-path authentication gaps.
- Enforcement of that gate kept behind `risk.entitlement.enforced` (default off) so the token-less UI
  keeps working, with the admission-path wiring still listed as open in this state's implementation
  status.
- Settlement and reconciliation that never mutate journal or BLP state: their writes are MariaDB-side
  only, and full-history reindex and regulatory reports run as read-only shadow replays, so the
  post-trade layer can never perturb the trading path it reports on.

## Changed

- A booked trade no longer lands as `Settled` the instant it books: the projector writes `Processing`
  with a real settlement date, defaulting to T+1 business day, and a scheduled T+N sweep or the
  `POST /trades/{id}/settlement/force` override advances it to `Settled`.
- The legacy NATS booking path derives its trade id from the fill that produced it instead of minting
  a fresh random one, so both write paths agree on the same identity.
