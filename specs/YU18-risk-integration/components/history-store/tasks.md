# History store tasks

- [x] Load on the bridges' managed wire, per-partition fill, null-sink feed ceiling (`HsbLoad`, `HistoryFeed`).
- [x] ClickHouse, QuestDB and KDB-X (intraday write-down, `kdb/hsb.q`) sinks; exactly-once check, queries (a)–(d)
      and export (`HsbQuery`).
- [x] Restart recreates the durable at the store's cursor + 1; the resume-the-durable loss measured (19 of 360M).
- [x] Bake-off on GCE, VMs deleted; KDB-X licence answers from the agreement text and a mapped-read measurement.
- [x] Decision record `docs/engineering/history-store-bakeoff.md`.
- [x] `history-feed` service: per-partition streams with trade-processor's names, consumer-sequence gap rule
      (FR-DRS-33), day-bounded batches, `/health` and the history queries with bound parameters (FR-HST-06).
- [x] `HistoryFeedKillIT`: two SIGKILLs mid-drain, exactly once; fails under a duplicate mutation and a loss mutation.
- [x] Opt-in kind manifests and `scripts/history-feed/kind-smoke.sh`; ADR-098; render module loop.
- [x] Cursor bound to the stream incarnation; a recreated stream is refused, not skipped (FR-HST-07, codeX §6).
- [x] Rows with no binding past cursor 0 are refused (`STREAM_UNBOUND`) until operator-attested (`0`) (FR-HST-07, codeX re-review).
