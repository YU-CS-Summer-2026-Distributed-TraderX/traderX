# Research: YU07 — Historical Tick Store

## Decision 1 — a new `tick-store` component, not a Java service extension

Capture and query need three things none of the existing JVM services provide cheaply: a columnar
file writer (Parquet), an embeddable analytical query engine over it (DuckDB), and a way to stream a
multi-gigabyte CSV out of a zip archive without staging the whole thing on disk. DuckDB and Parquet
are both first-class in Python; doing the same from a JVM service would mean adding a Java Parquet
writer (`parquet-mr`) and a JDBC/JNI path into DuckDB for equivalent capability, for no benefit over
using DuckDB's own native Python package. A small standalone Python component is the smaller
diff — this project already has a non-Java service (`price-publisher`, Node.js), so a second
polyglot component is not new precedent.

`tick-store` is a single container running two entrypoints from the same image:
`capture.py` (long-running NATS subscriber) and `ingest_taq_quotes.py` (one-shot CLI).

## Decision 2 — capture sources: `pricing.*` and `/accounts/*/trades`, both already broadcast

Per `system/messaging-subject-map.md`, `pricing.<TICKER>` (wildcard, broadcast) and
`/accounts/<accountId>/trades` (wildcard, broadcast) already have multiple independent subscribers
(the Angular frontend, and for `pricing.*`, trade-processor's `PriceHistoryStore`). Adding
`tick-store` as another broadcast subscriber is the same pattern already in use — no new subject,
no change to any publisher, no risk to the existing point-to-point `/trades` link between
trade-service and trade-processor (which this state deliberately does not touch, since core-NATS
point-to-point delivery is single-subscriber and a third listener there could steal deliveries).

`pricing.<TICKER>` payload (`price-publisher/src/main.js`): `{price, openPrice, closePrice, asOf,
source}` — a last-price update, not a bid/ask quote; captured as `event_type='price_tick'`.
`/accounts/<accountId>/trades` payload (`Trade` entity, Jackson-serialized):
`{id, accountId, security, side, state, quantity, price, updated, created, settlementDate}` —
captured as `event_type='trade'`.

## Decision 3 — one unified Parquet schema for live capture and TAQ, not two

VWAP and return/scenario queries need to range over both sources uniformly. A single wide table with
nullable columns, tagged by `event_type` and `source`, means one query works over both:

`symbol, event_type, ts, price, size, bid_price, bid_size, ask_price, ask_size, venue, source, seq,
ingested_at` — full column meanings in `data-model.md`. Partitioned
`source=<live|taq>/dt=<date>/symbol=<SYM>/*.parquet` (Hive-style directories DuckDB reads back with
`hive_partitioning=true`), giving partition-pruned range queries by date and symbol without a
separate index.

## Decision 4 — TAQ file format: confirmed by sample, standard NYSE Daily TAQ CSV

Inspected `taq_quotes_20250211_csv.zip` from the OneDrive Feb2025 drop directly (streamed via
`unzip -p`, no extraction). Header:

```
DATE,TIME_M,EX,BID,BIDSIZ,ASK,ASKSIZ,QU_COND,QU_SEQNUM,NATBBO_IND,QU_CANCEL,QU_SOURCE,SYM_ROOT,SYM_SUFFIX
```

Standard NYSE Daily TAQ Consolidated Quotes (CQ) CSV: nanosecond `TIME_M`
(`H:MM:SS.nnnnnnnnn`, unpadded hour), per-venue NBBO-component quotes, `QU_SEQNUM` for ordering.
`ingest_taq_quotes.py` implements this confirmed layout. TAQ **trades** ingestion is not implemented
in this state — the trades file (`taq_trades_feb2025_csv.zip`) had not hydrated locally from
OneDrive as of writing, and per this project's standing rule, no normalizer is written against an
unconfirmed format.

Nanosecond precision is truncated to DuckDB's native microsecond `TIMESTAMP` (drops the last 3
digits of `TIME_M`) — a current precision limit of the `ts` column, not a data-loss risk for any
query this state serves (VWAP/return aggregation at second-or-coarser granularity).

## Decision 5 — stream the TAQ CSV through a shell pipe into DuckDB, never extract to disk

The quotes file's single CSV entry decompresses to 82,176,574,103 bytes (~76.5GiB) — confirmed via
the zip's own central directory (`unzip -l`), no extraction needed to learn this. Scaled across
Feb+March's ~19+23 daily files, full extraction would need multiple terabytes of scratch disk before
a single row is normalized, for a machine that had 30GB free when this was checked. There is also no
capacity reason to extract: DuckDB's CSV reader accepts `/dev/stdin`, so the ingestion command is a
plain Unix pipeline —

```bash
unzip -p taq_quotes_20250211_csv.zip <entry>.csv | python3 ingest_taq_quotes.py --date 2025-02-11 --out /data/ticks
```

`unzip` (already present, no new dependency) decompresses straight into the pipe; DuckDB reads
`/dev/stdin` sequentially (its documented mode for exactly this streaming case — auto-detection uses
a bounded read-ahead buffer rather than seeking) and writes Parquet directly. Peak extra disk usage
for ingesting a 76GB day is the size of that day's own output Parquet partition, nothing more.

## Decision 6 — storage: local PersistentVolumeClaim in v1, not GCS yet

The parent handoff flags ~650GB in GCS as a real, non-trivial monthly cost and asks for the user's
budget comfort and standard-vs-coldline tier before any bulk upload — neither has been confirmed as
of this state. `tick-store` writes to a local PVC-backed path (`/data/ticks`) so capture and TAQ
ingestion work end-to-end today without committing to a cloud spend decision that belongs to the
user. The unified schema and partitioning scheme do not change when the storage backend does — only
the `--out` path does (DuckDB's Parquet writer and reader work identically against a local path, a
mounted GCS FUSE volume, or `gs://` via the `httpfs` extension).

## Decision 7 — Parquet compression: ZSTD

DuckDB's Parquet writer supports `COMPRESSION ZSTD` natively — no new dependency, better ratio than
the Snappy default for the kind of repetitive tick/quote text this data is. Used for both the
capture writer and the TAQ ingestion writer.

## Generation — a genuinely new component, no shared-file clobber risk

`tick-store` has no ancestor state to conflict with — it is a new directory in the shared component
tree, not an override of a file YU02–YU06 already touch. The one shared file this state does modify
is `kubernetes-runtime/manifests/base/kustomization.yaml` (every ancestor state through YU06 already
overrides this file to add its own resources) — YU07's copy starts from YU06's current version
(`generation/runtime-overrides/kubernetes-runtime/manifests/base/kustomization.yaml` in
`specs/YU06-eod-price-production/`) and appends `tick-store-deployment.yaml` +
`tick-store-data-pvc.yaml`, never replacing it. Verified empirically post-generation (see
`generation/implementation-status.md`): every ancestor resource entry (`eod-session-close-cronjob.yaml`,
`order-matcher-lmax-data-pvc.yaml`, etc.) survives alongside the two new entries.
