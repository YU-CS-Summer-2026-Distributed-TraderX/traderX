# The fixture seeder enables only equities and options — bonds, treasuries and ETFs are still absent

**Filed 2026-08-21**, measured on `kind-traderx-yu12-cluster`/`traderx` while closing
`issues/resolved/an-epoch-roll-silently-drops-instrument-classes.md`. **Open.** Same defect class as
that one, same symptom, same reason it hides — a different set of instruments.

## Correction to the resolved issue as originally filed

That issue said the seeder "seeds **equities and bonds** only". It seeded **equities only**.
`TICKERS` in `scripts/yu15/seed-proof-fixtures.sh` is 20 equity symbols and nothing else. Bonds have
never been in it; they are enabled because `yu16-bond-position.sh` and `yu16-treasury-pricing.sh`
each `POST /seed` their own instrument as a setup step — which is precisely the shape the resolved
issue's own principle rejects: *the tradeable set depends on which proofs happened to run*.

## Measured

On a fresh epoch on which only `seed-proof-fixtures.sh` (already carrying the option fix) and
`yu15-option-persistence.sh` had run — no bond proof:

```
BAC                Sell 1   @ 200    -> {"orderRef":13,"kind":1}                       HTTP 200
UST-20310630       Sell 100 @ 0.9966 -> {"orderRef":14, ... "UNKNOWN_SECURITY"}        HTTP 422
UST-BILL-20270812  Sell 100 @ 0.9966 -> {"orderRef":15, ... "UNKNOWN_SECURITY"}        HTTP 422
CORP-IBM-20330215  Sell 100 @ 0.9966 -> {"orderRef":16, ... "UNKNOWN_SECURITY"}        HTTP 422
```

Same hiding mechanism as the option case: `price-publisher` quotes all of them, so the instruments
look live, and `UNKNOWN_SECURITY` reads as "wrong ticker".

**Lot size masks the reason on a first attempt.** At `quantity 1` all three answer
`{"error":"Bond quantity must be at least 100."} HTTP 422` — a *different* 422, from a different
validator, which never reaches the enablement check. Probe bonds at 100+ or the enablement question
is not being asked at all.

## Why the obvious fix was NOT applied

Seeding everything `price-publisher` quotes would exhaust the symbol table on the historical builds
the suite deliberately rolls onto. Counted from `GET /prices` on 2026-08-21 — **68 quoted
instruments**: 20 equities (seeded), 24 options (seeded by the fix), 19 bonds/treasuries/strips/
corporates and 5 ETFs (`SPY QQQ IWM VTI GLD`), none of the last 24 seeded.

`MAX_SECURITIES` is 64 on the `yu15-pre` / `yu15-stp` builds that `yu13-stp-and-replace` rolls the
members onto with a fresh epoch, and `run-proofs.sh` carries a whole block about that exhaustion
(the 510-security control-feed replay hitting a 64-capacity engine: registrations refused with
`id = -1`, surfacing as a fast `422 {"seeded":false}`). The option fix took the count from 20 to 44,
still comfortably inside 64. **Seeding the remaining 24 would take it to 68 — over the limit, before
that proof mints its own tickers on top.** That is a real regression, not a hypothetical one.

## So this needs a decision, not a patch

Roughly, in increasing cost:

1. **Seed the bond/treasury/corporate class only** (19, total 63) and leave the 5 ETFs out. Fits
   under 64 by one — which is exactly the kind of margin that fails silently the day someone adds an
   instrument to `instruments.csv`. If this is taken, the seeder should assert its own count against
   the historical limit rather than discover it as a `{"seeded":false}` two proofs later.
2. **Scope the seeded set per epoch**: full universe normally, reduced set for the epoch
   `yu13-stp-and-replace` mints. Reintroduces "the tradeable set depends on which path ran", which
   is the thing being fixed, but does so *deliberately and in one place*.
3. **Retire the historical-build arm of `yu13-stp-and-replace`**, or rebuild those images with a
   larger `MAX_SECURITIES`. Removes the constraint at the cost of the proof's whole point (epoch
   continuity across a real version boundary) or of a rebuild of two pinned historical images.

## Also not run

`scripts/proofs/yu16-bond-position.sh` had a confirmed output-corrupting bug fixed in the same
commit (`sql()` exec'd `deploy/eod-price-db` without `-c mariadb`, so kubectl's `Defaulted
container` warning was folded into all twelve of its captured comparisons by its `2>&1`). That fix
is **read off the source and not exercised** — the proof has not been run since. It is a strict
improvement over a proof that could not have been comparing anything correctly, but nobody has
watched it pass.
