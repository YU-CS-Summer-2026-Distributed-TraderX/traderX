# Non-Functional Delta: YU07-historical-tick-store over YU06-eod-price-production

| Req | Status | Notes |
|---|---|---|
| NFR-TS01 off the hot path | **Done** | No BLP/journal/order-matcher change; `tick-store` is an independent process consuming pre-existing broadcast subjects. Bench-compare not required (nothing on the order/tick path changed) — per the parent handoff. |
| NFR-TS02 restart-safe, no publisher backpressure | **Done** | Both capture subjects are core-NATS broadcast (no ack semantics tying a publisher to `tick-store`'s availability); a `tick-store` outage drops nothing for existing consumers and simply resumes capturing from whenever it reconnects. |
| NFR-TS03 bounded TAQ ingestion disk usage | **Done** | Streaming pipe design (research.md Decision 5) — peak extra disk is one day's output Parquet partition, not the ~76.5GiB decompressed source CSV. |
| NFR-TS04 reuse proven libraries | **Done** | DuckDB (columnar storage + query) and `unzip` (archive streaming) — no hand-rolled format or query engine. |

## Added later — the KDB-X analytical layer

| Req | Status | Notes |
|---|---|---|
| NFR-TS05 tap never in the apply path | **Done** | The service thread allocates one record and does a non-blocking queue offer; a daemon thread does every file system call. A stalled disk fills the queue and drops — it cannot wedge apply. |
| NFR-TS06 drops counted and loud | **Done** | The first drop and every 10,000th log a WARN; `stop()` prints the totals, and that counter is the authoritative loss signal. `.tx.gaps[]` answers the narrower question from the other end — which consensus sequences produced no captured row — and is expected to be non-empty around control events. |
| NFR-TS07 bounded capture disk | **Done** | The tap stops capturing at `KDB_TAP_MAX_MB` (default 256) and says so. Analytics lose a tail; the Aeron Archive keeps its disk. |
| NFR-TS08 no authoritative state | **Done** | The capture log is a kdb tickerplant log, not a journal. Delete the whole directory and the cluster still recovers byte-identically; delete the Aeron Archive and it does not. |
| NFR-TS09 inert unless configured | **Done** | Unset `KDB_TAP_DIR` and the tap never starts — one null check per output event, the same shape as the two NATS bridges beside it. |
