# TraderX to JAX Risk Engine: what is actually shipping, as of 2026-08-17

For Alex, on `AlexNeugroschl/JAX_Risk_Engine`. Written against YU17 (`traderX-YU17-otc-rates`), which
is the tip state and carries every ancestor's work.

**Read this before writing `engine/ingestion/traderx_extract.py`.** Both TraderX planning documents on
your side (`docs/planning/traderx-integration.md`, `docs/planning/traderx-bond-integration-roadmap.md`)
describe a system that has moved. The bond roadmap targets extract **schema 2**; we ship **schema 3**.
The integration plan says trade specifications would need to be supplied; **they already are**, in a
second artifact delivered next to the extract. Three items on your wish list are done.

---

## 1. What you get, per end-of-day session

The EOD run produces **two CSV artifacts from one consensus cut**, both written write-once to GCS and
announced together:

| Artifact | Object | What it is |
|---|---|---|
| Netted position extract | `<key>.csv` | One row per `(accountId, security)`. Positions and marks. |
| Swap/swaption contracts | `<key>-contracts.csv` | One row per booked OTC contract. **Terms only, no valuation.** |

Both carry a `#`-comment preamble stamping `consensusSequence`, `sessionDate`, `priceSnapshotVersion`,
`cutSha256`, and a row count, plus prose conventions for every column. **The preamble is part of the
contract, not decoration.** Several answers you are looking for are in it.

**Announcement**: NATS subject `risk.extract.ready` (override with `RISK_EXTRACT_READY_SUBJECT`). This
is live from YU15 onward, so the "Phase 2, optional" subscriber in your bond roadmap is available now.

**Integrity**: `cutSha256` stamps the cut. The cut is rendered independently by all three cluster
members and is byte-identical across them, and byte-reproducible on replay. Your planned SHA-256
verification is therefore checking something real: a mismatch means genuine corruption, not a benign
rendering difference between nodes.

---

## 2. The swap contracts file: this is new and it is the interesting one

You have not seen this. It exists because your engine's strongest capabilities (swaps, European
swaptions by Jamshidian decomposition, Bermudan and American by LGM backward induction) are exactly
YU17's instrument scope, and it would be absurd for us to describe those positions only as a netted
quantity.

**Schema 2 header:**

```
contractId,accountId,payReceive,notional,fixedRate,floatIndex,effectiveDate,maturityDate,
paymentFrequency,dayCount,currency,counterpartyId,nettingSetId,productType,expiryDate,exerciseStyle
```

**Mapping to your configs:**

| Your field | Our column | Note |
|---|---|---|
| `SwapConfig.notional` | `notional` | Whole currency units of `currency`, not scaled or in millions |
| `SwapConfig.fixed_rate` | `fixedRate` | Annual decimal fraction, six decimals (`0.042000` = 4.2%) |
| `SwapConfig.payer` | `payReceive` | Direction of the **fixed** leg from the booking account's view |
| `SwapConfig.tenor` | `effectiveDate`, `maturityDate` | Explicit dates, so you derive tenor rather than assume it |
| `SwaptionConfig` expiry | `expiryDate` | Populated only for `productType=SWAPTION` |
| Which swaption engine | `exerciseStyle` | `EUROPEAN` \| `BERMUDAN` \| `AMERICAN`, which is precisely your three variants |
| Swaption strike | `fixedRate` | The underlying swap's fixed rate |
| `SwapConfig.spread` | **absent** | We do not book a float spread. Treat as zero or tell us you need it |
| `rate_factor_index` | `floatIndex` **as a string** | See below. This is the one mapping someone must own |

**`floatIndex` is a name, not an index.** Values come from an append-only convention table:
`USD-SOFR`, `EUR-ESTR`, `GBP-SONIA`, `JPY-TONA`, with `paymentFrequency` and `dayCount` resolved from
the same row (`USD-SOFR-1Y-ACT360`, `USD-SOFR-3M-ACT360`, `EUR-ESTR-1Y-ACT360`,
`GBP-SONIA-1Y-ACT365F`, `JPY-TONA-1Y-ACT365F`). Somebody owns the map from these names to your
`rate_factor_index`. Proposal: you own it, because the factor ordering is a property of your
simulation config, not of our booking.

