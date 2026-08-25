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

## BUILT 2026-08-25, NOT YET MINTED — what shipped, and the residual it leaves

The fix is **direction 1 in substance, delivered through direction 2's machinery**, and it is
neither of the two the issue expected: the band stays an absolute distance in ticks, but the TICK is
now derived per book from the collar's own replicated reference by a pure decade map
(`MatchingEngine.decadeTickPx`; `format-8-price-derived-grid-design.md` §2). A book re-derives its
grid at every moment it holds no resting orders, so the band is effectively relative — measured
across all 69 live instruments it lands every half-band between **6.6% and 64.9% of price** — with
no class-keyed constant to drift. The fraction-of-par ticker category still OUTRANKS the map, because
that grid is about six-decimal quote granularity rather than width (design §1.3, with
`CORP-JPM-20310601` trading above par as the live counterexample).

Corrections this closes, against the table above:

- **listed options: fixed, and without an option constant.** Each option book is priced off its own
  live premium, so `$0.504` gets tick 1 (±$0.0655, ~13% of premium) where the ±$65.54 band admitted
  130× the premium. The `OPTION_BOOK_TICK_PX` constant scope §2.1 proposed does not ship at all
  (design §8, settled) — the map is the strike-derived upgrade that section anticipated, obtained
  for free and better, since premium beats strike as a scale proxy.
- **`FNMA`: fixed.** At ~$1.11 it derives tick 10, a ±$0.655 band — 58% of price, against the ±$65.54
  that admitted a 58× fat finger.
- **the worst fat-finger multiple the band admits anywhere is now 1.65×.**

**The residual, narrowed and stated as directive 3 requires.** It is no longer "all sub-$10 equities":
it is exactly **an instrument the feed never prices and that never trades**. Such a book holds the
provisional global grid, and for anything under ~$66 that collar does not bind — and nothing else
does either, since the compensating risk caps are effectively unlimited
(`MAX_ORDER_NOTIONAL_TICKS = Long.MAX/4`). That is the same condition the risk gate already names
(`PRICE_MISSING`), and it self-heals: one tick plus one empty admission puts the book on its real
grid. Recorded rather than accepted silently.

The issue's closing requirement — *"a collar proof must cover a non-equity instrument"* — is met by
`yu17-option-collar` and `yu17-fnma-collar`, with `yu17-fine-grid` and `yu17-book-retick` covering
the grid itself and `yu17-retick-determinism` covering cross-member agreement.

**Still open until the mint.** The build is on `YU17-otc-rates` and tagged
`traderx/cluster-node:yu17-format8`; nothing is deployed, and the five proofs above are red by
design until the format-8 epoch is minted. Move this file to `issues/resolved/` when they go green —
and check the commit's stat line when you do, because `git mv` stages the index blob and a resolve
can otherwise land as a pure rename with the body missing.

## Related

- [ADR-066](../specs/YU17-otc-rates/system/adr-066-price-band-follows-the-market.md) — the band
  follows the market. Correct, and orthogonal to this.
- ADR-057 — bond prices are a fraction of par, which is what puts them off the collar's scale.
- `issues/open/the-treasury-proofs-assert-the-synthetic-seed-not-a-treasury.md` — same lane, same week,
  same shape: a check that cannot fail on the input it is given.
