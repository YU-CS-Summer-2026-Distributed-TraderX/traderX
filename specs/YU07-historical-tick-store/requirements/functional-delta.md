# Functional Delta: YU07-historical-tick-store over YU06-eod-price-production

New requirement namespace `TS`.

| Req | Status | Notes |
|---|---|---|
| FR-TS01 capture pricing.* and account trades | **Done** | `capture.py` subscribes both subjects on the shared NATS broker connection. |
| FR-TS02 no change to existing publishers/consumers | **Done** | Both subjects are pre-existing broadcast subjects with multiple subscribers already; `tick-store` adds a subscriber, nothing more. |
| FR-TS03 partitioned Parquet output | **Done** | `source=<live|taq>/dt=<date>/symbol=<SYM>/*.parquet`, written via DuckDB `COPY ... (FORMAT PARQUET, COMPRESSION ZSTD)`. |
| FR-TS04 TAQ CQ normalizer | **Done** | `ingest_taq_quotes.py` implements the confirmed `DATE,TIME_M,EX,BID,BIDSIZ,ASK,ASKSIZ,QU_COND,QU_SEQNUM,NATBBO_IND,QU_CANCEL,QU_SOURCE,SYM_ROOT,SYM_SUFFIX` layout. |
| FR-TS05 stream from zip, no extraction | **Done** | `unzip -p <zip> <entry> \| python3 ingest_taq_quotes.py` — DuckDB reads `/dev/stdin` directly. |
| FR-TS06 uniform DuckDB query recipe | **Done** | `duckdb_query_examples.sql` — VWAP-style and return-series queries filter by `symbol`/`dt` and read across both `source` values in one `FROM read_parquet(..., hive_partitioning=true)`. |
| FR-TS07 source/event_type/symbol on every row | **Done** | Every write path sets all three; see `data-model.md`. |
