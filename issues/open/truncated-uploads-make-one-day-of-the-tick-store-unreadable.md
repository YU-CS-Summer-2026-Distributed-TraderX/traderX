# 52 symbols are unreadable on dt=2025-03-11 — truncated uploads, and one poisons the whole scan

**Found 2026-08-26** while widening the ADR-070 replay extract from 23 symbols to 100: the BigQuery
build that had run clean for months failed outright, and the failure is not in the builder.

**Corrected the same day, twice, and the corrections are the point.** The first version of this
issue said *two* symbols and offered an alphabetical bounding story. The coordinator footer-checked
independently, found two more, and showed the story was false. Auditing the whole day then found
**52**. A wrong bounding story in a filed issue is what makes the next person stop looking, so both
the wrong versions and what replaced them are kept below.

## What is proven

`bq query` over the hive-partitioned external table
(`gs://traderx-501015-tick-store/ticks/source=taq/*`) aborts with

```
Error while reading table: taq, error message: Input file is not in Parquet format.
File: .../dt=2025-03-11/symbol=AMD/d255a4f6-….parquet
```

**One unreadable object fails the entire scan** — every other symbol and all 39 other days are
collateral. A universe containing any affected symbol cannot be resampled at all.

The objects are **truncated, not corrupt in content**: they start with the parquet magic and stop
mid-stream, with no closing magic.

| | head | tail |
|---|---|---|
| `dt=2025-03-11/symbol=AMD/08d49652-…` (39,148,251 B) | `PAR1` | `bf e6 42 35 34 ca d1 12` |
| `dt=2025-03-11/symbol=AMD/2dd83118-…` (2,457,528 B) | `PAR1` | `… PAR1` |

## The method — cheap, decisive, and the only thing that works

**Read the last four bytes and compare to `PAR1`.** One ranged GET per object, no download:

```bash
gcloud storage cat -r -4 "$uri" | xxd -p      # 50415231 = PAR1 = complete
```

**Size is not a substitute**, and this is the trap that produced the first wrong version of this
issue. A byte size repeated many times within one symbol-day flags the retry's duplicated output —
but 500 of those 552 symbols are duplicated *complete* files and only 52 are truncated. The size
signature does not distinguish them; the footer does.

## The extent — measured 2026-08-26, a census of the damage domain and not a sample

`dt=2025-03-11` is the OOM outlier ADR-070 records as "the day that completed only on a reduced-
thread retry". It holds **42,943 objects / 53.6 GiB** against a normal day's ~20,400 / 14.6 GiB. The
retry re-ran from the top of the alphabet and died before finishing, **42 times**, each run
re-writing the same prefix.

| | |
|---|---|
| Symbols on the day | 10,168 |
| **Duplicated class** (a size repeated 41–42x) | **552 symbols**, 23,132 objects, 36.1 GiB — spanning exactly `A` … `ASPI`, all of them A-prefixed |
| **Truncated** (footer-checked, all 552) | **52 symbols**, 2,132 objects, **6.1 GiB** |
| Duplicated but complete | 500 symbols — harmless to a median, **not** harmless to a sampler (see below) |
| Control: symbols outside the duplicated class | **80/80 clean** |
| False-negative probe: 5 copies each for 20 "clean" candidates | **100/100 clean** |

The 52:

```
AAON ABCB ABEV ABFL ABLS ABNB ABOT ABSI ACB  ACHR ACTV ACVA ACVF ADT  AEG  AFIF AFRM
AFSC AGCO AGI  AGYS AI   AISP AKYA ALAI AM   AMBC AMD  AMDY AMID AMJB AMKR AMN  AMTM
AMUB AMZA ANET ANSS AON  AOTG APLD APMU APP  APPS APUE ARCC ARE  ARKF ARKO ARR  ASHR ASPI
```

**Every truncated symbol keeps two complete objects** — all 104 footer-checked, all clean. So the
2,132 truncated copies carry nothing that is not also held in a readable file.

**The day dimension is bounded too**, and that was paid for by the build rather than by an audit:
the 100-symbol extract resampled all 40 days successfully once AMD and ANET were removed. A
successful external-table scan must open every file in the partitions it touches, so all 3,998
remaining symbol-days of that universe are readable. The defect is confined to `dt=2025-03-11`.

## Two bounding stories, both wrong, and the one that survives

1. **"Two symbols" — wrong, and wrong by method.** The exclusion list was derived from the failures
   the build happened to hit. That covers what broke, not what is broken; the other 50 were outside
   the hundred by luck.
2. **"The alphabetical head is where more would be" — wrong as stated.** It reads as a bounding
   claim and it is not one: AMAT clean, **AMD truncated**, AMGN clean; ANET and ANSS truncated,
   AOS and APA clean. Truncation is **scattered**, so it cannot be bounded by finding the edges of
   a band, and position predicts nothing.
3. **What survives.** The two claims are each half of the real one. The *damage domain* is
   contiguous and small — the `A` … `ASPI` prefix the failing job re-wrote on every attempt — while
   *which symbols inside it are truncated* is scattered. That is what makes the audit finite: 552
   symbols to footer-check, not 10,081 × 43. It is now done.

## A separate hazard in the same data, for a different consumer

The 500 duplicated-but-complete symbols are invisible to ADR-070 because **a median is invariant
under symmetric duplication** — which is why the 23-symbol extract, carrying AAPL and AMZN, always
built cleanly and correctly. **A print sampler is not invariant.** Sampling raw prints from this
tree over-weights `dt=2025-03-11` by ~42x for those symbols, and seven of them (`AAL`, `AAPL`,
`ADBE`, `AEP`, `AMCR`, `AMZN`, `APA`) are in the shared 100-symbol universe. Anything that samples
rather than aggregates must dedupe that day or exclude it.

## What was done here, and what is left

`scripts/yu17/build-taq-replay-extract.sh` excludes the affected names **that its own ranking domain
can reach** — the S&P 500 reference list intersects the 52 in exactly five: `AMD`, `ANET`, `ANSS`,
`AON`, `ARE`. `BA` and `AFL` take the two places that were filled. The selected 100 is unchanged by
the wider exclusion (verified), so no rebuild was needed. The comment there names this file.

That is a workaround, not a resolution. **The resolution is deleting the 2,132 truncated objects**,
after which the plain wildcard works again with no exclusion list at all and nothing has to stay in
sync. It loses no data — every affected symbol keeps two complete files. It is a write to the shared
corpus and is **yaakov's call, not a lane's**. The object list is reproducible from the method above
in about two minutes; it is deliberately not pinned here, because a stale list of UUIDs is worse than
none.

Deleting them does **not** fix the 500 duplicated-complete symbols, which stay a hazard for samplers.
A re-ingest of that day would fix both, and would be the moment to recover `SYM_SUFFIX` as well.

## Related

- `specs/YU17-otc-rates/system/adr-070-the-tape-is-the-reference.md` — flags `dt=2025-03-11` as the
  OOM outlier and records it "verified rather than excluded" on **sampled** symbols. That finding
  stands and is not contradicted: the sample was the 23-symbol universe, which contains none of the
  52.
- `issues/open/tick-store-drops-taq-sym-suffix-and-merges-share-classes.md` — the other reason a
  symbol is excluded from the replayed universe, and the other defect a re-ingest would close.
