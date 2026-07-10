# Runtime Topology: YU07-historical-tick-store

Parent state: `YU06-eod-price-production`

## Entrypoints

- No new HTTP entrypoint anywhere — `tick-store` is a NATS subscriber plus a one-shot CLI, not a
  web service.
- `capture.py` — process entrypoint, connects to NATS on boot, subscribes `pricing.*` and
  `/accounts/*/trades`, runs until terminated.
- `ingest_taq_quotes.py --date <date> --out <dir>` — CLI entrypoint, reads TAQ CQ CSV from stdin,
  exits after the input stream ends.

## Components

- Inherits every Kubernetes/C3/FDC3/EOD runtime component already present in
  `YU06-eod-price-production` unchanged.
- `tick-store` (new): a single container image running either `capture.py` (Deployment, 1 replica)
  or `ingest_taq_quotes.py` (ad hoc `kubectl exec` / local run, not a standing workload).
- `tick-store` has no PVC — both entrypoints write directly to `gs://traderx-501015-tick-store`
  (research.md Decision 6). No shared volume with the hot path, no local disk to manage or exhaust.
- NATS gains no new stream or subject; `tick-store` is a plain subscriber on two subjects that
  already have other subscribers.

## Networking

- `tick-store` connects directly to the existing `nats-broker` service — no new service-to-service
  HTTP calls.
- `tick-store` authenticates to GCS via an HMAC key/secret (the `tick-store-gcs-hmac` k8s Secret,
  created out-of-band) scoped to only the `traderx-501015-tick-store` bucket; no other component
  reads or writes that bucket.
- TAQ ingestion is invoked locally/ad hoc against the mounted OneDrive path or a copied sample file
  — it is not a standing network service and has no ingress.

## Startup / Health Order

1. Generate and verify the inherited `YU06-eod-price-production` baseline assets.
2. Start MariaDB, NATS, and inherited support services as in the parent state.
3. Start `tick-store`'s `capture.py`; it connects to NATS in a retry loop and does not block
   readiness on broker availability, matching the pattern every other NATS consumer in this
   lineage uses.
4. `ingest_taq_quotes.py` runs independently of the standing runtime, on demand.

## Degraded Behavior

| Condition | Effect |
|---|---|
| NATS unreachable from `tick-store` at boot | `capture.py` retries the connection; no messages are lost for other subscribers since delivery to `tick-store` was never required by any publisher (broadcast, no ack). |
| `tick-store` down while ticks are published | Those ticks are not captured (broadcast subjects are not durable/replayed) — a gap in the historical record for that window, with no effect on any other consumer or the order-matching hot path. |
| GCS write fails (network blip, transient auth error) | The next Parquet flush fails and is logged; `capture.py` continues attempting subsequent flushes rather than crash-looping on one failed batch. The failed batch's rows are dropped, not retried or buffered — acceptable for supplementary tick history, not for the durable trade record (which lives in MariaDB/the BLP journal, unaffected by this). |
| Malformed TAQ CSV row | `ingest_taq_quotes.py` excludes the row (missing symbol/date/time) from the write and continues; the run only fails if zero valid rows were parsed, or if the column layout doesn't match the confirmed CQ header. |
