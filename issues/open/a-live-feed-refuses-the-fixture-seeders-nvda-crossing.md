# A live feed refuses the fixture seeder's NVDA crossing, deterministically

**Raised 2026-08-23** while deploying the ADR-045 feed adapter. Measured ahead of the adapter
working (see `issues/open/the-feed-adapter-parses-the-wrong-level-of-the-pricing-envelope.md`), by
sequencing by hand the one thing the adapter does — a `TYPE_PRICE_TICK` at the published price.

## The finding

`scripts/yu15/seed-proof-fixtures.sh` crosses three real trades to give the proofs held positions:

```
hold 10031 42422 NVDA 25 200     # yu06-consumer-halt needs 10031 to HOLD stock
hold 44044 42422 AAPL 10 200
hold 22214 42422 IBM  10 200
```

Since ADR-066 the collar band is centred on the security's sequenced reference and is
**±$65.50 wide** (`DEFAULT_BOOK_LEVELS` 1<<17 × `DEFAULT_BOOK_TICK_PX` 0.001). Once the feed adapter
is live, the reference for every one of these is the publisher's price, not the seeder's 200:

| fixture | published 2026-08-23 | crossed at | Δ | both legs, with a feed tick at the published price |
|---|---|---|---|---|
| NVDA | 916.389 | 200 | 716.4 | **REFUSED `PRICE_COLLAR`** (both) |
| AAPL | 237.893 | 200 | 37.9 | accepted |
| IBM  | 186.782 | 200 | 13.2 | accepted |

Reproduced on fresh tickers running the seeder's exact shape (`/seed @200`, then one `PRICE_TICK` at
the published price, then the `hold()` pair at 200), with the control arm — no tick — accepted:

```
arm B  no feed tick        SELL 25 @200 -> kind=1   BUY 25 @200 -> kind=1
arm A  PRICE_TICK @916.389 SELL 25 @200 -> PRICE_COLLAR   BUY 25 @200 -> PRICE_COLLAR
```

**This is not a flake.** The publisher quotes each instrument every ~4.3s and the seeding pass takes
far longer than that, so NVDA's reference has moved to ~900 well before `hold()` runs, on every
fresh epoch. The proofs that lose their fixture are `yu06-consumer-halt` (10031 must hold stock; it
correctly refuses to run without it) and `yu05-recon`/`yu05-settlement`/`yu15-risk-extract`.

## The fix, and why it was not applied here

Cross at the live price, the same way the seeder already seeds the rest of the universe — the change
`issues/resolved/a-books-price-band-is-anchored-by-its-first-order.md` said should happen "in the
same pass" as whatever closes the enablement gap. It was not applied here because it cannot be
validated yet: the adapter does not work, so a full `run-proofs.sh` sweep with a live feed — the only
thing that would show whether moving those three crossings breaks something else — is not runnable.
Landing an unvalidated fixture change to pre-empt a defect that is not yet live is the worse trade.

**Whoever fixes the adapter must land this in the same change**, and run the full suite with the
adapter up.

## The latent half, worth knowing before it bites

`scripts/proofs/yu10-fix-session.sh` hardcodes `FIX_PX=200` against IBM, with the comment *"IBM at
200 is what seed-proof-fixtures.sh crosses and books reliably"*. Live IBM is 186.78, so it has about
**$52 of headroom** inside the ±$65.50 band before it starts refusing too. It is a random walk. Its
sibling `yu13-cancel-ingress.sh` already reads the live price from price-publisher and falls back
only when that fails — that is the pattern `yu10-fix-session` should adopt, and this is the reason.

Nothing bounds a hardcoded price against a walking reference except the band's width, and the band's
width is the same $131.07 whether the instrument trades at 0.99 or at 900. Bonds and treasuries are
unaffected for that reason; the high-priced equities are where this class lives.
