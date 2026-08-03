# Functional Delta: YU06-eod-price-production (vs YU05-post-trade-compliance)

Everything from `YU05-post-trade-compliance` is carried forward unchanged — order matching, the
post-trade recon/TCA read side, the admin JWT authentication, and the deploy and observability
harness. What this state adds is an end-of-day layer: an official, versioned closing price per
instrument for each trading session, a durable event announcing that the prices are ready, and one
real overnight job that consumes them. It runs entirely on the read side, so the order-matching hot
path and its event schema are untouched.

## Added

- Session-close trigger `POST /eod/session/close?sessionDate=` in `trade-processor`, called both by a
  Kubernetes CronJob and by an operator on demand through the same code path.
- Official closing price per instrument, defined as the newest last-trade sample at or before the
  close from the existing `pricing.*` price feed — no new market-data source.
- Versioned, immutable closing-price snapshot (`eod_price_session`, `eod_price_snapshot`) keyed by
  session date and version; re-running production writes a new `DRAFT` and never edits a `PUBLISHED` one.
- Data-quality classification of every price as `OK`, `STALE`, `SPIKE` or `MISSING`, from configured
  staleness and maximum-move thresholds plus an expected-instrument universe (`eod.universe`).
- Manual override endpoint `POST /eod/prices/{date}/override` that records a corrected price and its
  reason as a new version, so a bad price is resolved without touching what was already published.
- Admin-only EOD control surface: every `/eod/*` endpoint requires an authenticated `admin` caller,
  reusing the JWT mechanism inherited from `YU05-post-trade-compliance` rather than adding a new one.
- Publication fail-safe: `POST /eod/prices/{date}/publish` returns `409` while any instrument is an
  unresolved `STALE`, `SPIKE` or `MISSING`; a session that closes clean auto-publishes.
- Durable `EOD_PRICES_READY` event on JetStream subject `eod.prices.ready`, emitted only after the
  version is fully committed, so a consumer that was offline still receives it on reconnect.
- Downstream EOD consumer in `position-service` that marks positions against the exact
  `(sessionDate, version)` the event names rather than live ticks, and holds an entire account back
  when one of its holdings is missing or flagged instead of guessing a price.
- Chain-completion event `eod.pnl.done`, plus Micrometer counters for quality flags, sessions
  published and accounts marked or halted, surfaced in a `traderx-eod-batch-chain` Grafana dashboard.
