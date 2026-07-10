# Quickstart: YU07-historical-tick-store

## Local (kind)

### First run

```bash
bash pipeline/generate-state.sh YU07-historical-tick-store
bash generated/code/target-generated/scripts/start-state-YU07-historical-tick-store-generated.sh \
  --provider kind --without-sail
```

UI at **http://127.0.0.1:8080**. This inherits the `YU06-eod-price-production` (`YU02`) kind
runtime unchanged; only `tick-store` is new (its own Deployment + PVC).

### Subsequent runs (skip rebuild if code unchanged)

```bash
bash generated/code/target-generated/scripts/start-state-YU07-historical-tick-store-generated.sh \
  --provider kind --without-sail --skip-build
```

### Validate

```bash
bash scripts/test-state-YU07-historical-tick-store.sh
```

### Stop

```bash
bash generated/code/target-generated/scripts/stop-state-YU07-historical-tick-store-generated.sh
```

---

## Run the self-check (no cluster needed)

The generated `tick-store` component ships a standalone self-check that proves the capture mapping
and the TAQ quotes normalizer both produce Parquet a single DuckDB query reads back uniformly —
runnable straight from the spec source, no NATS/kind dependency:

```bash
cd specs/YU07-historical-tick-store/generation/runtime-overrides/tick-store
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python3 -m pytest tests/ -v
```

## Capture TraderX's own ticks (against a running cluster)

```bash
NATS_URL=nats://localhost:4222 TICKSTORE_OUT_DIR=/tmp/ticks python3 capture.py
```

Runs until terminated; ticks flush to `/tmp/ticks/source=live/dt=.../symbol=.../*.parquet` every
`TICKSTORE_FLUSH_INTERVAL_SECONDS` (default 30s) or `TICKSTORE_FLUSH_MAX_ROWS` (default 5000),
whichever comes first.

## Ingest a TAQ quotes file (streamed, no extraction)

```bash
# entry name is the single CSV inside the zip — confirm with: unzip -l <zip>
unzip -p taq_quotes_20250211_csv.zip <entry>.csv \
  | python3 ingest_taq_quotes.py --date 2025-02-11 --out /tmp/ticks
```

## Query the store

```bash
duckdb -c "$(cat duckdb_query_examples.sql)" # or run statements individually, see file
```

`duckdb_query_examples.sql` includes a VWAP-style query and a simple daily-return query, both
filtering by `symbol`/`dt` and reading across `source='live'` and `source='taq'` in one
`read_parquet('/tmp/ticks/**/*.parquet', hive_partitioning=true)`.
