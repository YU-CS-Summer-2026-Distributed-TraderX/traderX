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

Inspected a real TAQ quotes zip file directly (streamed via `unzip -p`, no extraction). Header:

```
DATE,TIME_M,EX,BID,BIDSIZ,ASK,ASKSIZ,QU_COND,QU_SEQNUM,NATBBO_IND,QU_CANCEL,QU_SOURCE,SYM_ROOT,SYM_SUFFIX
```

Standard NYSE Daily TAQ Consolidated Quotes (CQ) CSV: nanosecond `TIME_M`
(`H:MM:SS.nnnnnnnnn`, unpadded hour), per-venue NBBO-component quotes, `QU_SEQNUM` for ordering.
`ingest_taq_quotes.py` implements this confirmed layout.

TAQ **trades** ingestion (`ingest_taq_trades.py`) was added once a real trades zip file was
available to inspect, confirming the standard NYSE Daily TAQ Consolidated Trades (CT) CSV layout:

```
DATE,TIME_M,EX,SYM_ROOT,SYM_SUFFIX,TR_SCOND,SIZE,PRICE,TR_STOP_IND,TR_CORR,TR_SEQNUM,TR_ID,TR_SOURCE,TR_RF
```

Same streaming/no-extraction approach as quotes (Decision 5). Column mapping in `data-model.md`.
Verified against real CT sample rows before shipping, per this project's standing rule.

Nanosecond precision is truncated to DuckDB's native microsecond `TIMESTAMP` (drops the last 3
digits of `TIME_M`) — a current precision limit of the `ts` column, not a data-loss risk for any
query this state serves (VWAP/return aggregation at second-or-coarser granularity).

## Decision 5 — stream the TAQ CSV through a shell pipe into DuckDB, never extract to disk

A single daily quotes file's CSV entry can decompress to tens of GB — confirmed via the zip's own
central directory (`unzip -l`), no extraction needed to learn this. Scaled across a multi-week or
multi-month batch of daily files, full extraction would need multiple terabytes of scratch disk
before a single row is normalized. There is also no capacity reason to extract: DuckDB's CSV reader
accepts `/dev/stdin`, so the ingestion command is a plain Unix pipeline —

```bash
unzip -p taq_quotes_YYYYMMDD_csv.zip <entry>.csv | python3 ingest_taq_quotes.py --date YYYY-MM-DD --out gs://traderx-501015-tick-store/ticks
```

`unzip` (already present, no new dependency) decompresses straight into the pipe; DuckDB reads
`/dev/stdin` sequentially (its documented mode for exactly this streaming case — auto-detection uses
a bounded read-ahead buffer rather than seeking) and writes Parquet directly. Peak extra disk usage
for ingesting a 76GB day is the size of that day's own output Parquet partition, nothing more.

## Decision 6 — storage: GCS Standard tier, `traderx-501015`, bucket-scoped HMAC credential

