# Engine requests from the VaR/ES end-to-end proof, November 6

**From:** Yaakov / TraderX · **For:** Alex · **Engine measured:** your upstream `17ac757e8ffe9b180fa6e98ca6114c474ab0e6ad`
(main, October 29), unmodified, local CPU (`uvicorn engine.api.app:app`).

Alex, `scripts/proofs/yu18-var-es-e2e.sh` now books every instrument class TraderX has through real
ingress, cuts it at the sequenced EOD marker, and sends one `POST /runs` (`npv` + `market_risk`, 1,024
Monte Carlo scenarios, 10-day horizon). Everything TraderX could fix on its side now prices, with finite,
non-zero VaR and ES attributed per trade:
- Treasury bills and coupon notes (the notes use the extract's semiannual schedule anchored at maturity).
- The GBP-SONIA swap.
- European, Bermudan and American GBP swaptions, calibrated on the trade's own 12M swap index.

On that book your engine matches an independent ORE/QuantLib reference to 1.6e-13 relative. The
remaining eight refused rows are all on your side, as four requests (R1–R4), plus one scaling finding (R6) and one
observation (R5). Each one below gives the input
TraderX would send, the expected value from an independent ORE/QuantLib computation, and the engine's
actual reply. You already list R1, R2 and R4 as I-07 and R3 as I-05. For R3 we also supply the
convention set you said I-05 is blocked on.

The expected values come from `coordination/alex-engine-requests-20261106/expected_values.py`. It runs in
your engine's own environment (the ORE wheel) on the proof's synthetic market: flat continuous ACT/365F
zero rates, USD discount 4.0%, index 4.1%, as-of 2026-10-26.

## R1: cash equity (and ETFs) on `/runs`, with equity spot as a market-risk factor

- **Input.** One row per position: `{"trade_type": "equity", "trade_id": ..., "symbol": "AAPL", "quantity": 100,
  "multiplier": 1, "currency": "USD"}`. The spot comes from the `market.equities.AAPL.spot` you already parse.
  TraderX supplies the published close. ETFs are the same row.
- **Expected.** `NPV = quantity x multiplier x spot x fx = 100 x 1 x 150 x 1 = 15,000`. With a factor
  `equity:AAPL/spot` shocked lognormally at 30% annual volatility over 10 days, `VaR_99 = 15000 x
  (1 - exp(-2.3263 x 0.30 x sqrt(10/252))) = 1,946.92`.
- **Actual.** `422 union_tag_invalid: Input tag 'equity' found using 'trade_type' does not match any of the
  expected tags: 'swap', 'european_swaption', 'bermudan_swaption', 'american_swaption', 'bond'`.
  `config.market_risk.scenarios.factors` accepts curve pillars only (`RateRiskFactors`), so the spot also
  needs a factor.

## R2: listed equity option (OCC, American exercise)

- **Input.** `{"trade_type": "equity_option", "symbol": "AAPL261218C00150000", "quantity": 1, "multiplier": 100,
  "currency": "USD"}`. The underlying, expiry, call/put and strike are all in the OCC symbol; TraderX can
  send them parsed if you prefer. Market inputs: `market.equities.AAPL` (spot 150, dividend curve 0.5%), plus
  a Black volatility (30%).
- **Expected.** QuantLib `FdBlackScholesVanillaEngine(400, 400)`, American exercise to 2026-12-18: `7.2018`
  per share, `720.18` per contract, delta `0.5401`.
- **Actual.** `422 union_tag_invalid` (as R1).

## R3: USD-SOFR and EUR-ESTR swaps and swaptions (ACT/360 OIS), engine I-05

- **The convention set TraderX books.** `USD-SOFR-1Y-ACT360` means a standard USD SOFR OIS:
  - annual fixed and floating legs, both ACT/360;
  - SOFR compounded in arrears, no lookback or lockout;
  - payment two US government-bond business days after each period end;
  - ModifiedFollowing on the UnitedStates(GovernmentBond) calendar, Forward generation, effective date as booked.

  `EUR-ESTR-1Y-ACT360` is the same on TARGET with ESTR. `USD-SOFR-3M-ACT360` is the quarterly variant.
- **Input.** `{"trade_type": "swap", "trade_id": "sofr-5y", "currency": "USD", "notional": 10000000, "fixed_rate": 0.042,
  "payer": true, "effective_date": "2026-10-28", "maturity_date": "2031-10-28", "index_tenor_months": 12,
  "accrual_day_count": "ACT/360"}`. A new index or convention field is fine; name it and we will send it.
- **Expected.** QuantLib `OvernightIndexedSwap` with the conventions above: NPV `-32,453.80`. The same terms
  with an ACT/365 fixed leg give `-6,543.98`. That 25,910 gap on 10mm is about 6bp of running coupon, so the
  refusal is right and substituting ACT/365 is not an option.
- **Actual.** `400 portfolio[0] ('sofr-5y'): accrual day count 'ACT/360' is not supported; this engine implements
  ['ACT/365', 'ACT/ACT (ICMA)']. It is refused rather than defaulted`. Swaptions have no `accrual_day_count`
  field and accrue ACT/365, so a USD or EUR swaption cannot be sent at all.

## R4: corporate bonds (credit spread), engine I-07

- **Input.** The existing `bond` trade plus a credit input. For example, a flat continuous `credit_spread`
  on the bond, or a named credit curve in `market`. It also needs `accrual_day_count: "30/360"` (US bond
  basis), which corporates accrue on.
- **Expected.** GS 5.750% 2036-01-24, 1mm face, semiannual from maturity, 30/360, on the USD discount curve
  plus a flat 120bp spread: dirty NPV `1,049,185.36`. Discounted on the Treasury curve alone it is
  `1,145,157.51`, a 9.1% overstatement. That is why we refuse it instead of mapping it as a Treasury.
- **Actual.** `422 extra_forbidden: body.portfolio[0].bond.credit_spread`. `30/360` is also not in
  `SUPPORTED_ACCRUAL_DAY_COUNTS`.

## R5 (low priority, an observation): American slightly below Bermudan on identical terms

A GBP payer swaption, 5mm, strike 4.5%, exercisable 2027-10-26 into 2027-10-28 x 2032-10-28. The Bermudan
exercises on expiry and each annual period start. The American's window `[2027-10-26, 2031-10-28]` contains
every Bermudan date. Engine NPVs (USD at 1.27): Bermudan `156,484.59`, American `156,442.93`. By
no-arbitrage the American should be at least the Bermudan. The 41.66 gap (0.027%) is inside a grid
tolerance, so this is an observation only. It may be the 24-steps-per-year American exercise grid against
the Bermudan's exact dates.

## R6: compile time grows superlinearly with portfolio size (`jnp.stack` over every trade)

`engine/risk/market/revaluation.py:72-74` revalues trade by trade, which is good: one cached program per
product and shape. It then joins the results with `jnp.stack([...one array per trade...], axis=-1)`. An
eager `jnp.stack` compiles a fresh `jit_stack` for every operand count, and XLA's CPU compile time for that
grows superlinearly in the count. Measured alone in your pinned environment (`JAX 0.10.2`, CPU, 256-scenario
float64 columns), first call versus cached:

| Operands (trades) | First call | Cached |
|---|---|---|
| 1,000 | 2.46 s | 0.004 s |
| 2,000 | 9.88 s | 0.007 s |
| 4,000 | 47.0 s | 0.015 s |
| 8,000 | 280 s (XLA's own "Very slow compile" alarm) | 0.03 s |

- **Input.** A genuine TraderX book's bonds, GBP/JPY swaps and Europeans tiled ×10, 26,220 trades, one
  `POST /runs` with `npv` + `market_risk` at 256 scenarios.
- **Expected.** Roughly ×26 the 1,000-trade time, about 2.5 minutes; 1,000 swaps take 5.8 s in total.
- **Actual.** Still compiling after 22 minutes, on one core at ~100 MB resident, with the process sample in
  `PjRtCompiler::CompileAndLoad` and LLVM `LoopVectorizePass`. We cancelled it.

Stacking on the host (`np.stack` of `np.asarray` columns), or writing into a preallocated `[scenario, trade]`
buffer, avoids per-count compilation. Any other whole-portfolio `jnp.stack` or `jnp.concatenate` probably
needs the same treatment; the npv path took 58 s for 2,864 trades. This, not memory or the device, is what
caps book size today, so it is worth fixing before any GPU/TPU scale run.

## Not a request, just how we now map things (so you can tell us if we read your schema wrong)

- **Booking account is the holder.** `/swaptions` has no short side, so `long_short` is `Long` and `payer`
  is the underlying's fixed-leg direction.
- **Exercise schedules.**
  - Bermudan: `exercise_dates = [expiry] + every underlying period start after effective, before maturity`.
  - American: `first_exercise_date = expiry`, `last_exercise_date = the last such period start`.
- **Calibration index.** Bermudans and Americans set `config.pricing.{bermudan,american}.swap_index` to
  `{fixed_tenor 1Y, ACT/365, index_tenor_months 12}`, the trade's own index, not the 6M default.
- **GBP-SONIA and JPY-TONA** (ACT/365F) map to your generic term-index swap on a 12M index (`ACT/365`).
  Your I-05 point about overnight compounding applies to these too. On a single projection curve, a
  compounded overnight coupon forecasts as the term forward over the period, so the remaining difference is
  payment lag and calendar. Tell us if you would rather we refuse these until the OIS builder exists.

The proof is `scripts/proofs/yu18-var-es-e2e.sh` on TraderX branch `risk-full-pricing`. When R1–R4 land, it
answers PASSED with no change on our side beyond adding the new fields to the mapping.
