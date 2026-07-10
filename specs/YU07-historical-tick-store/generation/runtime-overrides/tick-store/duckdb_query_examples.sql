-- DuckDB query recipe over the unified tick-store Parquet schema (data-model.md).
-- Requires the tick-store-gcs HMAC secret registered first (see quickstart.md's "Query the store").
-- Swap the gs://traderx-501015-tick-store/ticks path for a local one if querying a local capture.
--
-- Run individual statements with the DuckDB CLI:
--   duckdb -c "<statement>"
-- or from Python: duckdb.sql(open('duckdb_query_examples.sql').read())

-- 1. VWAP over a symbol/date range, across live capture and TAQ uniformly.
--    (event_type='trade' rows only -- price_tick/quote rows have no size to weight by.)
SELECT
    symbol,
    dt,
    sum(price * size) / sum(size) AS vwap,
    sum(size) AS total_volume,
    count(*) AS trade_count
FROM read_parquet('gs://traderx-501015-tick-store/ticks/**/*.parquet', hive_partitioning = true)
WHERE event_type = 'trade'
  AND symbol = 'IBM'
  AND dt BETWEEN DATE '2025-02-01' AND DATE '2025-02-28'
GROUP BY symbol, dt
ORDER BY dt;

-- 2. Simple daily return series from trade prices (last trade price per day as the day's close).
WITH daily_close AS (
    SELECT
        symbol,
        dt,
        arg_max(price, ts) AS close_price
    FROM read_parquet('gs://traderx-501015-tick-store/ticks/**/*.parquet', hive_partitioning = true)
    WHERE event_type = 'trade' AND symbol = 'IBM'
    GROUP BY symbol, dt
)
SELECT
    symbol,
    dt,
    close_price,
    close_price / lag(close_price) OVER (PARTITION BY symbol ORDER BY dt) - 1 AS daily_return
FROM daily_close
ORDER BY dt;

-- 3. Bid/ask spread from TAQ quotes for a symbol/date, for reference (research not directly
--    consumed by VWAP, but immediately available from the same store).
SELECT
    symbol,
    dt,
    avg(ask_price - bid_price) AS avg_spread,
    min(ask_price - bid_price) AS min_spread,
    max(ask_price - bid_price) AS max_spread
FROM read_parquet('gs://traderx-501015-tick-store/ticks/**/*.parquet', hive_partitioning = true)
WHERE event_type = 'quote' AND source = 'taq' AND symbol = 'A' AND bid_price > 0 AND ask_price > 0
GROUP BY symbol, dt
ORDER BY dt;

-- 4. Row counts by source/event_type/symbol -- a quick inventory pass over what's actually stored.
SELECT source, event_type, symbol, dt, count(*) AS row_count
FROM read_parquet('gs://traderx-501015-tick-store/ticks/**/*.parquet', hive_partitioning = true)
GROUP BY source, event_type, symbol, dt
ORDER BY source, symbol, dt;
