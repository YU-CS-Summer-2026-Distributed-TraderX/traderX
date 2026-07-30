# Functional Delta: YU04-durable-control-feeds (vs YU03-in-memory-risk-gateway)

Everything YU03 established about *deciding* on an order is carried forward untouched: the two-tier
Gateway plus business-logic-processor admission pipeline, order screening, the control-plane admin
API's request and response shapes, idempotency and reservation mechanics, and the journal, replication
and snapshot formats. This state changes only how the Gateway's replica of account and security
control state gets populated and kept current at the edge — replacing YU03's one-shot REST fetch with
durable, versioned feeds published from the source services themselves. Running more than one Gateway
concurrently remains out of scope.

## Added

- Transactional outbox in `account-service` and `reference-data`: each control change is written in the
  same transaction as the business record, so the feed can never diverge from the source of truth.
- Two durable NATS JetStream streams of versioned control deltas, `TRADERX_CONTROL_ACCOUNT` and
  `TRADERX_CONTROL_SECURITY`, kept separate so a fault on one source never disturbs the other.
- Retention and replay on those streams, so a control change made while a replica is briefly offline is
  still delivered once it reconnects rather than silently lost.
- Watermarked snapshot endpoints `GET /account/control-snapshot` and `GET /stocks/control-snapshot`,
  each carrying schema version, source epoch, watermark, record count and a checksum.
- A five-step bootstrap per source in `order-matcher`: subscribe and buffer, fetch the snapshot, verify
  and atomically install it, apply buffered deltas above the watermark, then consume live.
- Real per-source epoch and monotonic version on every control record, so the replica can tell a gap, a
  version regression and a deliberate resync apart without a separate sequencing service.
- Quarantine and automatic re-bootstrap: a gap, regression, epoch change or failed checksum stops
  applying that one source's deltas and restarts its bootstrap, leaving the other source running.
- Per-source observability — `traderx_replica_source_watermark` and `traderx_replica_quarantine_total`,
  the latter labelled by source and reason — so operators can see how far behind a replica is.
- Stable `Nats-Msg-Id` values (`account:<version>`, `security:<version>`) so a redelivered or
  re-published outbox row is de-duplicated by JetStream rather than double-applied.

## Changed

- Gateway readiness now requires *both* sources to have installed a valid snapshot and caught up to the
  high watermark observed at subscribe time; a one-source-only bootstrap no longer counts as ready.
- The fail-closed path is triggered by feed loss as well — subscribe failure, snapshot fetch failure or
  checksum mismatch all revoke readiness, alongside the pre-bootstrap and stale-price cases YU03 had.
- `reference-data` gained MariaDB-backed persistence and its first write path (`POST /stocks`); its CSV
  file becomes a one-time idempotent seed instead of the service's only source of stock data.
- The startup and degraded-mode matrix gained rows for subscribe failure, snapshot checksum mismatch and
  quarantine/re-bootstrap. It remains partial: there is still no generic fail-open path.
