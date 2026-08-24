# The price collar cannot bind for any instrument priced in single digits or below par

> A record, not a rig you can query. Re-derive the constants from the tree before acting.

**Filed 2026-08-23 by the coordinator**, from combining two lanes' work. Neither could see it alone:
the collar lane was reasoning about equities, the rates lane about bonds, and the defect lives in the
ratio between them.

## CORRECTED 2026-08-24 — most of the table was computed from the wrong constant

The table below was computed from the **global** `BOOK_TICK_PX` and did not account for ADR-060,
which shipped on the same branch: `MatchingEngineClusteredService` derives a **tick-1 grid** for
every ticker matching `UST-` or `CORP-`, and band width is `levels × tick`, so those rows get a
$0.131 window (±$0.0655), not $131.

- **Treasury note (~0.99): WRONG — binds.** ±$0.0655 is ~±6.6%.
- **30Y STRIP: WRONG — binds.** STRIP tickers are `UST-STRIP-…` (bills `UST-BILL-…`); both match
  `UST-`. Measured on the rig 2026-08-24: `UST-STRIP-20560515` marks 0.215580 → ±30%.
- **Listed options: RIGHT, on live numbers.** OCC tickers match no prefix and get the equity
  grid. `/bbo` premiums measured 2026-08-24 span $0.504 (`AAPL260918P00220000`) to $35.177
  (`MSFT261218P00410000`) — the cheapest is two orders of magnitude inside the ±$65.54 band.
- **Missing row: `FNMA`, an equity.** The publisher's committed bootstrap seed prices it at
  1.12/1.145 (`price-publisher/data/snapshot-prices.json:70`, operative carrier YU16). The rig
  currently marks it 200.000000 only because `scripts/yu15/seed-proof-fixtures.sh` POSTs a flat
  `PRICE:-200` for every fixture ticker — the fixture constant, not the instrument (ADR-067's
  NVDA defect with the sign flipped). No ticker convention can classify a low-priced equity, so
  the category is **price scale, not instrument class**, and any ticker-derived fix carries a
  stated residual here.
- **The "another gate may refuse it first" caveat is resolved for the cluster tier**: the cluster
  gateway screens nothing (ADR-047, verified in `ClusterGatewayMain`) — the ±50% percentage
  pre-screen (`GatewayReplicaStore`) is the retired Spring tier only. On the one rig the engine
  band is the sole price gate for orders; only the bond face-quantity rule (bonds only) sits in
  front of it.

The fix is scoped in `specs/YU17-otc-rates/system/format-8-mint-scope.md` §2 and rides the
SNAPSHOT_FORMAT 7→8 epoch mint (ADR-069, "Epoch consequence").

## The arithmetic

`LimitBook` covers `BOOK_LEVELS` (1<<17 = 131,072) consecutive ticks of `BOOK_TICK_PX` (0.001) —
**a $131.07 window, ±$65.54 around its anchor**. Those are ADR-050's constants and
[ADR-066](../specs/YU17-otc-rates/system/adr-066-price-band-follows-the-market.md) quotes the same
figures.

That half-width is an **absolute price distance**, and it is the same for every instrument.

| instrument | typical price | band around it | does the collar bind? |
|---|---|---|---|
| equity | ~200 | ~134 … ~265 | **yes** — a fat finger at 2,000 is refused |
| listed option | ~3 | ~−62 … ~68 | **no** — 20× the premium is inside the band |
| Treasury note (fraction of par) | ~0.99 | ~−64 … ~66 | ~~no~~ **YES — see the 2026-08-24 correction (ADR-060 grid)** |
| 30Y STRIP | ~0.22 | ~−65 … ~66 | ~~no~~ **YES — see the 2026-08-24 correction (ADR-060 grid)** |
| FNMA (equity, seeded 1.12) | ~1.15 | ~−64 … ~67 | **no** — added 2026-08-24; no prefix can ever match it |

A price cannot be negative, so for anything trading below roughly $65 **the band's lower half is
unreachable and its upper half is far beyond any plausible error.** The collar is structurally
inert for those classes.

## Why this survived

- **It is not a regression.** ADR-066 changed *where* the band sits and made it follow the market. It
  did not change *how wide* the band is, and width relative to price was never the question being
  asked.
- **Every proof that exercises the collar uses equities.** `yu03-risk-proof`, `yu10-fix-session`,
  `yu13-cancel-ingress`, `yu13-stp-and-replace` — all equity-priced, all in the range where the band
  works. The instrument classes where it does not bind are exactly the ones no collar proof covers.
- **Nothing fails.** An inert guard accepts everything, which is indistinguishable from a guard that
  is working on well-behaved input.

## What it costs

A fat-finger order on a bond, a strip or an option is accepted where the same relative error on an
equity is refused. For a system whose stated direction is a sell-side OMS — correctness, risk,
compliance — a price guard that covers one instrument class and silently abstains on the rest is a
worse position than not having one, because the protection is *assumed* rather than *absent*.

The multiplier makes it sharper for options: a $3 premium at 100 contracts is a $300 notional error
per lot at the wrong price, and ADR-057's multiplier gate is a *risk* control, not a *price* one.

## Status of the evidence

**Measured:** the constants, from the tree and from ADR-066's own text. The arithmetic above.

**NOT measured, and it should be before anything is built:** that a fat-finger bond or option order is
actually accepted end to end. Another gate may refuse it first for an unrelated reason — a lot-size
validator already answers a *different* 422 on bonds below quantity 100, which is precisely the kind
of thing that makes a probe look like a verdict about the collar when it is not. **Probe it directly,
at a quantity that clears the other validators, and confirm which gate answers.**

## Directions

1. **A relative band** — ± some percentage of the reference rather than an absolute distance. This is
   what venues actually do, and the reference already exists after ADR-066. It changes the
   deterministic core: no gradual roll, fresh epoch, and it re-opens a proof that was just closed.
2. **A per-class band width** — keep absolute distance, scale it by instrument class. Cheaper, and
   fits the existing grid, but adds a class-keyed constant that will drift from reality the way
   hardcoded prices did.
3. **Accept and state it** — the collar is an equity control, documented as such, with the other
   classes relying on the risk gate instead. Legitimate only if written down; otherwise the
   protection is presumed by every future reader.

Whichever is taken, a collar proof must cover a **non-equity** instrument, or the same blind spot
returns on the next instrument class.

## Related

- [ADR-066](../specs/YU17-otc-rates/system/adr-066-price-band-follows-the-market.md) — the band
  follows the market. Correct, and orthogonal to this.
- ADR-057 — bond prices are a fraction of par, which is what puts them off the collar's scale.
- `issues/open/the-treasury-proofs-assert-the-synthetic-seed-not-a-treasury.md` — same lane, same week,
  same shape: a check that cannot fail on the input it is given.
