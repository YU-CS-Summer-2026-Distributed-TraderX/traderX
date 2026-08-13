# TraderX ↔ JAX Risk Engine: current state and integration points

**Status as of 2026-08-12** (extract at schema 3). Supersedes the instrument-mismatch framing in
[`risk-engine-integration-fit.md`](risk-engine-integration-fit.md), which was written before YU16
landed and states that we trade nothing his engine prices. That is no longer true.

Facts about our side are verified against the repo. Facts about the JAX engine are read from its
public repository and docs, not run.

---

## 1. Where each system is

### TraderX (this repo)

| | |
|---|---|
| Tip state | `YU16-cdm-instruments`, cut from YU15 |
| Architecture | LMAX Disruptor matching engine as a deterministic state machine, replicated across a 3-member Aeron Raft cluster |
| Throughput | ~260k orders/s end to end, one order per request; ~6M/s in the matching core alone |
| Latency | consensus commit ~220µs, flat across a 6× load sweep; p50 client RTT under 1.5ms |
| Verification | 21 acceptance proofs, ~300 unit and integration tests, all in CI |

**Instruments held:**

- Cash equities
- Listed equity options (OCC symbols, ×100 multiplier)
- **ETFs** — SPY, QQQ, IWM, VTI, GLD (CDM Equity / Fund)
- **Fixed-rate US Treasuries** — `UST-20280630`, `UST-20310630`, `UST-20360515`, `UST-20460515`,
  `UST-20560515` (CDM Debt), maturities spanning 2028 to 2056, real FIGIs, TreasuryDirect auction
  prices seeding the simulation

**Bond conventions** (decided in YU16, binding across the publisher, engine, read model and extract):

- Quantity is **face amount**
- Price is stored as a **fraction of par**, never a percentage: `99.886%` → `0.998860` → `998,860`
  ticks at the 1e6 scale
- Value is `face × fraction`, with no `÷100` anywhere, so the integer contract multiplier stays 1
  and the deterministic core needs no divisor
- Prices and `marketValue` are **clean**. Accrued interest is no longer excluded: schema 3
  (ADR-061) emits `lastCouponDate` and `accruedInterestFraction` alongside, as a fraction of par,
  so `closingMark + accruedInterestFraction` is the dirty price. Accrual runs to `sessionDate`,
  not to a settlement date — we carry no holiday calendar, and `lastCouponDate` is there so the
  consumer can roll it forward under its own calendar

### JAX Risk Engine (`github.com/AlexNeugroschl/JAX_Risk_Engine`)

GPU-accelerated pricing and risk in JAX, built to mirror ORE (Open Source Risk Engine), with parity
checked against ORE's C++ source rather than its documentation.

**Implemented:**

- Cross-asset market simulation (rates, equities, FX), Hull-White short rates, Sobol quasi-MC
- Interest rate swaps
- European swaptions (Jamshidian decomposition)
- Bermudan and American swaptions (numeric LGM backward induction)
- Value at Risk and Expected Shortfall
- **Greeks (added 2026-08-11)** — delta and gamma **per curve pillar** via automatic
  differentiation of NPV with respect to each pillar's zero rate, scaled to 1bp to match ORE;
  theta by forward-difference repricing; vega on Bermudan/American only

**Planned, not built:** XVA (CVA/DVA), live TraderX API integration.

**Limits its own docs declare:** swap theta omits floating-leg cashflows inside the theta window;
European swaptions have no vega (no calibrated vol model yet); gamma is diagonal only; Bermudan vega
requires `hw_sigma` to derive from the exact calibration targets supplied.

---

## 2. What changed, and why it matters

Before YU16 the two systems shared **no instrument**. His pricers were rates derivatives; ours were
cash equities and listed options, which sit on equity spot — a factor his engine simulates as
background but does not price.

YU16 put five fixed-rate government bonds in our book. That moves us onto **the interest rate
curve**, which is the factor his engine is built around.

### Why a bond is nearly free for him

A fixed-rate Treasury is a stream of **known** cashflows on known dates, discounted off a zero
curve. A swap's fixed leg is the identical object; a bond is that plus principal at maturity. His
swap pricer is therefore most of a bond pricer already.

It is also the **simplest** instrument his machinery can take. No optionality, no exercise schedule,
no volatility calibration. His hard problems (backward induction for Bermudans, vol calibration) do
not arise.

### And it suits his hardware better than what he has built

A Bermudan swaption needs backward induction: solve the last exercise date, step back, repeat. Time
is sequential, which fights a GPU.

A bond is a pure feed-forward sum. Every cashflow, position and scenario is independent, so it
parallelizes in every dimension simultaneously. A VaR run over a bond book (thousands of paths ×
hundreds of positions × dozens of cashflows, then differentiated per pillar) is a large dense tensor
operation with no branching — the ideal JAX workload.

**He has already solved the hard case. Bonds are the easy one.**

---

## 3. Integration points, current

