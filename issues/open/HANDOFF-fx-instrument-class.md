# HANDOFF: FX as a tradable instrument class (deferred)

**Filed** 2026-08-17 by the coordinator. **Deliberately deferred** — nobody has asked to trade FX.
This exists so the scope is written down once while it is understood, not so someone starts it.

**Do not confuse this with the credit-check defect**, which is separate, live, and being fixed now:
`BlpRiskState` sums notionals across currencies into one per-account counter. That fix needs an FX
**rate**, not an FX **instrument**, and it is the piece with an actual bug behind it. This document is
only the tradable-instrument half. Conflating the two is how a one-week fix becomes a quarter.

---

## Why this would be a new state (YU18), unlike the rate fix

Measured against what a YU state is in this project (a branch plus a spec pack, cut from the tip,
carrying every ancestor), FX-as-a-tradable ripples the way YU16 and YU17 did:

- new product types and a two-legged booking model in the engine
- settlement dates, which nothing in this system currently models
- extract representation and a new `instrumentType` value
- EOD quality-gate band selection (an FX rate's plausible daily move is neither an equity's 20% nor an
  option's 200%, so this is a **third** band — see `EodQualityChecker` and the control pair in
  `yu06-quality-gate.sh` step 6, which asserts band selection and would need extending)
- reference data for pairs and conventions

That is a capability tier, not a data addition. The rate fix, by contrast, touches the risk path and
the snapshot and is not a state.

## Scope, cheapest first

The family, in the order it is worth considering:

| Instrument | What it is | Note for this system |
|---|---|---|
| **FX spot** | Exchange at today's rate, T+2 | Pricing is trivial; the work is settlement, which we do not model at all |
| **FX forward (outright)** | Exchange at a future date at a rate agreed now | Priced by covered interest parity off two curves. **The one that matters**, and it is blocked on curves we do not have |
| **FX swap** | Spot leg plus offsetting forward leg | How the market actually quotes forward points. Two-legged booking |
| **NDF** | Non-deliverable forward, cash-settled | Adds a fixing source and a settlement currency |
| **Cross-currency basis swap** | Floating-for-floating across currencies, notionals exchanged | The instrument that would let a EUR swap be discounted properly from a USD curve. Hardest, and the most genuinely useful for the risk story |
| **FX options** | Vanillas, barriers | A separate vol surface. Out of scope by a wide margin |

**Recommended scope if this is ever picked up: spot and outright forwards only.** They cover the FX
exposure a sell-side OMS actually carries, and they stop short of the two-legged and vol-surface work.

## What blocks it, and the ordering that matters

1. **Forwards need curves in both currencies.** Covered interest parity is not optional; a forward rate
   is a spot rate and two discount factors. We have no curve in any currency
   (`docs/handoff/INTEGRATION-jax-risk-engine-2026-08-17.md` §6 has the full picture). So **FX forwards
   cannot precede the rates-curve work**, and pretending otherwise produces a forward priced off an
   assumption.
2. **Settlement is unmodelled system-wide.** T+2 is not a property of FX; it is a gap we have
   everywhere. If FX is the reason we build settlement, that is a much larger piece of work wearing an
   FX label, and it should be scoped as settlement.
3. **The deterministic-core constraint applies.** Any booking-model change is an engine change, so it
   cannot be rolled gradually: scale to zero, wipe the PVCs, mint a fresh epoch. Budget the rig time.

## What it would unblock

- Alex's cross-asset simulator has FX legs with UIP drift mapping and nothing on our side fills them.
  **But note: his FX legs need rates, not instruments.** The rate fix satisfies him; this does not add
  anything he has asked for.
- Genuine multi-currency exposure reporting, once the extract can express an FX position rather than
  only a currency label on a row.

## The honest recommendation

**Leave it.** The FX pain that exists today is entirely a rate problem, and the rate fix closes it. The
tradable-instrument work is blocked behind curves and settlement, neither of which we have, and it
serves no consumer that has asked. Revisit if either (a) somebody wants to trade FX, or (b) the rates
curve work happens anyway and forwards become cheap on the back of it.

## Where to look

| Thing | Path (YU17, the operative layer) |
|---|---|
| Risk gate, currency-blind today | `specs/YU17-otc-rates/.../risk/BlpRiskState.java` |
| Swap booking, notional handling | `specs/YU17-otc-rates/.../cluster/MatchingEngineClusteredService.java:648` |
| Convention table (append-only), currencies USD/EUR/GBP/JPY | `specs/YU17-otc-rates/.../lmax/SwapConventions.java` |
| Extract `instrumentType` legend | `specs/YU17-otc-rates/.../cluster/RiskExtractCsv.java` |
| Quality-gate band selection | `specs/YU15-eod-risk-extract/.../service/EodQualityChecker.java:62` |
| The consumer-side picture | `docs/handoff/INTEGRATION-jax-risk-engine-2026-08-17.md` |
