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

## The trap, recorded because it already cost a build

**Do not take the universe from `GET /prices`.** A quote's `source` only reads `taq-replay-2025-02`
after that ticker's first tick, and the publish loop ticks a fraction of the universe per interval
(`PRICE_PUBLISH_BATCH_RATIO`), so a publisher that has just rolled reports a universe still filling
in. Measured 2026-08-26: two reads minutes apart returned 23 symbols and then 19, and the 19
silently became a built artifact. The declared `PRICE_TICKERS` env is the stable statement.
