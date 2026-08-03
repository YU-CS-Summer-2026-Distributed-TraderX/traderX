# Functional Delta: YU07-historical-tick-store over YU06-eod-price-production

The deployment harness, observability stack and existing services carry over from
`YU06-eod-price-production` — no existing publisher, consumer, or the order-matching hot path is
touched; the one existing-service edit is an `order-matcher` forward-port of `YU05`'s
`EntitlementGate`, restoring parity with YU05/YU06 rather than changing matching behavior. This
state adds one new component, `tick-store`, which listens to market data TraderX already broadcasts
and keeps it, alongside normalized third-party NYSE TAQ data, in a single columnar store that can be
queried by symbol and date range.

## Added

- A `tick-store` component that subscribes to the existing `pricing.*` and `/accounts/*/trades` NATS
  subjects and records every message published on them (`capture.py`).
- Capture is an extra subscriber on broadcast subjects that carry no ack back to a publisher, so a
  capture outage applies no backpressure and cannot slow order matching.
- A Parquet store partitioned as `source=<live|taq>/dt=<date>/symbol=<SYM>/`, ZSTD-compressed and
  written through DuckDB, so queries prune to just the symbols and dates they ask for.
- A unified row schema carrying `source`, `event_type` and `symbol` on every row, so live-captured
  ticks and TAQ-ingested rows stay distinguishable in any query.
- `ingest_taq_quotes.py`, a normalizer that turns a NYSE Daily TAQ Consolidated Quotes (CQ) CSV into
  exactly the same schema and partition layout as live capture.
- `ingest_taq_trades.py`, the equivalent normalizer for TAQ Consolidated Trades (CT) files, verified
  against real CT sample rows before shipping.
- Ingestion that streams a source CSV straight out of its zip archive, `unzip -p` piped into
  DuckDB's `/dev/stdin` read, so nothing decompressed lands on disk.
- Peak ingestion disk is one output Parquet partition rather than a day's ~76 GiB decompressed CSV,
  or terabytes of scratch across a whole batch.
- A container-bundled `stage2_ingest.sh` driver that dispatches any batch of `_raw-taq` zips to the
  matching normalizer, streamed from GCS without touching a pod's local disk.
- Bulk runs as a Kubernetes Indexed Job (`tick-store-stage2`), one file per pod for
  coordination-free parallelism; end-to-end verification at full production scale stays open.
- A DuckDB query recipe (`duckdb_query_examples.sql`) covering VWAP, daily-return, spread and
  inventory queries that read live and TAQ rows together in one `read_parquet(..., hive_partitioning=true)`.
- Optional Google Cloud Storage output: a `gs://` destination path configures DuckDB's native `gcs`
  secret from HMAC environment variables, letting the store run with no attached volume.

## Added later — the KDB-X analytical layer

Added after this state's original implementation; see the addendum in `spec.md`. It reads the
corpus above rather than changing it — the Parquet layout, schema and ingestion contract are
untouched.

- A KDB-X layer (`kdb/tickstore.q`) that maps the existing ZSTD Parquet objects as a
  date/symbol-partitioned virtual table. There is no conversion step and no second copy: the store
  *is* the corpus, and partition columns come from the path the ingest already wrote.
- `quote` and `trade` for the market tape, `txOrder` and `txTrade` (`kdb/txstore.q`) for TraderX's
  own flow. The two never share a table name, because one `trade` table holding both is how a VWAP
  ends up silently answering a question nobody asked.
- Query verbs over the tape — `.ts.vwap`, `.ts.spread`, `.ts.session`, `.ts.replay` — and over our
  own flow — `.tx.fills`, `.tx.orders`, `.tx.gaps`, `.tx.replay`.
- Duplicate collapse at load: re-ingest wrote new UUID-named files beside the old ones, so one file
  per distinct byte size is kept within each `(dt;symbol;kind)` group, and what was dropped is
  printed. An *unequal*-sized pair is a partial write, not a duplicate, so it is kept and flagged.
- `kdb/selfcheck.q` — 17 gates over the tape, every expected value computed independently in DuckDB
  over the same files, so the store is checked against something other than itself.
- `kdb/txselfcheck.q` — 18 gates over the session store, run against a fixture the cluster itself
  wrote under real consensus, needing no cluster, corpus or network.
- The leader-side capture tap that produces `txOrder`/`txTrade` is `KdbTapWriter` and ships in the
  `YU13-limit-order-book` layer, since it lives in the clustered `order-matcher`. Its rows are
  epoch-qualified, and a security whose ticker was never registered is captured under a synthetic
  identifier rather than dropped.
