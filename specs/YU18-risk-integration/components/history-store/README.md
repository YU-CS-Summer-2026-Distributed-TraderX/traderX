# History store

Status: prototype, chosen by bake-off and measured on GCE VMs, 2026-10-29. Not deployed; nothing in the render
or the GKE manifests changes. Decision record: `docs/engineering/history-store-bakeoff.md`.

The full-rate history of every order event and every trade side (blotter history, TCA, regulatory/EOD export),
fed from the `TRADERX_FEED` capture stream. MariaDB keeps current state (downstream-read-side, snapshot mode); this
store keeps everything. Chosen store: ClickHouse (Apache 2.0).

Source ownership (root-owned, built from the checkout, not rendered): `scripts/history-store-bakeoff/`:

- `src/HistoryFeed.java`: the feed (`sink=clickhouse`), plus the QuestDB, KDB-X and null sinks the bake-off ran.
- `src/HsbLoad.java`: load / fill on the bridges' exact managed wire; `src/HsbQuery.java`: exactly-once check,
  queries and export.
- `kdb/hsb.q`: the KDB-X intraday write-down process; `vm/`: the bake-off rig; `summarize.py`.

See [spec](spec.md).