| # | Point | State |
|---|---|---|
| 1 | **Shared risk factor** — our Treasuries and his pricers both live on the zero curve | **Open now.** The single biggest change |
| 2 | **Shared math** — his swap fixed leg ≈ a bond, plus principal | Needs a bond pricer; bounded work |
| 3 | **Position transport** — our EOD risk extract, schema 3 | **Ready.** Carries face, coupon, maturity, clean price, last coupon date and accrued interest |
| 4 | **Sensitivities** — his per-pillar AD delta is key-rate DV01, the number a bond book needs | **Ready on his side**, once a bond NPV exists to differentiate |
| 5 | **Curve construction** — five bonds at spread maturities imply a bootstrappable zero curve | **Partially closes a gap that belonged to neither system** |
| 6 | **XVA** — `counterpartyId` and `nettingSetId` are on every extract row | Ready whenever he gets there |
| 7 | **Return path** — risk verdicts tightening pre-trade limits via the YU04 durable control feed | Mechanism exists, unused for this purpose |

### The extract contract (schema 3)

```
accountId,security,instrumentType,quantity,contractMultiplier,costBasis,closingMark,
markSource,markQuality,marketValue,unrealizedPnl,currency,counterpartyId,nettingSetId,
coupon,maturityDate,lastCouponDate,accruedInterestFraction
```

`instrumentType` is `EQUITY | OPTION | TREASURY`. `TREASURY` is set **by join against instrument
reference data**, never by prefix-parsing the ticker. `coupon` and `maturityDate` populate for
Treasury rows only. ETFs report `EQUITY`, which is arithmetically what they are.

`lastCouponDate` and `accruedInterestFraction` are **derived** rather than joined: the coupon
schedule of a fixed-rate Treasury is a function of its maturity, so no new reference data was
needed and the extract stays a pure function of cut plus static.

Full detail, including the guarantees and the six conventions most likely to trip a consumer, is in
[`risk-extract-consumer-guide.md`](risk-extract-consumer-guide.md), which is current for schema 3.

---

## 4. What is still missing

- **No bond pricer on his side.** Related math, real work, but bounded.
- **Our prices are simulated.** Maturity-sensitive but synthetic, so a curve bootstrapped from them
  is a simulated curve.
- **No swaps or floating-rate instruments on our side.** The overlap is factor-and-math, not
  instrument identity.
- **Market data belongs to neither system.** He needs zero curves, a cross-asset covariance matrix,
  dividend yields and Hull-White calibration. We produce none of them, and our option marks use a
  single flat implied vol across the whole chain. The Treasuries narrow this; they do not close it.

---

## 5. If both were production systems at one firm

The two sit on opposite sides of a latency boundary.

```
   ORDERS
     │
     ▼
┌─────────────────────────┐   positions    ┌──────────────────┐
│   TraderX OMS           │ ─────────────► │  JAX Risk Engine │
│   matching engine       │  stream + EOD  │                  │
│                         │                │  VaR / ES        │
│  inline risk gate       │                │  key-rate DV01   │
│  sub-microsecond        │                │  greeks          │
│  ON the critical path   │                │  XVA (later)     │
│                         │                │  OFF the path    │
└─────────────────────────┘                └──────────────────┘
     ▲                                              │
     │            tightened limits                  │
     └──────────────────────────────────────────────┘
                YU04 durable control feed
```

**Four interaction points, by timescale:**

1. **Pre-trade, microseconds.** `BlpRiskState` inside consensus: position limits, notional,
   restricted list, kill switch. **Not his engine** — a GPU Monte Carlo cannot live here. Worth
   stating explicitly because "risk" spans five orders of magnitude of latency in one firm.
2. **Intraday, seconds to minutes.** A consumer follows the trade stream, maintains live positions,
   and hands them to the risk engine on a loop.
3. **End of day, batch.** The risk extract: quiesced, cut at a consensus sequence, byte-identical on
   all three members. The official number, and auditable precisely because of that property.
4. **What-if, on demand.** Hypothetical portfolios priced synchronously for a trader.

**The return path is the part usually forgotten.** Risk is not a read-only consumer: a VaR breach
has to tighten the pre-trade gate. The YU04 control feed already provides a durable, watermarked,
consensus-sequenced channel for exactly that, which is what would make these one system rather than
two programs sharing a file.

**The trust boundary:**

- TraderX is authoritative on **positions**. If his numbers disagree, his input is stale or wrong.
- The risk engine is authoritative on **valuation and risk**. The OMS never second-guesses a VaR.
- Neither silently overrides the other; a limit change travels back as an explicit sequenced event.
- **They must reconcile.** If his positions-as-of-N do not match the extract at N, something is
  broken. Nobody owns that check by default, and it should not be discovered at a month-end break.

---

## 6. The question that decides the next step

> Your AD already produces per-pillar delta against the zero curve. A fixed-rate Treasury's NPV is a
> pure function of that same curve, with no optionality and no vol calibration. How much work is
> adding it?

Everything else follows from the answer.
