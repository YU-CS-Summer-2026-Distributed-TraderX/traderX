# Every class priced on TraderX's engine fork: results, November 7

**Engine.** TraderX's fork `traderx/engine-fork`. The proof is pinned to `24173899a2fdec1ec5e6180208a9503733d65d87`
(the engine lane's commit on top of `b90af35`; same figures and parity as `b90af35`, re-run on both), cut from
Alex's `17ac757`. Alex permitted the fork. The commit is a WIP, and the engine lane (session "Risk engine
fork: R1–R6 and remaining gaps") now owns the fork. It is served from a read-only clone of that commit, so
the run reports it unmodified. It runs on local CPU (macOS arm64, shared with other sessions' builds).

**Scope.** The market is synthetic (`financial_validation=false`): flat curves, equity spots from the cut's
closing marks, a 30% volatility and 0.5% dividend yield per name, and a 120bp spread per corporate.

**References.** Specs: FR-RP-VARES-06/07 and the fork ADR in
[risk-pipeline/spec.md](../../specs/YU18-risk-integration/components/risk-pipeline/spec.md). What the fork
added: [the requests, with their implementation status](../../coordination/alex-engine-requests-20261106.md).

## 1. The proof: every class, no refusal

`scripts/proofs/yu18-var-es-e2e.sh` **PASSED**: 17 trades, VaR99 and ES97.5 finite and non-zero for every
one. Portfolio VaR99 373,857, ES97.5 381,255, on 1,024 scenarios over a 10-day horizon.

| Class | Rows | NPV (one side) | VaR99 (long / short) | Reference agreement |
|---|---|---|---|---|
| Cash equity AAPL | 2 | 15,000 | 1,928 / 2,226 | exact (3.6e-12) |
| OCC option AAPL261218C00150000, American | 2 | 720.14 | 623 / 1,670 | QuantLib FD 800x800, 2.7e-5 relative |
| Corporate GS 5.750% 2036, 30/360 + 120bp | 2 | 1,049,185.36 | 42,091 / 44,078 | QuantLib spreaded `FixedRateBond`, 4.7e-10 |
| Treasury bill and note | 4 | 97,329 / 1,024,776 | 364-17,549 | ORE oracle, 1.5e-8 |
| USD-SOFR OIS 5y | 1 | -32,453.80 | 283,716 | QuantLib `OvernightIndexedSwap`, 2.3e-9 |
| EUR-ESTR OIS 7y (new booking) | 1 | -54,491.06 (USD) | 337,557 | QuantLib, 5.3e-9 |
| GBP-SONIA OIS | 1 | -36,227.77 (USD) | 102,853 | QuantLib, 1.5e-10 |
| GBP swaptions, European / Bermudan / American | 3 | 120,449 / 156,485 / 157,229 | 71,545 / 70,647 / 70,657 | European: QuantLib Bachelier 6e-10; European ≤ Bermudan ≤ American |
| USD-SOFR European swaption | 1 | 129,297 | 86,011 | QuantLib Bachelier on the OIS, 1.0e-9 |

Every new input has a negative control, and each was refused with a 400 naming the input before any run
existed:

- the OIS index curve;
- the equity's spot;
- the option's volatility;
- a spread bond's Greeks.

A run whose engine is not the pinned fork commit, or reports itself modified, fails the proof. That check
did its job twice on the way:

- a replayed run from an uncommitted tree was caught, so the idempotency key now includes the engine
  revision;
- the live fork worktree, being edited by the engine lane, was caught, so the engine is now served from a
  clone.

The American now prices above the Bermudan (157,229 against 156,485; it was 41.66 below on November 6),
because it also exercises on the period starts (R5).

## 2. R6: the 26,220-trade tile

The same request as November 6: the genuine book's bonds, swaps and Europeans tiled ×10, `npv` +
`market_risk` at 256 scenarios, through the unchanged November 6 tools.

| | November 6 (Alex's `17ac757`) | November 7 (fork) |
|---|---|---|
| Engine time | still compiling at 22 min, cancelled | **87.6 s** |
| Peak RSS (API + worker) | ~0.1 GiB, compiling | 0.9 GiB |
| VaR99 | — | 449.9mm, finite |

## 3. The genuine large book, every class

The November 6 book (seed 20261106, cut seq 16,706, `cutSha256` `1c89f41d…eefc8`), every class now mapped,
`npv` + `market_risk` at 256 scenarios through `large_book.py`, engine `b90af35` from a clean clone
(`modified=false`). Every row that holds a position prices.

| | November 6 (Alex's `17ac757`) | November 7 (fork) |
|---|---|---|
| Priced trades | 2,864 | **11,360** |
| Refused | 8,500 (R1–R4) | 4, all zero-quantity flat options (not instrument gaps) |
| Engine time | 1,496 s | 5,949 s (99 min) |
| Peak RSS (API + worker) | 2.8 GiB | 3.4 GiB |
| VaR99 / ES97.5 | 36.18mm / 37.34mm | 73.56mm / 73.80mm |
| VaR97.5 / ES99 | 32.89mm / 43.38mm | 54.81mm / 89.62mm |

- **Mix.** 2,113 equities, 3,326 OCC options, 1,921 bonds (Treasuries and corporates), 2,594 swaps,
  812 Europeans, 453 Bermudans, 141 Americans. That is all 7,364 position rows less the 4 flat ones, and all
  4,000 contracts. Every trade NPV is finite; portfolio NPV 1.626bn.
- **Correctness.** `ore_parity.py --sample 300`: 285 checked against a reference, all agree.
  - Equities 52, exact (5.9e-16 relative).
  - Options 84 against QuantLib FD, max 1.1e-4 relative (86.66 on a large position), inside the 3e-4 bound.
  - Bonds 55 (35 ORE oracle, 20 spreaded corporates) 1.8e-13; swaps 71 6.2e-13; Europeans 23 2.2e-14.
  - The 15 Bermudans and Americans in the sample have no reference (§4).
- **Time.** At the November 6 cost of ~30 s an American, the 141 Americans alone are ~70 minutes; the
  calibrated swaptions, not the new classes, set the run's length.

Not yet re-run on `2417389`; the engine lane reports it reproduces `b90af35`'s figures, as the proof did.

## 4. Gaps not closed

| Gap | Why it stays open |
|---|---|
| An OIS coupon in progress on the EOD date is refused | It needs that period's realised compounding, and TraderX has no overnight fixings source. Today every booking's effective date is after the cut; a seasoned book will hit this. |
| Volatility, dividend yield, FX and credit spread are held in VaR/ES (the result warns) | The factors are rate pillars and equity spots only. Adding them is a model-scope decision, not a mapping. |
| An option whose underlying has no EQUITY row in the cut is refused (owner `traderx`) | There is no spot without a holder of the name. Production needs the published EOD price snapshot in the bundle. |
| OIS swaptions calibrate on generic-index helpers with the trade's conventions | The helpers approximate the OIS swaption quotes. The surface is synthetic anyway. |
| OIS Bermudans and Americans have no ORE reference here | Checked by the no-arbitrage bounds only. |
| An expired swaption is worth 0 | TraderX models no exercise (`lifecycle=NOT MODELLED`), so an in-the-money expiry never becomes its swap. |
| The exposure cube still stacks per trade count | That path is exposure-only and outside EOD VaR/ES. It is the engine lane's to fix. |

## Reproduce

```bash
S=<scratch>; git clone --no-checkout ~/dev/lmax/jax-risk-traderx $S/snap && git -C $S/snap checkout b90af35
(cd $S/snap && PYTHONPATH=$S/snap JAX_RISK_JOB_QUEUE=$S/q/jobs.sqlite3 <engine venv>/bin/python -m uvicorn engine.api.app:app --port 8766)
RISK_ENGINE_ORIGIN=http://127.0.0.1:8766 scripts/proofs/yu18-var-es-e2e.sh "$(mktemp -d /private/tmp/var-es.XXXXXX)/out"
<engine venv>/bin/python -I specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools/ore_parity.py \
  --engine-source $S/snap --request OUT/engine/request.json --reply OUT/engine/reply.json
```
