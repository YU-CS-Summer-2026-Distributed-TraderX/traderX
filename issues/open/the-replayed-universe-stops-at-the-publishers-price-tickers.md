# The tape extract carries 100 symbols; the publisher prices 23, so the replay reaches 23

**Filed 2026-08-26** on YU17, alongside the ADR-072 implementation. Not a defect in either
component — a gap between two lists that nothing currently reconciles, and it is invisible from
either side alone.

## The state of it

| | |
|---|---|
| `gs://.../replay/taq-replay-2025-02/extract-v1.json.gz` | **100 symbols** (widened 2026-08-26) |
| `PRICE_TICKERS` on the `price-publisher` Deployment | **25 equity/ETF names**, of which 23 are on the tape (GOOGL and FNMA are the deliberate ADR-070 exclusions) |
| Symbols the publisher therefore carries a tape price for | **23** |
| Symbols ADR-072's replay submits orders for | **23** — it will not trade a symbol with no tape reference, deliberately |

So 77 symbols of measured, resampled February 2025 reference data are in the bucket and reach
nothing. The replayed order rate is sized against the 23 (6.1/s, inside ADR-072's band), so nothing
is broken — the demo is just narrower than the data.

## Why the replay refuses the other 77 rather than trading them

A replayed print submitted for a symbol the publisher does not tape-price would enter a book whose
collar reference is still the synthetic walk: real February prices judged against an invented band.
That is the one combination ADR-070 and ADR-072 both forbid, so `print-replay.js` intersects the
sample with the tape-priced universe and reports the shortfall as
`/health.printReplay.unpricedSymbols`. The refusal is correct; the gap is what wants closing.

## What closing it costs

1. Widen `PRICE_TICKERS` in `specs/YU17-otc-rates/generation/kubernetes/cluster/eod-chain.yaml` to
   the extract's universe. The publisher's flush already carries ~69 instruments; ADR-070 sized the
   sequenced tick rate against that, so **adding 77 equities changes the flush size** and the sizing
   argument in ADR-070's "Sizing, measured" needs re-checking rather than assuming.
2. Re-run `scripts/yu17/build-taq-print-sample.sh` with the new `PRICE_TICKERS`. It solves prints
   per window for a target rate, so a wider universe lowers the slot count automatically and the
   artifact stays the same size — the transport ceiling is a rate, not a universe (see ADR-072).
3. Every one of those books then comes alive, which is the whole point of ADR-072.

## Measured 2026-08-27, which discharges step 1's "re-check rather than assume"

**The flush does not get bigger — it gets more frequent.** `FeedAdapterMain.flush()` iterates the
conflated tick map and calls `offerTick()` **once per ticker**, each into a fixed
`AeronReplicationCodec.INPUT_BYTES` buffer. There is no batched flush message, so a wider universe
cannot push any single message across a fragmentation boundary. What it changes is the sequenced
message RATE, proportionally: ~69 instruments per 15s (~4.6/s) today, ~145 (~9.7/s) at the widened
list. Against a cluster already absorbing ~6/s of replayed orders plus their fills, that is
immaterial — but it IS a change to ADR-070 decision 1's "byte-for-byte identical" bound, which
covered the replay changing the CONTENT of the flush and not the size of the universe.

**Precondition state, measured the same day:** the median extract now carries **100** symbols (the
tape-extract widening landed), and **99 of the 100 already exist in `stocks`** (605 rows). The one
exception is **`DOC`** (Healthpeak Properties), which is in the extract and not in the rig's
reference data. It is left OUT of the widened `PRICE_TICKERS` rather than seeded: the replayer
refuses a symbol with no tape price, and the self-heal treats `UNKNOWN_SECURITY` as healable — so
including it would publish an instrument the read model has no row for, which is the same shape as
the `trades.accountid` foreign-key break ADR-072 already paid for once. One REIT is not worth that.

**The artifact is built and sized.** 99 symbols x 40 days x 120 windows x 1 slot = **845,881 bytes
gzipped** (1,901,620 raw), inside the 1,048,576-byte Secret ceiling. Note the slot count fell from 4
to 1 as the universe grew 23 -> 99 and the achieved rate held: this is the "the ceiling is a rate,
not a universe" arithmetic doing exactly what ADR-072 says it does.

## The trap, recorded because it already cost a build

**Do not take the universe from `GET /prices`.** A quote's `source` only reads `taq-replay-2025-02`
after that ticker's first tick, and the publish loop ticks a fraction of the universe per interval
(`PRICE_PUBLISH_BATCH_RATIO`), so a publisher that has just rolled reports a universe still filling
in. Measured 2026-08-26: two reads minutes apart returned 23 symbols and then 19, and the 19
silently became a built artifact. The declared `PRICE_TICKERS` env is the stable statement.
