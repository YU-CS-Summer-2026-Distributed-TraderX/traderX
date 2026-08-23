# The Treasury proofs assert the synthetic seed, not a Treasury

**Raised 2026-08-23** while implementing [ADR-068](../../specs/YU17-otc-rates/system/adr-068-external-price-sources.md)
— the real U.S. Treasury constant-maturity curve behind `price-publisher`. Not a defect in the
integration; a property of the proofs that only becomes visible once a real price exists to compare
them against.

## What the proof actually asserts

`scripts/proofs/yu16-treasury-pricing.sh` step 2:

```bash
SEED_PERCENT="99.878"          # the auction-derived seed, as TreasuryDirect quotes it
BAND_PERCENT="0.15"            # the 2Y term profile's total band (FR-CDM18)
...
low, high = (seed_pct - band_pct) / 100.0, (seed_pct + band_pct) / 100.0
sys.exit(0 if low <= price <= high else 1)
```

Both constants come from the **synthetic walk**: `runtimeSeedCleanPrice` out of
`price-publisher/data/snapshot-prices.json`, and `TREASURY_PROFILE_BY_TERM[2].maxDistance` out of
`treasury-pricing.js`. The band is the walk's own clamp. So the assertion reads "this price came out
of our random walk", written in the vocabulary of "this price is a plausible Treasury".

Those two claims were the same claim for as long as the walk was the only source. They are not the
same claim any more.

## What it costs

With `FRED_API_KEY` set, `UST-20280630` (4.125% of 2028-06-30) prices off the interpolated
constant-maturity curve rather than off the 99.878 seed. Measured off-rig against a plausible curve
(2Y 3.80 / 1Y 3.95, interpolated at the bond's 1.85y remaining term):

```
                      no key (walk)            with key (curve)
UST-20280630          0.99879                  1.00533        <- outside 99.878% +/- 0.15
UST-BILL-20261112     0.98970                  0.99077
UST-STRIP-20560515    0.22014                  0.24089
```

`1.00533` is a **correct** price for that bond on that curve — the published `ytmPercent` solves
back to 3.8219, which is the curve point that produced it. The proof fails on it anyway. The failure
is the proof's, and the direction matters: it fails **loudly and only in the with-key arm**, which is
also what makes it the cheapest available discriminator between the two arms.

The other two Treasury proofs were **not** measured against a real curve on a rig (see "What is not
known" below). `yu16-bond-position.sh` and `yu16-accrued-interest.sh` do not hardcode a seed band, so
there is no reason on inspection to expect them to break — but "no reason to expect" is not a
reading.

## Why it is filed rather than fixed

Changing step 2 to a band wide enough for a real curve weakens the assertion it exists for. Its whole
job is catching **a percentage leaking onto the wire** (99.878 instead of 0.998780) and **the
inherited 3dp equity rounding** flattening 0.998780 to 0.999000. A band of, say, 0.90–1.10 still
catches the 100x error, and step 3's decimal-count check catches the rounding one independently — so
the property survives a widened band. But the *right* fix is probably to assert against
`price-publisher`'s own `/health` `priceSource.provider`: in the synthetic arm keep the tight seed
band, and in the real arm assert the price round-trips to a yield inside the published curve's range.
That is a proof-authoring decision, and the person who owns the YU16 proofs should make it rather
than a lane passing through.

The same shape as the fixture seeder's break, already documented in
`issues/open/a-live-feed-refuses-the-fixture-seeders-nvda-crossing.md`: a fixture that pinned a
*number* rather than a *property* stops being true the moment the number becomes real. ADR-068's
"Costly / risky" section predicted exactly this class and it landed exactly there.

## What is not known

The with-key arm has **never been run on a rig** — no FRED API key exists (registration is yaakov's,
a human step). Everything above is measured off-rig with a stubbed FRED responding with plausible
constant-maturity values, driving the real `fred-curve.js` and the real `main.js` in one process.
That proves the wiring, the interpolation and the composition with `treasury-pricing.js`. It does not
prove the live HTTP path, the real series' shape, or how the collar behaves when a Treasury's
reference moves for the first time.

## Related

- [ADR-068](../../specs/YU17-otc-rates/system/adr-068-external-price-sources.md) — the decision this
  implements; its rule 1 is why the no-key arm must stay untouched.
- [ADR-066](../../specs/YU17-otc-rates/system/adr-066-price-band-follows-the-market.md) — the collar
  follows the reference, so a Treasury whose price now moves for real moves its own band with it.
- `issues/open/a-live-feed-refuses-the-fixture-seeders-nvda-crossing.md` — the same class, one
  instrument tier over.
