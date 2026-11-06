# ADR-102: Trade history leaves MariaDB for time-series tiers (ClickHouse system of record, KDB-X analytics)

Status: proposed 2026-11-01; first slice implemented 2026-11-06 (local and kind; findings at the end). Nothing in the demo build changes (yaakov:
"adding it now would ... ruin an already stable system"). Pack: [trade history tiers](../components/trade-history-tiers/spec.md).
Relates to ADR-097 (snapshot read model), ADR-098 (history store, ClickHouse feed), FR-DRS-34 (per-partition streams),
and the bake-off in `docs/engineering/history-store-bakeoff.md`.

## Context

Every trade side reaches MariaDB as a row (cluster → NATS `TRADERX_FEED` → trade-processor → SQL `trades`). That
append-only table grows without bound and is the read model's write ceiling: the 2026-10-30 run left 89.7M trade
sides unprojected, and ADR-097 measured the single feed stream at ~250K acked/s. Positions, open orders and balances
are small (accounts × instruments) and change in place; trades only ever append.

The 2026-10-29 bake-off on one rig and one feed: ClickHouse 1.14M rows/s, one account's page 131 ms cold / 5.9 ms
warm (sorted by account); KDB-X Community 1.23M rows/s at 0.2 cores but 26 s cold per-account scans (arrival order)
and a 16 GiB heap cap; QuestDB 0.79M/s. The `history-feed` service (ClickHouse, exactly-once, FR-HST-01..07) exists
but runs on kind only.

This is a project, not a hosted service, so the KDB-X Community licence (internal use, 16 GiB heap and 24 cores per
instance; mapped on-disk reads do not count against the heap, measured) does not rule KDB-X out. Both tiers are
kept.

## Decision

1. **Split current state from history.** MariaDB keeps current state only: positions, open orders, accounts,
   reference data, EOD prices. Trade sides stop being inserted into SQL `trades` per trade; trade-processor still
   applies each side to `positions` (one row per account × instrument).
2. **ClickHouse is the trade history's system of record** below the cluster journal: the existing `history-feed`
   (ADR-098) on the per-partition streams, `PARTITION BY toDate(ts) ORDER BY (account, ts)`, deployed on GKE.
   Recent partitions on SSD; older partitions move to a GCS-backed disk by a TTL storage policy.
3. **KDB-X is the analytics tier**, fed from the same capture streams by its own exactly-once lane (store-is-cursor,
   FR-HST-03 rules): an intraday in-memory table written down each day to a date-partitioned HDB, sorted by account
   with the parted attribute inside each date. Several HDB processes over date ranges sit behind one q gateway when
   one instance's heap or cores are not enough. Older dates may live in GCS if KDB-X's object-storage support
   (`par.txt` segments on `gs://`) is available in the Community build; otherwise on persistent disks listed in
   `par.txt`.
4. **Both are projections.** The Aeron cluster log stays the source of truth; either store can be rebuilt from the
   capture streams (and, past stream retention, from the archived journal).
5. **Every reader of SQL `trades` moves first**: the blotter and trade history views, the recon SQL (FR-DRS-33),
   the restore gate's read-model fingerprint (FR-PRG-27, G3/G4), EOD, and the proofs. SQL `trades` is dropped
   only after none remains.

## Consequences

- The read model's write load becomes positions and order state only; trade volume no longer bounds it.
- The restore gate needs a history-store check in place of the SQL fingerprint over `trades` (count and an
  order-sensitive hash per partition from ClickHouse).
- GCS holds no time-series engine itself; it is the cold tier underneath ClickHouse (and possibly KDB-X).
- Two stores to run and size; the demo's synthetic KDB history (FR-DA10/12) becomes a real feed.
- Rejected: KDB-X as the only store (per-instance caps, every analytic query must stay under the 16 GiB heap);
  BigQuery/Bigtable (seconds per query, or point reads without analytics; neither is local-testable on kind);
  keeping trades in MariaDB with bigger hardware (the append-only table keeps growing).

## Findings, 2026-11-06 (first slice, local)

- **KDB-X Community reads `gs://` HDB segments.** Measured with `scripts/trade-history-tiers/kdb-objstor-check.sh`
  on a real bucket: decision 3's `par.txt` segments on GCS are available, so cold KDB-X dates may live in GCS. It
  needs `use`kx.objstor` plus `init[]`; without it the GCS dates load as absent with no error.
- **ClickHouse tiering works on the upgrade path** (`HistoryTierIT`): an existing local table moves onto the
  `tiered` policy only if its hot volume is named `default`.
- **The restore gate's history check is G3h** (FR-THT-06), the snapshot G3's closed form over ClickHouse.
- **Snapshot mode already took per-trade rows out of SQL** (ADR-097): production's `trades` is each account's
  blotter window. The 10-30 loss was upstream of every store: the bridge publishes NATS dropped while it was
  OOM-killed (FR-PRG-28), so ClickHouse fed from the same streams would have missed the same 89.7M sides. The history
  store is exact only for what reaches the stream, and it must keep up with `TRADERX_FEED`'s discard-old retention.
- **One epoch per store**: refs restart at a fresh epoch (ADR-101), so the history tables must be archived with the
  epoch (FR-THT-08, proposed).
