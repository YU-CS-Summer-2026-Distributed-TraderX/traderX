# AMD and ANET are unreadable on dt=2025-03-11 — 41 truncated copies each, and one poisons the scan

**Found 2026-08-26** while widening the ADR-070 replay extract from 23 symbols to 100. The
BigQuery build that had run clean for months failed outright, and the failure is not in the
builder.

## What is proven

`bq query` over the hive-partitioned external table
(`gs://traderx-501015-tick-store/ticks/source=taq/*`) aborts with

```
Error while reading table: taq, error message: Input file is not in Parquet format.
File: .../dt=2025-03-11/symbol=AMD/d255a4f6-….parquet
```

and the same for `symbol=ANET`. **One unreadable object fails the whole scan**, so a universe
containing either symbol cannot be resampled at all — the other 39 days and the other 98 symbols
are collateral.

The objects are **truncated, not corrupt in content**. Measured by reading the magic bytes at both
ends (an 8-byte ranged GET each, no download):

| | head | tail |
|---|---|---|
| `dt=2025-03-11/symbol=AMD/08d49652-…` (39,148,251 B) | `PAR1` | `bf e6 42 35 34 ca d1 12` |
| `dt=2025-03-11/symbol=AMD/2dd83118-…` (2,457,528 B) | `PAR1` | `… PAR1` |

A parquet file ends with the `PAR1` magic; the 39 MB one does not. It starts correctly and stops
mid-stream — an interrupted upload, not damaged data.

**The scale, and why it went unnoticed.** `dt=2025-03-11` is the OOM outlier ADR-070 records as
"the day that completed only on a reduced-thread retry". That retry wrote its output **43 times**
per symbol for the alphabetical head of the corpus. Listing every object of a 100-symbol universe
across all 40 days (6,893 objects) finds a repeated exact byte size on exactly nine symbol-days,
all of them `dt=2025-03-11`, all of them alphabetically early:

| symbol | copies | each | footer |
|---|---|---|---|
| AAL, AAPL, ADBE, AEP, AMCR, AMZN, APA | 42 | 1.7–49 MB | **`PAR1` — complete** |
| **AMD** | **41** | 39,148,251 B | **truncated** |
| **ANET** | **41** | 5,669,269 B | **truncated** |

So seven of the nine are complete duplicates — harmless to a median, which is invariant under
symmetric duplication, and the reason the 23-symbol extract (which carries AAPL and AMZN) has
always built cleanly. Two of the nine are truncated, and those two are fatal.

**The size is not a reliable tell.** A repeated byte size flags all nine; only the footer separates
the seven harmless ones from the two fatal ones. Any future detector has to read the last four
bytes.

## Why this is not fixed here

BigQuery's `sourceUris` can only add paths, never subtract them, and a wildcard cannot exclude a
file. Keeping AMD and ANET would mean replacing the one-wildcard external table with a per-day
wildcard list plus an explicit, footer-verified allowlist of `dt=2025-03-11`'s good objects —
several hundred UUIDs pinned in a tracked build script, re-derived whenever anything about that day
changes. That is a permanent cost for a defect that belongs upstream.

`scripts/yu17/build-taq-replay-extract.sh` therefore **excludes AMD and ANET by name**, with BA and
AFL taking their places in the liquidity ranking, and the exclusion comment points here. The
extract is 100 symbols and complete: 479,999 of 480,000 windows carry a real median.

## What would resolve it

Deleting the 82 truncated objects (41 × 2) makes the plain wildcard work again with no builder
change and no allowlist — they carry no data any reader can use. That is a write to the shared
corpus and is **yaakov's call, not a lane's**.

Worth doing at the same time, and cheap once someone is in there: the same footer check across the
whole of `dt=2025-03-11`. This audit covered 100 symbols because that is the universe that was
being built; the retry's alphabetical pattern says the head of all 10,081 is where the rest would
be, and nothing has looked.

## Related

- `specs/YU17-otc-rates/system/adr-070-the-tape-is-the-reference.md` — flags `dt=2025-03-11` as the
  OOM outlier and records it "verified rather than excluded" on **sampled** symbols. That finding
  stands and is not contradicted: the sample was the 23-symbol universe, which contains neither AMD
  nor ANET.
- `issues/open/tick-store-drops-taq-sym-suffix-and-merges-share-classes.md` — the other reason a
  symbol is excluded from the replayed universe, and the other defect that a re-ingest would close.
