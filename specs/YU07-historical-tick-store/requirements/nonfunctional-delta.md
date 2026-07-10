# Non-Functional Delta: YU07-historical-tick-store over YU06-eod-price-production

| Req | Status | Notes |
|---|---|---|
| NFR-TS01 off the hot path | **Done** | No BLP/journal/order-matcher change; `tick-store` is an independent process consuming pre-existing broadcast subjects. Bench-compare not required (nothing on the order/tick path changed) — per the parent handoff. |
| NFR-TS02 restart-safe, no publisher backpressure | **Done** | Both capture subjects are core-NATS broadcast (no ack semantics tying a publisher to `tick-store`'s availability); a `tick-store` outage drops nothing for existing consumers and simply resumes capturing from whenever it reconnects. |
| NFR-TS03 bounded TAQ ingestion disk usage | **Done** | Streaming pipe design (research.md Decision 5) — peak extra disk is one day's output Parquet partition, not the ~76.5GiB decompressed source CSV. |
| NFR-TS04 reuse proven libraries | **Done** | DuckDB (columnar storage + query) and `unzip` (archive streaming) — no hand-rolled format or query engine. |
