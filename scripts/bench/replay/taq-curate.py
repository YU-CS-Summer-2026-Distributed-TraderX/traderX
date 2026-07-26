# Curate a TAQ trade-print replay slice: 7 symbols, 2025-03-03 09:30-10:00 ET.
# Output CSV (ts_us,symbol,px,qty,aggr) sorted by event time, tick-rule aggressor side,
# price rounded to the engine's 0.001 grid. Run on the tick-store pod (GCS HMAC in env).
import duckdb, os

con = duckdb.connect()
con.execute("INSTALL httpfs; LOAD httpfs;")
con.execute(f"""
CREATE SECRET gcs_secret (
    TYPE GCS,
    KEY_ID '{os.environ["GCS_HMAC_KEY_ID"]}',
    SECRET '{os.environ["GCS_HMAC_SECRET_ACCESS_KEY"]}'
);
""")

SYMS = ["AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "META", "JPM"]
DT = "2025-03-03"
globs = ",".join(
    f"'gs://traderx-501015-tick-store/ticks/source=taq/dt={DT}/symbol={s}/*.parquet'" for s in SYMS
)

con.execute(f"""
COPY (
  WITH t AS (
    SELECT symbol,
           epoch_us(ts) AS ts_us,
           round(price * 1000) / 1000 AS px,
           CAST(size AS BIGINT) AS qty,
           seq
    FROM read_parquet([{globs}], hive_partitioning=true)
    WHERE event_type = 'trade'
      AND ts >= TIMESTAMP '{DT} 09:30:00' AND ts < TIMESTAMP '{DT} 10:00:00'
      AND price > 0 AND size > 0
  ),
  ticked AS (
    SELECT *,
           CASE WHEN px > lag(px) OVER w THEN 'B'
                WHEN px < lag(px) OVER w THEN 'S'
                ELSE NULL END AS raw_tick
    FROM t
    WINDOW w AS (PARTITION BY symbol ORDER BY ts_us, seq)
  ),
  sided AS (
    SELECT ts_us, symbol, px, qty,
           coalesce(
             last_value(raw_tick IGNORE NULLS)
               OVER (PARTITION BY symbol ORDER BY ts_us, seq
                     ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW),
             'B') AS aggr
    FROM ticked
  )
  SELECT * FROM sided ORDER BY ts_us
) TO '/tmp/taq-replay-20250303-0930-1000.csv' (FORMAT CSV, HEADER false);
""")

n, buys = con.execute(
    "SELECT count(*), sum(CASE WHEN column4='B' THEN 1 ELSE 0 END) "
    "FROM read_csv('/tmp/taq-replay-20250303-0930-1000.csv', header=false)"
).fetchone()
print(f"rows={n} buy_aggr={buys} ({100.0*buys/n:.1f}%)")
print("size:", os.path.getsize("/tmp/taq-replay-20250303-0930-1000.csv"), "bytes")
