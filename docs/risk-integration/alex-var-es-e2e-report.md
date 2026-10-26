# VaR and ES end to end on each instrument: what prices today, what does not

**Date:** 2026-10-26
**From:** Yaakov / TraderX
**Engine measured:** your upstream `f9cc4c16fa843859729a9fb0956dd8cdfd2fa09b`, unmodified, run locally (`uvicorn engine.api.app:app`, CPU)
**Status:** the proof exists and runs end to end; it fails today, by design, on three of the four instrument classes, each named with your engine's own error text.

Alex — you asked for "a test of at least VaR and ES working end to end on each instrument." It is built and committed on `traderX-risk-integration` (`1f7e3996`). One command reruns it against a running engine:

```bash
RISK_ENGINE_ORIGIN=http://127.0.0.1:8000 scripts/proofs/yu18-var-es-e2e.sh
```

## 1. What the proof covers

TraderX side, in one JVM test with the real gateway as a child process and three real cluster members:

- every instrument class we book goes through real ingress: listed equity over REST, a listed equity option (OCC symbol) over FIX 4.4, a Treasury bill over the binary order-entry path, and two OTC swaps over REST (USD-SOFR 1Y ACT/360 and GBP-SONIA 1Y ACT/365F);
- the venue is closed, the sequenced EOD marker lands at consensus sequence N, all three members render the identical cut (same SHA-256 they announced), and the N+1 quiescence witness is checked;
- the production exporters write the fixture: `positions.csv` (six rows: equity, option, bill, two accounts each), `contracts.csv` (two swaps) and the write-once ready receipt;
- negative control: a counterparties table missing one trading account makes the extract refuse. No fixture, no blank.

Engine side (`risk-portfolio-tools/var_es_e2e.py`):

- `eod-risk-bundles` packages the receipt exactly as the EOD path does;
- every position and contract is mapped to a `POST /runs` trade, or refused by name. Nothing is substituted: a day count you do not implement is refused, not defaulted, which is your engine's own rule;
- one run with `analytics ["npv", "market_risk"]`: Monte Carlo shocks of every curve pillar, 10-day horizon, 1,024 scenarios, `outputs.pnl = "inline"`;
- VaR and ES at 99 and 97.5 must be finite and non-zero for the portfolio, and are attributed to every trade from the inline P&L `[scenario, trade]` with the rank rule in `engine/risk/market/var_es.py`; the portfolio VaR recomputed from the columns must equal yours;
- negative control: the same request with the swap's floating-index curve removed must be refused before any run exists. Measured: `400 "trade 'ctr-SW-21' (SwapConfig): no forwarding curve for index 'GBP-SIMINDEX-12M' in GBP; have []"`.

The market is a synthetic flat curve per currency and is labelled so; this proves the path, not the numbers.

## 2. What prices today

| Instrument | Engine trade | VaR 99 | ES 97.5 |
|---|---|---|---|
| Treasury bill `UST-BILL-20270630`, long 100,000 face | `bond` | 373.77 | 387.80 |
| Treasury bill, short 100,000 face (the other account) | `bond` | 372.36 | 373.26 |
| GBP-SONIA 1Y ACT/365F swap, receive fixed 4.5% on 5mm, 3y | `swap` (`accrual_day_count` `ACT/365`) | 109,924.93 | 110,004.63 |
| Portfolio | | 109,924.93 | 110,004.63 |

ACT/365F is Actual/365 (Fixed), which ORE and your schema spell `ACT/365`; the mapping treats that as a spelling, not a substitution.

## 3. What is refused, by class

| Class | Rows refused | Reason, from the engine |
|---|---|---|
| Listed equity (`AAPL`) | 2 | no `/runs` product: `engine/instruments` holds treasury, swap and the three swaptions; `engine/traderx/equity.py` refuses a cash equity as `SPOT_SOURCE_NOT_SUPPLIED` on the `/eod` path |
| Listed equity option (`AAPL261218C00240000`) | 2 | no `/runs` product and nothing on the `/eod` path prices a listed option |
| USD-SOFR 1Y ACT/360 swap (`SW-19`) | 1 | `400 "accrual day count 'ACT/360' is not supported; this engine implements ['ACT/365', 'ACT/ACT (ICMA)']. It is refused rather than defaulted -- a substituted day count prices confidently and wrongly."` |

Every USD swap convention TraderX books is ACT/360 (`USD-SOFR-1Y-ACT360`, `USD-SOFR-3M-ACT360`; EUR-ESTR is ACT/360 too), so today no USD or EUR swap of ours can be priced.

## 4. What we need from you

1. An equity product on `/runs` (spot revaluation, `NPV = signed quantity x multiplier x spot x fx`; the fixture carries quantity, multiplier and currency, and TraderX can supply the spot from the published close).
2. A listed equity option product (OCC symbol; the fixture carries the contract multiplier; strike and expiry are in the symbol).
3. The `ACT/360` accrual day count on swaps, so the SOFR and ESTR conventions price.

When those land, the same command answers PASSED, and the cloud version of the proof (published-close marks, the NATS and gs:// transport, the engine as a pod) becomes worth its lane time.
