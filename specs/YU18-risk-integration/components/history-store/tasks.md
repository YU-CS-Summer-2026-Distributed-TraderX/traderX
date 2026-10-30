# History store tasks

- [x] Load on the bridges' managed wire, per-partition fill, null-sink feed ceiling (`HsbLoad`, `HistoryFeed`).
- [x] ClickHouse, QuestDB and KDB-X (intraday write-down, `kdb/hsb.q`) sinks; exactly-once check, queries (a)–(d)
      and export (`HsbQuery`).
- [x] Restart recreates the durable at the store's cursor + 1; the resume-the-durable loss measured (19 of 360M).
- [x] Bake-off on GCE, VMs deleted; KDB-X licence answers from the agreement text and a mapped-read measurement.
- [x] Decision record `docs/engineering/history-store-bakeoff.md`.
