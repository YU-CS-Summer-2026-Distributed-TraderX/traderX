**Proposal: TraderX → JAX Risk Engine EOD handoff**

We are focusing on end-of-day integration: TraderX freezes the trading portfolio, your engine consumes it for overnight batch risk, and TraderX imports the results the next morning.

**What TraderX already exports**

Two complementary CSV artifacts come from the same consistent EOD cut:

| Artifact | Coverage | Available fields |
|---|---|---|
| **Positions — schema 3** | Equities, listed options, Treasuries, corporate bonds; signed holdings per account/security | Account, security, instrument type, quantity, multiplier, cost basis, closing mark, mark source/quality, market value, unrealized P&L, currency, counterparty/netting identifiers; bond coupon, maturity, last coupon date, and accrued-interest fraction |
| **OTC contracts — schema 2** | Individual swaps and swaptions, preserved separately even when economically offsetting | Contract/account IDs, pay/receive direction, notional, fixed rate, floating index, effective/maturity dates, payment frequency, day count, currency, counterparty/netting identifiers; swaption expiry and exercise style |

Both artifacts carry the session date, consensus sequence, price-snapshot version, and shared cut hash. Each exported file also has its own integrity hash.

Important conventions: swap fixed rates are decimal fractions; bond prices are fractions of par. OTC exports contain contract terms, not calculated NPVs or Greeks.

**Proposed delivery additions**

We propose an immutable bundle in a private Google Cloud Storage bucket, with a versioned manifest containing:

- Unique bundle/run identity, including cluster epoch.
- Business date and explicit valuation timestamp.
- Portfolio-file locations, schemas, row counts, and hashes.
- References to the agreed market-data snapshot.
- Requested calculations and reporting currency.

Corrected EOD inputs would create a new bundle/version. Your engine would return results referencing the exact input bundle.

**Market data and known gaps**

TraderX has closing marks, historical equity TAQ, and a Treasury reference-yield reader. A complete, versioned pricing-market bundle is **proposed work**, not currently available. Please specify the curves, fixings, volatility data, and historical windows your batch requires, and whether you want observations or constructed curves.

Some contract details also require agreement: full SOFR scheduling/compounding conventions, option settlement/exercise terms, and lifecycle state. Current OTC exports retain terms as booked; payments, resets, exercises, and terminations are not modelled. Export coverage therefore does not imply every contract is ready for accurate valuation.

**Requested response from your side**

Please propose:

1. Which instruments and calculations you can support first.
2. Required additional contract and market-data fields, including units.
3. How your batch would consume this bundle and report completion/failure.
4. A morning-result contract with input-bundle identity, per-position/contract results, portfolio totals, units, model/precision metadata, and explicit unsupported or failed items.

Please distinguish what your engine already supports from additions you would implement. We can then reconcile both proposals into two agreed interfaces.