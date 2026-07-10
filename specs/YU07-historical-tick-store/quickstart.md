# Quickstart: YU07-historical-tick-store

## Local (kind)

### First run

```bash
bash pipeline/generate-state.sh YU07-historical-tick-store
bash generated/code/target-generated/scripts/start-state-YU07-historical-tick-store-generated.sh \
  --provider kind --without-sail
```

UI at **http://127.0.0.1:8080**. This inherits the `YU06-eod-price-production` (`YU02`) kind
runtime unchanged; only `tick-store` is new (its own Deployment, no PVC — writes straight to GCS).

### One-time GCS credential setup (before `tick-store` can actually write)

`tick-store` writes to `gs://traderx-501015-tick-store` via an HMAC key/secret for the
bucket-scoped `tick-store-gcs` service account (research.md Decision 6). Create the key once via
Cloud Storage → Settings → Interoperability → Service account HMAC in the console, then create the
k8s Secret **in your own terminal** (the secret value should never be pasted into a chat/tool log):

```bash
kubectl create secret generic tick-store-gcs-hmac -n traderx \
  --from-literal=access-key-id=<ACCESS_ID> \
  --from-literal=secret-access-key=<SECRET>
```

Without this Secret, `tick-store` pods start but every Parquet flush fails (`gcs.configure_gcs`
raises clearly if `GCS_HMAC_KEY_ID`/`GCS_HMAC_SECRET_ACCESS_KEY` are unset — check
`kubectl logs deploy/tick-store -n traderx` for that error if writes aren't landing).

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
# against the real GCS bucket
NATS_URL=nats://localhost:4222 \
TICKSTORE_OUT_DIR=gs://traderx-501015-tick-store/ticks \
GCS_HMAC_KEY_ID=<ACCESS_ID> GCS_HMAC_SECRET_ACCESS_KEY=<SECRET> \
python3 capture.py

# or against a local path for quick iteration, no GCS credential needed
NATS_URL=nats://localhost:4222 TICKSTORE_OUT_DIR=/tmp/ticks python3 capture.py
```

Runs until terminated; ticks flush to `<out>/source=live/dt=.../symbol=.../*.parquet` every
`TICKSTORE_FLUSH_INTERVAL_SECONDS` (default 30s) or `TICKSTORE_FLUSH_MAX_ROWS` (default 5000),
whichever comes first.

## Ingest a TAQ quotes file (streamed, no extraction)

```bash
# entry name is the single CSV inside the zip — confirm with: unzip -l <zip>
unzip -p taq_quotes_20250211_csv.zip <entry>.csv \
  | GCS_HMAC_KEY_ID=<ACCESS_ID> GCS_HMAC_SECRET_ACCESS_KEY=<SECRET> \
    python3 ingest_taq_quotes.py --date 2025-02-11 --out gs://traderx-501015-tick-store/ticks
```

## Query the store

```bash
duckdb -c "
INSTALL httpfs; LOAD httpfs;
CREATE SECRET (TYPE gcs, KEY_ID '<ACCESS_ID>', SECRET '<SECRET>');
$(cat duckdb_query_examples.sql)
"
```

`duckdb_query_examples.sql` includes a VWAP-style query and a simple daily-return query, both
filtering by `symbol`/`dt` and reading across `source='live'` and `source='taq'` in one
`read_parquet('gs://traderx-501015-tick-store/ticks/**/*.parquet', hive_partitioning=true)`
(swap the path for a local one if you captured to disk instead).
