# Data Model: YU07 — Historical Tick Store

## Storage layout

Parquet files under a single root (`gs://traderx-501015-tick-store/ticks`, GCS Standard tier —
research.md Decision 6), Hive-partitioned:

```
gs://traderx-501015-tick-store/ticks/source=<live|taq>/dt=<YYYY-MM-DD>/symbol=<SYM>/part-*.parquet
```

Read back with DuckDB's `hive_partitioning=true`, which reconstructs `source`, `dt`, `symbol` as
query-able columns from the path — partition pruning applies directly to date-range and
symbol-filtered queries without a separate index.

## Unified `ticks` schema (every Parquet file, both `source` values)

| Column | Type | Notes |
|---|---|---|
| `symbol` | VARCHAR | Ticker (`SYM_ROOT` for TAQ; the NATS subject's ticker for live). |
| `event_type` | VARCHAR | `price_tick` (live `pricing.*`) / `trade` (live account trades) / `quote` (TAQ CQ). |
| `ts` | TIMESTAMP | Event time, microsecond precision. For TAQ, `DATE`+`TIME_M` truncated from nanoseconds (research.md Decision 4). |
| `price` | DOUBLE, nullable | Trade/price-tick price. Null for `quote` rows. |
| `size` | BIGINT, nullable | Trade quantity. Null for `price_tick`/`quote` rows. |
| `bid_price` | DOUBLE, nullable | TAQ `BID`. Null outside `quote` rows. |
| `bid_size` | BIGINT, nullable | TAQ `BIDSIZ`. |
| `ask_price` | DOUBLE, nullable | TAQ `ASK`. |
| `ask_size` | BIGINT, nullable | TAQ `ASKSIZ`. |
| `venue` | VARCHAR | TAQ `EX` (per-venue quote source); `TRADERX` for every live row. |
| `source` | VARCHAR | `live` / `taq` — also the top partition level. |
| `seq` | BIGINT | TAQ `QU_SEQNUM`; a per-process monotonic counter for live rows (ordering within a capture session, not comparable across sources). |
| `ingested_at` | TIMESTAMP | Capture/ingestion wall-clock (audit only, not an event-time field). |

## Source → schema mapping

### Live capture — `pricing.<TICKER>` (NATS, wildcard `pricing.*`)

Payload: `{price, openPrice, closePrice, asOf, source}` (`price-publisher/src/main.js`).

| Source field | → | Column |
|---|---|---|
| ticker (parsed from subject) | → | `symbol` |
| `price` | → | `price` |
| `asOf` (ISO-8601) | → | `ts` |
| — | → | `event_type='price_tick'`, `venue='TRADERX'`, `source='live'` |

### Live capture — `/accounts/<accountId>/trades` (NATS, wildcard)

Payload: `{id, accountId, security, side, state, quantity, price, updated, created, settlementDate}`
(`Trade` entity, `trade-processor`).

| Source field | → | Column |
|---|---|---|
| `security` | → | `symbol` |
| `price` | → | `price` |
| `quantity` | → | `size` |
| `updated` | → | `ts` |
| — | → | `event_type='trade'`, `venue='TRADERX'`, `source='live'` |

### TAQ quotes CSV (`taq_quotes_YYYYMMDD_csv.zip`, confirmed format)

Header: `DATE,TIME_M,EX,BID,BIDSIZ,ASK,ASKSIZ,QU_COND,QU_SEQNUM,NATBBO_IND,QU_CANCEL,QU_SOURCE,SYM_ROOT,SYM_SUFFIX`

| Source column | → | Column |
|---|---|---|
| `SYM_ROOT` | → | `symbol` |
| `DATE` + `TIME_M` (truncated to µs) | → | `ts` |
| `BID` | → | `bid_price` |
| `BIDSIZ` | → | `bid_size` |
| `ASK` | → | `ask_price` |
| `ASKSIZ` | → | `ask_size` |
| `EX` | → | `venue` |
| `QU_SEQNUM` | → | `seq` |
| — | → | `event_type='quote'`, `source='taq'` |

`QU_COND`, `NATBBO_IND`, `QU_CANCEL`, `QU_SOURCE`, `SYM_SUFFIX` are read but not carried into the
unified schema in this state — none of VWAP or return/scenario aggregation needs them, and every
byte read from the source CSV is still available by re-running ingestion against the same file if a
future consumer needs one.

## Config (namespace `tickstore.*`, environment variables)

| Key | Default | Meaning |
|---|---|---|
| `TICKSTORE_NATS_URL` | `nats://nats-broker:4222` | Broker connection for `capture.py`. |
| `TICKSTORE_OUT_DIR` | `gs://traderx-501015-tick-store/ticks` | Parquet store root (capture and ingestion both write here). A local path also works unchanged (`gcs.py`'s `is_gcs_path` gates the GCS setup, opt-in on the `gs://` prefix). |
| `TICKSTORE_FLUSH_INTERVAL_SECONDS` | `30` | Capture batch flush cadence. |
| `TICKSTORE_FLUSH_MAX_ROWS` | `5000` | Capture batch flush size trigger (whichever of interval/rows hits first). |
| `GCS_HMAC_KEY_ID` | (none — required when `TICKSTORE_OUT_DIR` is `gs://`) | HMAC access key ID for the bucket-scoped `tick-store-gcs` service account (research.md Decision 6). Sourced from the `tick-store-gcs-hmac` k8s Secret, never committed. |
| `GCS_HMAC_SECRET_ACCESS_KEY` | (none — required when `TICKSTORE_OUT_DIR` is `gs://`) | HMAC secret for the same credential. |

## Reused, unchanged

- `pricing.<TICKER>` / `/accounts/<accountId>/trades` NATS subjects — no publisher change.
- NATS broker (`nats-broker`) — same connection every other consumer uses.
