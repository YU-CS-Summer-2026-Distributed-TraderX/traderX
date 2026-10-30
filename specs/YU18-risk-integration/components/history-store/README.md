# History store

Status: implemented 2026-10-30 as the `history-feed` service, proven locally (kill -9 IT) and on kind; not on the
CompSci render or GKE. Chosen by the bake-off on GCE VMs, 2026-10-29 (`docs/engineering/history-store-bakeoff.md`).
Decision: [ADR-098](../../system/adr-098-history-store.md).

The full-rate history of every order event and every trade side (blotter history, TCA, regulatory/EOD export),
fed from the `TRADERX_FEED` capture stream. MariaDB keeps current state (downstream-read-side, snapshot mode); this
store keeps everything. Chosen store: ClickHouse (Apache 2.0).

Source ownership:

- `generation/runtime-overrides/history-feed/` (rendered): `HistoryFeedMain` (config, `/health`, the history
  queries), `FeedLane` (one (feed, partition): store-is-cursor consumer, FR-HST-03), `ClickHouseStore`, `Rows` (the
  bridges' wire); tests `RowsTest`, `HistoryFeedKillIT`; opt-in manifests `k8s/clickhouse.yaml`, `k8s/history-feed.yaml`.
- `scripts/history-feed/kind-smoke.sh` (root): the image and manifests on a throwaway kind cluster.

The bake-off (root-owned, not rendered), `scripts/history-store-bakeoff/`:

- `src/HistoryFeed.java`: the feed (`sink=clickhouse`), plus the QuestDB, KDB-X and null sinks the bake-off ran.
- `src/HsbLoad.java`: load / fill on the bridges' exact managed wire; `src/HsbQuery.java`: exactly-once check,
  queries and export.
- `kdb/hsb.q`: the KDB-X intraday write-down process; `vm/`: the bake-off rig; `summarize.py`.

See [spec](spec.md).
