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
reading. Neither was run.

## Fixed (2026-08-23), and break-tested before being believed

Step 2 no longer asserts a level. It asserts properties of the published curve, read from `/prices`
in one call, that hold under any curve — invented or real — and that a wrong pricer cannot satisfy
by accident:

- **(a)** every bond is on the fraction scale, not one spot-checked instrument;
- **(b)** the zero curve is strictly monotone across the four STRIPs;
- **(c)** shape — a near-dated bill just under par, a 30Y zero deep below it (the file's own comment,
  finally asserted);
- **(d)** a coupon bond is worth more than its own same-maturity STRIP;
- **(e)** every bond carries a finite, plausible yield on the stated basis;
- **(f)** provenance is self-consistent — `simulated` and `source` are two halves of one fact and
  must agree, in **both** arms;
- **(g)** the seed is demoted to what it is still good for: that the instrument was *bootstrapped*,
  not what it is *worth*.

Round-trip (`cleanPriceFromYield` -> `yieldFromCleanPrice`) and monotonicity in yield are deliberately
**not** re-asserted here — `test/treasury-pricing.test.js:111` and `:233` already assert both, over a
wide range and to 1e-9. They are properties of a pure function and need no rig, and asserting them
through HTTP would require a second bond model living in the proof script. Two models drift.

**Break-tested off-rig** against a real `price-publisher` (local NATS, stubbed FRED), five
deliberately wrong models, each run in both arms:

| break | caught by | first failure reported |
|---|---|---|
| control (correct code) | — | **passes in both arms** |
| a percentage leaks onto the wire | (a) | `CORP-F-20320512: price 97.395 is not a par-scale fraction` |
| coupons dropped from the cash flows | (e), (d) | `UST-20280630 0.93236 is not worth more than its own zero` |
| maturity dropped from the discount exponent | (e), (a) | `UST-20460515: price 1.61613 is not a par-scale fraction` |
| `simulated` hardcoded `true` again | (f) | `provenance disagrees with itself — simulated=True but source='fred-us-treasury-cmt-curve'` |
| the long end of the zero curve mispriced | (b) | `zero curve is not monotone: UST-STRIP-20360515 0.64422 <= UST-STRIP-20560515 0.65766` |

The last one exists because (b) and (c) were not the *first* check to fire in any other break: it
multiplies only the long zeros, so every price stays par-scale and every yield stays plausible, and
(b) is the only thing left that can see it. Check (g) was never triggered by a break and is
therefore **not** demonstrated load-bearing.

Two harness bugs were hit and are worth recording, because both produced a **uniform** reading —
the tell that a probe has stopped discriminating:

1. `pkill -f 'node src/main.js'` does not match `node -r ./stub-fred.js src/main.js` — the substring
   is not there. Every break therefore measured the *surviving good server* and reported PASS. Kill
   by PID and wait for the port.
2. `/prices` returns the **stored** quote, and a ticker holds its bootstrap quote until the publish
   loop has ticked it. At the default `PRICE_PUBLISH_BATCH_RATIO=0.25` the provenance break read as a
   pass because nothing had been ticked yet. Set the ratio to 1 and wait two batches.

## Why it was not simply widened

Two repairs were available and both were rejected.

**Widen the band.** The step's real job is catching a percentage leaking onto the wire (99.878 rather
than 0.998780) and the inherited 3dp equity rounding flattening 0.998780 to 0.999000. A band of
0.90–1.10 still catches the 100x error, so nothing would visibly break. But it keeps the seed as the
reference point, which is the fiction, and it asserts strictly less than before. Check (a) now catches
the same 100x error across **every** bond rather than one, and step 3's decimal count catches the
rounding independently.

**Branch on the arm** — keep the tight seed band when `/health` reports `priceSource.provider: none`,
assert something else when it reports `FRED`. This is the tempting one and it is worse: a step that
passes for different reasons in different arms has moved the problem rather than fixed it, and the
synthetic arm keeps asserting the simulation. Every check above passes in **both** arms for the
**same** reason, which is what makes the two arms comparable at all.

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

---

## RESOLVED 2026-08-23 — the proof asserts the curve, and FRED is live behind it

Two commits, and a third defect found on the way.

**`6f781558`** replaced the seed band with properties **of the curve** — true under any curve anyone
can draw, invented or real, and unsatisfiable by accident: every bond on the fraction scale (not one
spot-checked instrument), the zero curve monotone across the STRIPs, a near bill just under par, a
30Y zero deep below it, and each coupon bond worth more than its own same-maturity strip.

**`2cc5f210`** fixed the rewrite's own defect: it fetched `/prices` and piped it into
`python3 - "${UST}" <<'EOF'`. `python3 -` takes its **program** from stdin and the heredoc supplies
it, so the pipe was discarded — under bash the program parses and `sys.stdin` reads **0 bytes**. The
step could not pass for any input, and it failed in the vocabulary of a real curve defect, which
sends you to look at the prices rather than at the plumbing. zsh resolves the same construct a third
way (data and program concatenated, python dies on a syntax error), so the shape is not portable in
either direction. The payload now travels in the environment; stdin belongs to the heredoc.

Swept: this construct appears **exactly once** in `scripts/`. Every other proof uses `python3 -c`
with the program as an argument, which leaves stdin free. Not a class, a one-off.

### Verified live

`FRED_API_KEY` is set on the rig, `price-publisher` runs `:yu17-fred`, and `/health.priceSource`
reports `provider=FRED, points=11/11, asOf=2026-08-20, lastError=null`, with all eleven CMT series
recorded `ok` by the ADR-068 obligation-2 copyright check.

The prediction in this issue held. It estimated a real 30Y zero near 0.22–0.24; the rig prices
`UST-STRIP-20560515` at **0.21558**. The published discount curve now reads
0.927 → 0.810 → 0.638 → 0.216 out to 2056, and divergence from the synthetic walk grows with
maturity exactly as it must: +0.05 pts at the 2Y, **-2.84 pts at the 30Y**.

`run-proofs.sh yu16` — **6 passed, 0 skipped, 0 failed.**

### Worth keeping

**A vacuous FAIL is as expensive as a vacuous pass and is easier to trust.** A check that cannot pass
looks like a discovery; nobody audits a red. The tell is the same one as ever — the reading does not
discriminate. This step reported identical failure for live FRED data, synthetic data, and no data at
all. `vacuous-pass-audit` covers checks that cannot fail; this is the mirror and belongs beside it.
