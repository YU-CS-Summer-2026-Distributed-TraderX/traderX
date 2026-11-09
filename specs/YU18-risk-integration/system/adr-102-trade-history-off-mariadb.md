# ADR-102: Trade history leaves MariaDB for time-series tiers (ClickHouse system of record, KDB-X analytics)

Status: proposed 2026-11-01; first slice implemented 2026-11-06 (local and kind; findings at the end); FR-THT-02 (the store in the CompSci render, history-pool, mandatory G3h) 2026-11-07, kind only. **Cutover
2026-11-08 (kind only, not in production):** ClickHouse is the trade system of record and booked trades are no longer
written to MariaDB; the blotter reads the store (FR-THT-09). Decided by yaakov 2026-11-08 (relayed by the EOD
integration coordinator), superseding the 2026-11-06 option A below. Nothing in the demo build changes (yaakov:
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
- **The blotter window stays in SQL** (decided 2026-11-06): it is current state; FR-THT-01 retires rows mode, not
  the window. The 10-30 gap stays recorded (synthetic history, not rebuilt). Rows mode, RI06 recovery and run
  transitions were removed the same day: all three wrote or verified SQL trade rows; the stream replay from the
  committed cursor and ADR-101's fresh epoch are the recovery paths.
- **One epoch per store**: refs restart at a fresh epoch (ADR-101), so the history tables must be archived with the
  epoch (FR-THT-08: `rig.sh` `archive_read_model`, 2026-11-08).

## Ingest rate, 2026-11-08

The store is the trade system of record only if it keeps up. On production e1108b history-feed ingested about
1,500 rows/s, because NATS stored the lanes' `maxAckPending(-1)` as 1000. That is fixed, with a scaled G3h wait, in
FR-THT-10 (ADR-098 amendment 2026-11-08). The cutover's rollout needs that history-feed image with it.

## Each side once, 2026-11-09

A system of record cannot hold a side twice. The bridges republish at a promotion and at a NATS reconnect
(FR-DRS-37/38), and on GKE `pa-proof-400k` the store held 1,104,397 sides twice (RI34). history-feed now leaves out
at the INSERT any row its store already holds, for trades and order events alike; the tables stay plain MergeTree, so
the blotter, TCA, the extract's source and G3h read each side once with no `FINAL` (FR-THT-11; the choice and what
was rejected are in ADR-098's amendment of 2026-11-09).

## Cutover, 2026-11-08 (kind)

Decided by yaakov (2026-11-08, through the EOD integration coordinator): ClickHouse becomes the trade system of record
and trades stop being written to MariaDB. This supersedes option A (the blotter window stays in SQL). It accepts the
cost option A avoided: the blotter now depends on history-feed's lag and the store's availability.

- **trade-processor** books every side in memory as before and writes positions, the per-date settlement counts, the
  cursor and the accounting. It writes no booked side to SQL `trades`; `TradeView` keeps no blotter ring and
  `TRADE_PROJECTION_BLOTTER_DEPTH` is gone. SQL `trades` keeps only **exceptions**, which the stream cannot show: a side
  booking refused (`Rejected`, the bond fail-closed rule, FR-DRS-19) and an operator-forced settlement (`Settled`).
- **The blotter** (position-service `GET /trades/{acct}`, `/trades/`, `/v2/projections/{scope}/accounts/{a}/trades`)
  reads the store, newest first (`BLOTTER_LIMIT`, 100), and puts an SQL exception row in place of the history row with
  the same id. A booked side's state is trade-processor's own T+N rule (`SETTLEMENT_T_PLUS_DAYS`, equal on both
  services by `validate`). The unfiltered list reads the newest date partition only. A scope other than the active one
  answers its SQL rows as before (an old epoch's history is in `arc_<label>_*`, FR-THT-08).
- **Ids**: the store keeps each id's prefix and the source order's epoch (`id_prefix`, `order_epoch`,
  LowCardinality, one value per epoch), so the blotter returns the exact trade and source-order ids. Rows written
  before the columns read "" (a legacy id, which production's `legacy-unknown` scope uses anyway).
- **By-id endpoints** (forced settlement from the console blotter and admin panel; TCA `GET /tca/report/{id}`):
  trade-processor's JPA lookups found these, which the SQL-literal inventory cannot see. With no SQL row,
  `HistoryStore.trade` reads the side from the store (by `trade_seq`, side and prefix; a minmax index on `trade_seq`
  skips the other granules). The entitlement is checked on that account. A forced settlement then records a
  `Settled` exception row. The recon is unaffected: in snapshot mode it reads the stream ledger, not SQL.
- **Gates**: G3 is unchanged. It fingerprints SQL tables, and `trades` now holds only exception rows, which do not
  move while the members start. G3h is unchanged (it already counts the store).
- **Not done**: SQL `trades` is not dropped (exceptions live there). Pre-cutover window rows stay until a fresh epoch
  archives them: a `Processing` one is never an exception (ignored), a swept `Settled` one says what the rule says.
  Proofs not runnable on kind keep their marks in `sql-trades-readers.txt`.

Proof (kind `traderx-cut`, 2026-11-08, images built from the integration tip 0a7c53f1 plus this change): yu18-projection-effect-end
(history rows, 0 SQL rows, positions equal to the history rows' signed sums, blotter ids equal to the store's), with
its NEGATIVE arm (a cross during a trade-processor outage replays, 6 of 6); kind-history-store (G3h with
delete/duplicate controls); yu18-extract-off-projection (the EOD extract); kind-cutover-check (extract = history store
= SQL positions on every traded (account, security); every account's blotter = its newest history rows); a forced
settlement of a booked side (200, Settled row, the blotter shows Settled; an unknown id 404); TCA and forced
settlement on booked ids (own 200, foreign 403 for a scoped token, unknown 404); yu05-auth-entitlements PASS on the
store's ids; a bond cross with no reference data (2 `Rejected` exception rows with the reason, 2 history rows, no
position, the blotter shows Rejected). kind-cutover-check also shows the engine's extract keeps a side
trade-processor rejected (the read model's fail-closed rule; positions = history less Rejected sides). The yu18 kind
suite (run-yu18-kind.sh, 8 proofs) passes, and every negative control fails. A/B: the pre-cutover trade-processor image
(`gap18-s17b`) on the same rig fails yu18-projection-effect-end ("SQL trades holds 2 row(s)").