**Two things about that table that matter to an ingestion layer.** It is **append-only**, and an
unknown index throws with "the contract was booked by a later build. Roll forward; do not reinterpret
it." If you pin a convention table on your side, honour the same rule: an unrecognised convention is a
version error, never a default. Silently resolving an unknown convention to the nearest known one is
how a Bermudan gets priced as a European.

**Valuation is absent by design.** No NPV, no PV01, no market value on this file. We state terms; you
state values. That boundary is deliberate and we would like to keep it.

**Netting is `NONE` and none is possible.** One row per booked contract. Two offsetting swaps are two
rows, because netting an OTC contract requires valuation we do not do.

**Lifecycle is not modelled.** Terms are as booked: no resets, no coupon payments, no fixings. See §5,
because this meets a gap you have already named from the other side.

---

## 3. The position extract: schema 3, not 2

**Header:**

```
accountId,security,instrumentType,quantity,contractMultiplier,costBasis,closingMark,markSource,
markQuality,marketValue,unrealizedPnl,currency,counterpartyId,nettingSetId,coupon,maturityDate,
lastCouponDate,accruedInterestFraction
```

### Three things your bond roadmap asks for that already exist

1. **`lastCouponDate` is supplied.** Your roadmap says "ideally TraderX would supply `lastCouponDate`."
   It does, from schema 3.
2. **`accruedInterestFraction` is supplied.** You planned to compute accrual in JAX. You no longer have
   to, though computing it independently and reconciling is a better use of it (see the day-count trap
   below). It is in the same unit as `closingMark`, so `dirtyPrice = closingMark + accruedInterestFraction`
   and `settlementValue = quantity * dirtyPrice`. `marketValue` in the file stays **clean**.
3. **Accrual runs to `sessionDate` itself**, not to a T+1 settlement date, because every other column is
   as-of `sessionDate` and this system carries no holiday calendar. If you want settlement-date accrual,
   you have `lastCouponDate` and `coupon` to roll it forward yourself.

### The trap: `instrumentType` is not just `TREASURY`

The legend is `EQUITY | OPTION | TREASURY | CORPORATE`. Your roadmap assumes bonds arrive as `TREASURY`.
`CORPORATE` and `TREASURY` are both fixed-rate bullet debt and share every bond column, but they carry
**different day counts**:

- `TREASURY` → **ACT/ACT (ICMA)**
- `CORPORATE` → **30/360**

They disagree by real money. On the seeded GS 5.750% of 2036, the same position accrues **0.004514 of
par more** under 30/360, which is **$4,514 on $1m face**. A consumer reconciling against its own model
must use the convention named in the file, not a default.

**The split comes from a reference-data join, never from parsing the security identifier.** Do not
prefix-parse the ticker to decide. If you do not care about the distinction you may treat both as debt,
but you may not treat both as Treasuries.

### Other conventions worth reading off the preamble rather than guessing

- **Bond prices are a FRACTION of par**, six decimals (`0.998780` = 99.878%). The contract multiplier is
  1, so `marketValue = face * fraction`.
- **`quantity`** is a signed net position: contracts for options, shares for equity, USD face for bonds.
- **`costBasis`** is a weighted average trade price, excludes fees, and excludes the contract multiplier.
- **Options are identified by OCC symbol.** Underlying, expiry, call/put and strike are derivable from
  the `security` field, so no extra columns exist for them.
