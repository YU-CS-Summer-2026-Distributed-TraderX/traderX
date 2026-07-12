# Contract Delta: YU07 over YU06-eod-price-production

All existing order/trade/position/risk/post-trade/EOD REST + NATS + UI contracts are retained. Every
delta below is additive; `tick-store` exposes no HTTP surface in this state (no REST API, no new
NATS publish subjects — it is a consumer/CLI only).

## 1. NATS subscriptions (new consumer, `tick-store`)

| Subject | Delivery | Effect |
|---|---|---|
| `pricing.*` | broadcast, wildcard | Every price tick is written to `gs://traderx-501015-tick-store/ticks/source=live/dt=.../symbol=.../` as an `event_type='price_tick'` row. |
| `/accounts/*/trades` | broadcast, wildcard | Every trade fill is written to the same tree as an `event_type='trade'` row. |

No subject is published by `tick-store`; no existing publisher or consumer of either subject is
modified.

## 2. TAQ ingestion CLI (new, `tick-store`)

```
unzip -p <taq_quotes_YYYYMMDD_csv.zip> <entry>.csv | python3 ingest_taq_quotes.py --date YYYY-MM-DD --out gs://traderx-501015-tick-store/ticks
```

Reads the confirmed TAQ CQ CSV layout from stdin, writes
`gs://traderx-501015-tick-store/ticks/source=taq/dt=YYYY-MM-DD/symbol=.../` partitions. A row missing `symbol`/`date`/`time`
is excluded from the write rather than aborting the run (verified: 1 malformed row among 3 does not
block the other 2); zero valid rows parsed, or a column layout that doesn't match the confirmed CQ
header, → non-zero exit with a logged error.

## 3. Query recipe (new, DuckDB over Parquet, no server)

`duckdb_query_examples.sql` — run directly against the store root with the DuckDB CLI or Python
API, e.g.:

```sql
SELECT symbol, dt, sum(price * size) / sum(size) AS vwap
FROM read_parquet('gs://traderx-501015-tick-store/ticks/**/*.parquet', hive_partitioning = true)
WHERE event_type = 'trade' AND symbol = 'IBM' AND dt BETWEEN 'YYYY-MM-DD' AND 'YYYY-MM-DD'
GROUP BY symbol, dt;
```

No new network-facing endpoint; this is a file-based analytical query, run from wherever the
Parquet store is mounted or synced.

## Not changed

Order/trade/position/risk/EOD payload shapes and subjects, matching policy, YU05's
settlement/recon/TCA/regulatory APIs, YU06's EOD chain, the BLP hot path, UI journeys (no
front-end tick-store panel in v1 — query is DuckDB-only).
