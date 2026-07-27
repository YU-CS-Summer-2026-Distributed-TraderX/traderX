# KDB-X as the YU07 tick store

KDB-X reads the existing TAQ Parquet corpus **natively, with no conversion step**. The store is
the corpus: `gs://traderx-501015-tick-store/ticks/source=taq/`, ZSTD, untouched. What lives here
is a ~130-line q layer that maps those objects as a date/symbol-partitioned virtual table and
gives you `quote`, `trade`, VWAP, spread, and session playback on top.

## Two things called "journal", two things called "playback"

|  | authoritative | analytical |
|---|---|---|
| **store** | Aeron Archive (+ snapshots) | **KDB-X — this directory** |
| **purpose** | consensus, recovery, determinism | query, analytics, demo playback |
| **playback means** | replay the log to rebuild exact state | replay a captured session for analysis |
| **on the hot path?** | yes, synchronous, before commit | **no — off-consensus, best-effort** |

Nothing in this directory is authoritative, on the hot path, or required for recovery. The Aeron
Archive consensus journal is untouched and stays the deterministic replay source of truth.

## Run it

```bash
bash fetch-sample.sh                                  # ~310 MB, 2 days x 4 symbols
TICKSTORE_ROOT=~/dev/lmax/kdb-tickstore/sample q selfcheck.q
```

`selfcheck.q` is the regression gate: 14 checks, every expected value computed independently with
DuckDB over the same files, so it is a cross-implementation check rather than kdb agreeing with
itself. It fails loudly on a wrong row count, a lost duplicate collapse, a broken quote/trade
split, a drifted VWAP, or an out-of-order replay.

```
$ TICKSTORE_ROOT=... q selfcheck.q
tickstore: collapsed 1 re-ingest duplicate file(s):
  .../dt=2025-02-03/symbol=AAPL/c42adfef-....parquet
tickstore: 16 files -> quote + trade over 2 date(s), 4 symbol(s).
ok   per-partition row counts
ok   deduped corpus total
...
selfcheck: 14 checks passed.
```

## Query it

`tickstore.q` defines two partitioned virtual tables, `quote` and `trade`, plus:

```q
.ts.vwap   [2025.02.03; `AAPL`SPY; 09:30:00.000000000; 16:00:00.000000000]
.ts.spread [2025.02.03; `CROX;     09:30:00.000000000; 16:00:00.000000000]
.ts.session[2025.02.03; `CROX`MSFT;09:30:00.000000000; 09:30:05.000000000]
.ts.replay [session; 1.0; {show x}]     / 1.0 = real time, 0w = as fast as possible
```

Ordinary q also works — `select count i by dt,symbol from trade` prunes to the right partitions.

## Sample

`dt=2025-02-03` + `2025-02-04`, AAPL / MSFT / SPY / CROX (the mid-cap). 310 MB, 17 files,
**52,265,720 rows after duplicate collapse** — 3.2M trades and 47.8M quotes. Both dates have
quotes *and* trades and avoid `dt=2025-03-11`, the OOM/retry day whose file set is unverified.

## Six things worth knowing

**1. No conversion step, and none needed.** `.pq.pq` maps a ZSTD Parquet file as a virtual table
and prunes row groups against the WHERE clause; `.pq.t.mkP` stitches per-file virtual tables into
one date/symbol-partitioned table. Partition columns come from the *path*, which is where the
ingest put them. Nothing is rewritten into kdb's own on-disk format.

**2. The 16 GiB Community cap is not binding.** An aggregate over all 47.8M quotes peaked at
**768 MiB** — 4.7% of the cap — because the reader works a row group at a time rather than
materialising the table. Disk was never the constraint and RAM turns out not to be either.

**3. Duplicates are collapsed at load, and said out loud.** Re-ingest wrote new UUID-named files
beside the old ones, so AAPL 2025-02-03 carries two equal-sized quote files whose rows differ only
in `ingested_at`. `.ts.dedup` keeps one file per distinct byte size within each
`(dt;symbol;kind)` group and prints what it dropped. An *unequal*-sized pair in one group is not a
duplicate — it is a partial write — so those are kept and flagged with a warning instead. Loading
both AAPL files would have given 9,256,890 quotes; the check asserts 4,628,445.

**4. `quote` and `trade` are separate tables on purpose.** Quote rows carry NULL `price`/`size`
and trade rows carry NULL `bid`/`ask`. One mixed table puts every price aggregate one forgotten
`WHERE event_type='trade'` away from being quietly wrong. The ingest already wrote the two kinds
to separate files, so the split costs nothing — `.ts.kind` reads one column chunk of row group 0
to classify each file.

**5. Zero-sided quotes are real values, not nulls.** Pre-open rows carry `bid_price=0.0`,
`ask_price=0.0` rather than NULL, so a spread calculation must exclude them explicitly.
`.ts.spread` does.

**6. VWAP here is arithmetic over unfiltered prints.** The ingest dropped `TR_CORR` and
`TR_SCOND`, the fields used to exclude corrected, cancelled and non-last-eligible sales, so the
numbers are correct over what is stored but are not an official/reference-grade VWAP. Recovering
those columns is a change to the ingest, not a re-run.

## Traps hit while building this

- **A line containing only `/` starts a q block comment** that runs to a line containing only `\`.
  A file whose header has a bare `/` separator silently loads as nothing and exits 0. Use `//`.
- `exp` and `ss` are reserved q words and are not usable as variable names.
- The virtual-table query engine rejects the each-both form `ts within'(dt+t0;dt+t1)`. Iterate
  dates and call the query per date.
- Never hand the store a recursive glob. ~10,100 symbol partitions per trading day means `**`
  LISTs ~400k objects before any predicate prunes. `.ts.scan` walks `dt=`/`symbol=` literally.

## Not done here

The off-consensus leader-side tap that feeds live cluster orders and trades into this store is not
built — it needs a running cluster, and this work was deliberately local. The tap belongs beside
`TradeNatsPublisher` / `OrderNatsPublisher`, best-effort with a visible drop signal, never in the
apply path, reusing the `OutputPublisher` drain-and-retry discipline.