- **`markSource`** distinguishes `EOD_SNAPSHOT` (the published closing price) from
  `CLUSTER_LAST_TRADE_AT_N` (the matching engine's last trade at the cut sequence).
- **`markQuality`** is `OK | STALE | SPIKE | MISSING | OVERRIDDEN`. `OVERRIDDEN` means an operator
  accepted a mark the quality gate had flagged. **This is signal, not noise**: a risk number computed off
  an `OVERRIDDEN` mark deserves a different confidence label than one off `OK`, and you are the only
  party positioned to propagate that.

### Schema versioning

The preamble line `# traderx-risk-extract schema=<n>` is the handshake. Schema 3 **appends** columns to
schema 2, so a named-column parser keeps working and a positional parser breaks. Parse by name, assert
on the schema number, and fail loudly on an unknown one rather than best-effort.

---

## 4. What we do NOT supply, which is most of what your engine needs

Your integration plan lists five required input groups. Four of them are **market data, and we produce
none of it**:

| Your requirement | Do we have it? |
|---|---|
| `EquityConfig.initial_prices` | **Yes.** Equity closing prices, per session, versioned |
| `RatesConfig.initial_zero_curves` | **No.** There is no curve of any kind in this system |
| `RatesConfig.initial_rates`, `theta`, `mean_reversion` | **No.** Hull-White calibration needs a vol surface we do not have |
| `RatesConfig.maturities` (discount pillars) | **No**, and it is a published convention, not a derivation |
| `SimulationConfig.joint_covariance` | **Not directly**, but derivable from our price history. See §6 |
| `EquityConfig.dividend_yields` | **No.** Nothing in the system carries a dividend |

This is the real gap and it is on neither project's roadmap. Your bond roadmap's Phase 2 five-point
Treasury bootstrap is the only bridge currently designed, and you already write that it is "too coarse
to be a trustworthy discounting curve for *other* instruments." Meaning: **it cannot discount the
swaps.** So the swap contracts file, which is otherwise nearly a drop-in for your trade configs, has no
curve to be priced against.

§6 is our answer to what it would take to close that.

---

## 5. One shared blind spot, arriving from both directions

Our swap file states `lifecycle=NOT MODELLED`: terms as booked, no resets, no coupon payments, no
fixings. Your docs name an "aged-instrument discounting gap," where conditional pricing at simulated
times past coupon dates does not correctly represent already-fixed coupons. Your bond roadmap notes it
applies to bonds too.

**These are the same problem meeting in the middle.** A swap booked three months ago has fixings that
have already happened. Our file does not describe them, and your engine does not consume them. Today
that is invisible because the book is young and every contract is effectively new. It becomes wrong
quietly, as a function of time, and it will be most wrong on the instruments with the longest history,
which are the ones a risk number most needs to be right about.

Neither side currently owns this. Naming an owner is cheaper than discovering it in a demo.

---

## 6. What it would take for us to supply market data

Answering the question directly, in tiers, cheapest first. The honest framing up front:

> **TraderX is a trading system, not a market data vendor.** What we can defensibly supply is what we
> *observe*: prices we marked, positions we hold, terms we booked. Curve construction and volatility
> surfaces are a market-data function, and anything we synthesise for them is our assumption wearing a
> market's clothes. The tiers below are ordered by how far each one takes us from observation.

### Tier 0: already there, zero work

- **`EquityConfig.initial_prices`** from the published EOD snapshot, per session, with version and
  quality flags attached.
- **Bond clean prices** with `coupon`, `maturityDate` and `lastCouponDate`, which is what your Phase 2
  bootstrap wants.

### Tier 1: small, real, and improves your data quality (days, not weeks)

**A covariance artifact from our own price history.** We hold one closing price per security per
session in `eod_price_snapshot`, keyed `(session_date, version, security)`, and the published version
per date is unambiguous. Log returns off that grid give a sample covariance matrix.

The reason this is worth doing rather than leaving to you: **a sample covariance computed from a
common date grid with complete overlapping observations is positive semi-definite by construction**, as
a Gram matrix. Your integration plan raises non-PSD correlation input as an expected real-world problem
and asks whether eigenvalue repair should auto-apply. If the matrix comes from one aligned panel rather
than from pairwise estimates assembled from different sources, that problem does not arise. It is worth
producing precisely because it dissolves one of your open questions rather than answering it.

**Caveat we should not bury: our prices are a simulated random walk.** A covariance estimated off them
is a well-formed matrix of noise, with no real cross-asset structure in it. It is correct for wiring the
pipeline, exercising the PSD path, and demonstrating the join. It is not a market view and must not be
presented as one.

**Effort**: one new artifact rendered from existing data, same stamping and delivery pattern as the
extract. No engine change, no consensus change.

### Tier 2: the highest-leverage single change (medium)

**Widen the Treasury universe into a proper tenor ladder** so a bootstrap has pillars: 1M, 3M, 6M, 1Y,
2Y, 3Y, 5Y, 7Y, 10Y, 20Y, 30Y. Today's seeded set is sparse, which is the direct cause of your
"five-point curve is too coarse" limitation.

This is mostly **reference data plus universe entries**, not engine work. It does not touch the
deterministic core, so it does not need a fresh epoch. It converts your existing, already-designed
bootstrap from a demo into something that can plausibly discount a short-dated swap.

**Who should own the bootstrap itself: you.** We supply instrument marks; you build curves. You have
ORE-parity discipline and a reference implementation to check against; we have neither, and a curve we
built would be a second unvalidated implementation for you to reconcile against. Our job is to make the
inputs good and to state their conventions exactly.

**One implementation consequence on our side, which we have just been burned by.** The EOD quality gate
selects a tolerance band by instrument class: 200% for options, 20% for everything else. Adding rates
instruments means a third band, because a rate quote's plausible daily move is nothing like an equity's.
That selection is now asserted by a proof (`yu06-quality-gate.sh` step 6) after we discovered it had no
assertion at all, so the work includes extending that control rather than only the checker.

### Tier 3: rates instruments as a priced asset class (medium-large)

To bootstrap a genuine curve rather than approximate one from bond prices, the system needs rates
instruments it actually marks: deposits, OIS par rates, or futures. That means a new instrument class in
the universe, EOD marks produced for it, quality-gate handling, extract representation, and a published
pillar convention that matches your `RatesConfig.maturities`.

This is real work and it is the point at which we stop being a trading system that reports what it
holds, and start being one that also publishes a curve. Worth doing only if the swaps genuinely need to
be priced for the deliverable.

### Tier 4: volatility. Recommendation, do not

`theta` and `mean_reversion` are calibrated to a volatility surface: swaption vols or cap vols. We have
no vol data, no options on rates, and no way to observe any. The only way we could supply it is to
synthesise a cube in the price publisher, which would make every downstream risk number a function of
our invented assumptions while looking like market-driven risk. That is worse than an honest gap.

**Recommendation**: you calibrate from your own assumed or static vol, and both sides state plainly that
the rates risk numbers are model-assumption-driven rather than market-driven. An acknowledged assumption
is defensible. A synthesised surface presented as data is not.

### Summary of the ask, if we do all of it

| Tier | Deliverable | Effort | Unblocks |
|---|---|---|---|
| 0 | Equity prices, bond terms | done | `initial_prices`, your bond pipeline |
| 1 | Covariance artifact from price history | days | `joint_covariance`, PSD by construction |
| 2 | Treasury tenor ladder | days to a week, reference data | Your bootstrap, properly |
| 3 | Rates instruments as an asset class | weeks, touches the price path and the gate | A real discount curve, hence swap pricing |
| 4 | Vol surface | not recommended | (state the assumption instead) |

**Tiers 1 and 2 together are the cheap majority of the value**, and neither touches the deterministic
core or requires a cluster roll.

---

## 7. Questions back to you

1. **Do you want the swap contracts file as-is**, or should the float spread column be added before you
   build ingestion against it?
2. **Who owns the `floatIndex` to `rate_factor_index` map?** Our proposal is you, since factor ordering
   belongs to your simulation config.
3. **Is the Tier 2 Treasury ladder worth our doing before the swaps have a curve?** It has standalone
   value for your bond pipeline regardless, which is why we would start there.
4. **Who owns the aged-instrument/fixings gap in §5?** It is invisible now and gets worse with time.
5. **Do you want `markQuality` propagated into your outputs** as a confidence label, or dropped at
   ingestion? We think propagated, but you own the output surface.

---

## Appendix: where to look in our tree

| Thing | Path (YU17) |
|---|---|
| Position extract renderer and full conventions | `specs/YU17-otc-rates/.../cluster/RiskExtractCsv.java` |
| Swap contracts renderer and conventions | `specs/YU17-otc-rates/.../cluster/SwapContractCsv.java` |
| Convention table (append-only) | `specs/YU17-otc-rates/.../lmax/SwapConventions.java` |
| Delivery, both objects | `specs/YU17-otc-rates/.../cluster/RiskExtractGcsSink.java` |
| Ready announcement | `specs/YU17-otc-rates/.../cluster/RiskExtractMain.java` |
| EOD price store DDL | `specs/YU16-cdm-instruments/.../manifests/base/database-init-configmap.yaml` |
| Quality gate and band selection | `specs/YU15-eod-risk-extract/.../service/EodQualityChecker.java` |

**Note on layering**: this repo composes `specs/YU*/` layers cumulatively, last wins. The YU17 copy of a
file is the operative one on this branch. An older copy of the same path under `specs/YU15-*/` or
`specs/YU16-*/` is a shadowed ancestor layer and reading it will tell you what an earlier state did, not
what runs.