Confirmed with the user: GCP, Standard storage class (not Nearline/Coldline — this is actively
queried research data, feeding VWAP and the user's own future VaR/ES iteration; a single DuckDB
range query re-scanning a month of Parquet would eat any storage-cost savings in retrieval fees on
a colder tier), in the existing `traderx-501015` project (same project as the GKE cluster, reusing
billing/IAM rather than standing up a second project). Bucket: `gs://traderx-501015-tick-store`,
`us-east1` (matching the cluster's region), uniform bucket-level access.

Auth: DuckDB's GCS support is HMAC key/secret (`CREATE SECRET (TYPE gcs, KEY_ID ..., SECRET ...)`),
the same credential shape as S3 — not Kubernetes/GKE Workload Identity, which DuckDB has no native
concept of. A dedicated service account (`tick-store-gcs@traderx-501015.iam.gserviceaccount.com`)
holds `roles/storage.objectAdmin` scoped to *only* this bucket (bucket-level IAM binding, not a
project-level one) — least-privilege: a compromised HMAC key can read/write/delete objects in this
one bucket and nothing else in the project. The HMAC key pair is provided to `tick-store` via a
Kubernetes Secret (`tick-store-gcs-hmac`), created out-of-band with `kubectl create secret` — never
committed to this repo (see `quickstart.md`).

`capture.py`/`ingest_taq_quotes.py` both take the `gs://` URI as their `--out`/`TICKSTORE_OUT_DIR`
value unchanged — the unified schema and partitioning scheme are identical to the local-PVC design
this state started with; only the destination path and one `CREATE SECRET` call at startup differ
(`tick-store/gcs.py`, shared by both entrypoints, opt-in on the `gs://` prefix so local/PVC paths
are unaffected). Verified structurally against the real bucket (dummy HMAC credentials against the
real `gs://traderx-501015-tick-store` path correctly produced an auth-style 403, not a
scheme/routing error, confirming the mechanism resolves correctly before a real key was ever
involved).

## Decision 7 — Parquet compression: ZSTD

DuckDB's Parquet writer supports `COMPRESSION ZSTD` natively — no new dependency, better ratio than
the Snappy default for the kind of repetitive tick/quote text this data is. Used for both the
capture writer and the TAQ ingestion writer.

## Decision 8 — bulk backfill: a Kubernetes Indexed Job reading straight from `_raw-taq`, not a manual per-file loop

Decisions 4/5's normalizers are general-purpose — they accept any single conforming TAQ file, not
a specific dataset. But running them one file at a time by hand doesn't prove they hold up at real
production scale (many files, tens of GB each, run concurrently) — a real multi-file TAQ corpus
landing in `gs://traderx-501015-tick-store/_raw-taq/` (uploaded separately, outside this state's
scope) was the first opportunity to test that. A `stage2_ingest.sh` driver, bundled into the same
`tick-store` container image, lists `_raw-taq/**/*.zip`, dispatches each file's basename to
`ingest_taq_quotes.py` or `ingest_taq_trades.py` by filename pattern, and streams it the same way
as single-file ingestion (`gcloud storage cat | funzip | python3 <normalizer>.py`) — the raw zip is
read from GCS and never touches the pod's local disk, matching Decision 5's no-extraction
principle even though the source moved from a local file to a GCS object. Nothing about the driver
or the Indexed Job pattern is specific to any one dataset — the same path runs unchanged against
any future TAQ drop landed in the same GCS prefix.

Deployed as a Kubernetes **Indexed Job** (`completionMode: Indexed`) rather than one long-running
pod looping over every file: `JOB_COMPLETION_INDEX` selects exactly one file per pod from the
sorted file list, giving coordination-free parallelism — N pods run concurrently with zero risk of
two pods double-processing the same file, and a crashed pod's index is retried by the Job
controller without disturbing the others. `completions` matches the file count, `parallelism`
scaled at runtime via `kubectl patch job ... -p '{"spec":{"parallelism":N}}'` (a mutable field —
no Job recreation needed to change concurrency, unlike the pod template's resource limits).

**Bug found and fixed**: `stage2_ingest.sh` originally judged success/failure from the whole
pipeline's exit code under `set -euo pipefail`. `funzip` exits nonzero on a single-entry zip's
trailing bytes even when the entry decompressed correctly, and `gcloud storage cat` can see a
`SIGPIPE` — both trip `pipefail` even when the normalizer itself fully succeeded. This caused every
"successful" ingest to be reported `FAILED`, so the Indexed Job retried the same file repeatedly,
each retry writing a **full duplicate copy** into the same partition (`OVERWRITE_OR_IGNORE` +
random-UUID filenames don't dedupe across separate runs) — confirmed by a 12–23× file-count
inflation on the first dates processed before the bug was caught. Fixed by keying success on the
normalizer's own exit code via `PIPESTATUS` (the last pipe stage), not the pipeline's `pipefail`
status. The corrupted partitions were wiped and re-ingested with the fixed image before resuming.
**Lesson for any future streaming-pipe ingestion in this codebase**: `pipefail` is not safe by
itself for a decompress-then-parse pipe where the decompressor may exit nonzero on trailing-byte
noise — check the actual consumer's exit code explicitly.

**Image-tag caching trap**: rebuilding the fixed image under the same reused tag
(`tick-store:state-yu07`) did not take effect on already-provisioned nodes with
`imagePullPolicy: IfNotPresent` — they kept running the stale cached (buggy) image. Fixed by
pinning the Job's `image:` field to the immutable push digest (`@sha256:...`) with
`imagePullPolicy: Always`, so a rebuild under the same tag can never silently serve stale code.

**Parallelism scaling hit three separate regional GCP quotas** (`C2_CPUS`, `IN_USE_ADDRESSES`,
`SSD_TOTAL_GB`) working through this — see `project_traderx_lmax_kube.md` (memory) for the
resolution of each, including standing up Cloud NAT + a private (no-external-IP) node pool
(`batch-private-pool`) to remove the `IN_USE_ADDRESSES` ceiling entirely. Not repeated here since
it's cluster-topology infrastructure, not a store-schema or ingestion-logic decision.

## Generation — a genuinely new component, no shared-file clobber risk

`tick-store` has no ancestor state to conflict with — it is a new directory in the shared component
tree, not an override of a file YU02–YU06 already touch. The one shared file this state does modify
is `kubernetes-runtime/manifests/base/kustomization.yaml` (every ancestor state through YU06 already
overrides this file to add its own resources) — YU07's copy starts from YU06's current version
(`generation/runtime-overrides/kubernetes-runtime/manifests/base/kustomization.yaml` in
`specs/YU06-eod-price-production/`) and appends `tick-store-deployment.yaml`, never replacing it
(no PVC — `tick-store` writes straight to GCS, Decision 6). Verified empirically post-generation
(see `generation/implementation-status.md`): every ancestor resource entry
(`eod-session-close-cronjob.yaml`, `order-matcher-lmax-data-pvc.yaml`, etc.) survives alongside the
new entry.
