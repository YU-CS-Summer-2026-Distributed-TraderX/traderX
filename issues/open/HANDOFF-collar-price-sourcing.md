# HANDOFF — where prices come from: split the collar reference from the valuation mark

**Status:** not started. **Rig:** kind only (`kind-traderx-yu12-cluster`) — no GCP credits.
**Related:** `HANDOFF-agent-flow-generator.md` — realistic flow changes what a good answer looks
like here, so read that first if both are being picked up.

---

## The finding

Prices in this system are a **random walk seeded from a static file**. Verified in
`specs/YU15-eod-risk-extract/generation/runtime-overrides/price-publisher/src/main.js`:

```js
const drift = current.price * (Math.random() * 0.01 - 0.005);
const nextPrice = round3(clamp(current.price + drift, low, high));
```

- ±0.5% of the last price per tick, clamped to a per-ticker volatility band
- starting levels from hand-written `data/snapshot-prices.json` (`AAPL 240.1`, `NVDA 901.4` —
  plausible *levels*, static values)
- any ticker **not** in that file gets `100 + Math.random() * 50`
- options are priced off the underlying by Black-Scholes with **one flat implied vol for every
  contract** — the code says this is deliberate
- published to NATS on `pricing.*` (JSON) and `pricing-tick-bin.<TICKER>` (binary)

The transport is genuine and event-driven. The *content* is invented. Two consequences that matter:

1. **The UI's positions and P&L are random.** A position's P&L swings because `Math.random()` went
   one way. It looks alive and means nothing.
2. **The collar rejects legitimate orders.** This cost real time during the 2026-08-05/06 proof
   runs — a limit of 100 against a live IBM of ~187 (46% deviation) rejected everything, and several
   proofs had to be rewritten to read the prevailing price from the feed instead of pinning a
   constant.

---

## The distinction to make

Price plays two different roles here and they currently share one source:

| Role | What it needs | State today |
|---|---|---|
| **Mark for valuation** — what a position is worth | Own last trade when we traded; external only for untraded instruments | **Already correct.** ADR-051: a price tick seeds a security's mark only while no trade has printed; afterwards the last trade price is the mark. |
| **Risk collar reference** — the anchor an order's limit is judged against | Exogenous, trustworthy, slow-moving, hard to manipulate by trading | **The weak spot.** It is the random walk. |

Note the asymmetry: the valuation path already prefers the engine's own execution, which is right.
The collar is the part still anchored to fiction.

There is also a subtle hazard once agent flow lands: if the collar reference derives from the
book, and agents move the book, then **the collar can be walked by trading**. A real venue's price
bands are deliberately resistant to this. Whatever is chosen, it should not be trivially
self-referential.

---

## The work — decide, then implement

This handoff is a **decision first**. Options, with the trade-off stated rather than a
recommendation smuggled in:

1. **Keep the random walk, bound it harder.** Cheapest. Narrow the band, slow the drift, seed every
   traded ticker in `snapshot-prices.json` so nothing falls into the `100 + rand*50` path. Fixes the
   proof-breaking symptom, fixes nothing conceptual.
2. **Derive the collar reference from the book** (mid, or a time-weighted mid, or session VWAP).
   Internally coherent, no external dependency, and it is what the engine actually knows. Risk: it
   is self-referential, so aggressive flow can walk the anchor. Needs a rate limit or a reference
   that updates on a slower clock than trading.
3. **Slow exogenous reference.** Keep an external input but make it a *reference price* that moves
   on a session cadence rather than a tick cadence — e.g. previous close, updated once. Closest to
   how real price bands work. Still fiction until fed real data, but structurally right.
4. **Real market data.** The only thing that makes the *level* real. Needs a vendor, a licence, and
   a network dependency at demo time. Out of scope for kind-local, but worth naming so the decision
   is made knowingly rather than by default.

Whichever is chosen, the deliverable includes:

- the two roles separated in code and in configuration, so "the price" is never ambiguous again
- every traded ticker seeded, so the `100 + Math.random() * 50` fallback stops being reachable in
  practice
- a note in the docs stating plainly which numbers are synthetic, so no slide accidentally claims
  real prices

---

## Traps

- **ADR-051 precedence is load-bearing.** Do not "fix" prices by making the tick always win — it
  would mean a security's mark ignores the trades the engine itself booked, and the extract's
  `markSource` semantics (`EOD_SNAPSHOT` vs `CLUSTER_LAST_TRADE_AT_N`) depend on the current rule.
- **The EOD close is built from observed samples**, via `EodPriceService` →
  `priceHistory.priceAtOrBefore(security, closeMillis)`. Changing what gets published changes what
  the EOD quality gate sees. A too-quiet feed can produce `MISSING`/`STALE` and block publication;
  a too-jumpy one can trip the spike check. Both paths are exercised by
  `scripts/proofs/yu06-quality-gate.sh` — run it.
- **`markQuality` flows through to the risk extract** as `OK`/`OVERRIDDEN`/`STALE`/`LAST_TRADE` and
  is documented to consumers in `docs/engineering/risk-extract-consumer-guide.md`. If the meaning of
  those values shifts, that guide needs updating.
- **Options have no published close** in this pipeline, so they fall back to
  `CLUSTER_LAST_TRADE_AT_N`. Anything done to equity pricing must not silently break the option
  path — `scripts/proofs/yu15-option-persistence.sh` and `seed-option-chain.sh` cover it.
- **Price-publisher is Node, not the JVM.** No deterministic-core involvement, no snapshot
  implications — this is all off-consensus. That makes it low-risk to change; do not let that
  tempt anyone into changing the collar *check* itself, which is in `BlpRiskState` and **is** core.

---

## Acceptance

- [ ] A written decision on which option was taken and why.
- [ ] Collar reference and valuation mark are separately configured and separately documented.
- [ ] No traded ticker resolves through the random-basis fallback.
- [ ] A proof run does not produce spurious `PRICE_COLLAR` rejections on legitimate limits.
- [ ] `yu06-quality-gate` and `yu06-consumer-halt` still pass — the EOD chain is downstream of this.
- [ ] `bash scripts/yu15/run-proofs.sh` → 19/19.
- [ ] Docs say plainly which prices are synthetic.

## Conventions

Never `git push`. No `Co-Authored-By: Claude` trailer, no "Generated with Claude Code" — commit as
yaakov only. This handoff file stays **untracked**.
