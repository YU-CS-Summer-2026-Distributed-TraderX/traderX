# RI32 — Move trade history off MariaDB into time-series tiers (after the conference)

Updated: 2026-11-06. Status: open, first slice landed locally (see "State, 2026-11-06"); nothing in production. Owner: yaakov + coordinator. Spec: `specs/YU18-risk-integration/components/trade-history-tiers/`
(FR-THT-01..07), decision ADR-102.

Problem: every trade side is a MariaDB row. That append-only table is the read model's write ceiling (89.7M sides
unprojected on 2026-10-30; one feed stream ~250K acked/s) and grows without bound. The demo's KDB-X tab runs over a
synthetic 5M-trade history, not the system's trades.

Direction: MariaDB keeps current state (positions, open orders, accounts). ClickHouse (`history-feed`, ADR-098,
already exactly-once on kind) becomes the trade history's system of record on GKE, with older partitions tiered to
GCS. KDB-X becomes the analytics tier fed from the same streams. Both are projections of the cluster log.

Acceptance: the spec's SC-THT-01..04: 400K orders/s with MariaDB writing current state only; 1.5B trade sides
queryable in under 1 s cold per account in both stores; a GCS-moved partition answers identically; the restore gate
checks the history store and refuses a deleted or duplicated row. Every SQL `trades` reader moved before the table
stops being written. Production steps only through admission and a yaakov-confirmed bring-up.

## State, 2026-11-06

Landed (local and kind, tests at the effect end with negative controls): ClickHouse tiering policy (FR-THT-03,
`HistoryTierIT`), the SQL `trades` reader inventory (FR-THT-05, 58 files, `sql-trades-readers.sh`), the FR-DRS-33
recon on ClickHouse, the restore gate's history-store check G3h (FR-THT-06), and the KDB-X answer: Community serves
`gs://` HDB segments (ADR-102 findings). Not landed: FR-THT-02 (ClickHouse on GKE), moving the blotter and the proofs,
the write-path cut, the KDB-X feed lane, the scale and rebuild proofs.

Decided (yaakov, 2026-11-06): FR-THT-01 is option A, rows mode retired. SQL `trades` keeps each account's blotter
window (current state, ADR-097); nothing reads it as history. The cut gate (`sql-trades-readers.sh`) now passes once
only the `KEEP:` window readers remain. Rows mode retired the same day (with RI06 recovery and run transitions);
Kind traderx-tht the same day: recon ported to the ledger (yu05-recon PASS with both controls), 4 window proofs PASS,
unset mode = snapshot and `rows` refuses at the effect end. 38 readers: KEEP 19, WINDOW 18 (need services the kind
cluster tier lacks), ROWS 1 (unreachable writer).

## The 89.7M sides of 2026-10-30 (decided: keep recorded)

Today they are recorded, not rebuilt: `rig-unprojected` = `3392665-662922190 663474186-753187098` (yaakov's yes,
2026-10-31), and the epoch was archived (ADR-101). The sides were never on the stream (bridge publishes dropped while
NATS was OOM-killed), so no store fed from the streams can have them. The old epoch's journal survives in the GCE
snapshots `traderx-e1101-m{0,1,2}` (250 GB each). Options:

1. **Keep recorded** (no cost). The range stays a known gap in the read model; the members' journal snapshots remain
   the record. History queries over 10-30 are incomplete and say so.
2. **Rebuild into the archived epoch's history** (proposed). Restore one snapshot to a disk, run an offline journal
   reader (the shadow-replay path G2 already uses) that emits each trade side 663,474,186..753,187,098 as a
   history row with its consensus sequence, into ClickHouse tables `arc_e1101_trades` (FR-THT-08), then check them
   with G3h's closed form over exactly that range. Needs code (a journal-to-rows exporter, its test on kind with a
   deliberately dropped range), one c4d-standard-16 (~$0.85/h) for ~3 h and a 250 GB Hyperdisk from the snapshot for
   a day (~$1): about $5 to $10 with retries. The
   SQL read model is not touched (snapshot mode would only window it). Not pursued: see below.
3. **Rebuild the read model's rows too**: SQL rows for 89.7M sides is what RI32 removes; not proposed.

Decided (yaakov, 2026-11-06): option 1. The trade history is synthetic and none of it is needed; the gap stays
recorded in `rig-unprojected` and no rebuild is run.
