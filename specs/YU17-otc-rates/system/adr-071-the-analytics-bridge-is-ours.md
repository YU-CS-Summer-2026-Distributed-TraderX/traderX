# ADR-071: The analytics bridge is ours, and the return path is the hard half

## Status

**Proposed** (2026-08-26). **Not implemented.** Intended as its own state.

Raised by yaakov after reviewing `AlexNeugroschl/JAX_Risk_Engine` against our two integration handoffs
(`docs/handoff/INTEGRATION-jax-risk-engine-2026-08-17.md` and its `-inbound-` companion): *we produce a
file, he serves an API, and nothing sits between them — that may be us.*

It is. This ADR records why the bridge is ours rather than his, and what the constraint actually is —
which is **not** the plumbing.

## Context

### Two systems that fit, and do not meet

We emit, from one consensus cut under one `cutSha256`, **two write-once CSV artifacts** to GCS and
announce them on `risk.extract.ready`: the netted position extract (schema 3) and the per-contract
swap/swaption file (schema 2, **terms and no valuation** — ADR-064).

The JAX engine is a **stateless HTTP service**: `POST /portfolio/price` returns `202` with a job id,
results are polled from `GET /portfolio/price/{job_id}`, and the job store is an **in-process dict**.

Neither is wrong. They are a file-drop producer and a request/response consumer, and the distance
between them is a service nobody has written.

### The instrument scope already lines up, which is why this is worth doing now

Our `#contracts` artifact carries `productType` and `exerciseStyle`, and we book **EUROPEAN, BERMUDAN
and AMERICAN**. Those are precisely his four pricers. This is not a partial overlap to be negotiated:
on the rates axis his coverage and our booking are the same set.

### The first consumer is a defect we already have

Our credit gate reserves against **notional**:

```
executedNotional[account] + reservedNotional[account] > creditLimit - notional
```

For a swap that is close to meaningless — a 30-year and a 1-year swap of the same notional consume
identical credit and carry wildly different risk. **The gate is wrong on every swap we book.** DV01 per
contract fixes the tenor dimension, and it is closer to plumbing than new capability on his side.

So the bridge is not speculative infrastructure looking for a use. It has one, it is a live defect, and
the analytics that fix it exist on the other side of a gap.

## Decision (proposed)

**Build a service — working name `risk-analytics-bridge` — that owns the round trip: cut in, analytics
back, and the return path made safe for a replicated state machine.**

Outbound is the easy half and is nearly mechanical:

1. Subscribe to `risk.extract.ready`.
2. Read both artifacts, **verify `cutSha256`** before using either.
3. Assemble the engine's `PortfolioRequest` from the contracts file.
4. `POST /portfolio/price`, hold the job id, poll to completion.

**The hard half is the return, and it is the reason this cannot be his service.** The inbound handoff
states four rules that anything re-entering our system must satisfy, and they follow from consensus,
not from finance:

| Rule | What it forbids |
|---|---|
| **Sequenced, never looked up** | A member must never fetch an analytics number at apply time. Two members reading a service a millisecond apart get different answers and the cluster **diverges permanently**. |
| **Exactly reproducible** | A value that cannot be reproduced cannot be snapshotted, and a state that cannot be snapshotted cannot be recovered. |
| **Fail closed** | Missing or stale analytics must refuse the booking, never value it at par or fall back to notional. |
| **Versioned, roll-forward** | An unrecognised version is an error, never a best-effort interpretation. |

**A stateless pricing service cannot satisfy Rule 1 from its own side.** It has no way to decide how its
output enters a totally-ordered input log — that is a property of our cluster, not of his API.
**Therefore the bridge is ours, and the boundary is exactly here: he computes, we sequence.**

The channel already exists in the right shape. `7256a33c` added `POST /risk/control/fxrate` →
`TYPE_FX_RATE` (14) → applied in the clustered service → `T_FX_RATE` in snapshot format 7 → booking
refused `PRICE_MISSING` when absent. **A per-contract DV01 is the same shape with a different payload**,
and copying that shape is cheaper than inventing one.

## What the bridge must own, and why each is not obvious

**The market/calibration config, or an explicit refusal to own it.** His `SimulationConfig` needs
`hw_a`, `hw_sigma`, a covariance matrix, correlations and vols — and his own planning doc assumes *the
TraderX side provides correlation/volatility data*. **Nothing on our side produces that today.** The
outbound handoff already tiers what we could defensibly supply (Tier 1: a sample covariance from
`eod_price_snapshot`'s own closing-price history) and states the honest limit: **we are a trading system,
not a market data vendor — curve construction and vol surfaces are our assumption wearing a market's
clothes.** This is the single largest open question in the integration and it belongs to the bridge
because the bridge is what would have to fabricate it.

**Idempotency across a job store that forgets.** Jobs live in an in-process `dict` and die with the
process. The bridge must be able to **resubmit the same cut and get the same answer**, and must never
read "unknown job id" as "pricing failed" — that is a lost record, not a result.

**Identity translation.** His `PortfolioResult` keys greeks by **trade index as a string**; we key
everything by `contractId`. The index↔`contractId` map is bridge-owned state, and getting it wrong
attaches the right number to the wrong account silently.

**Unit translation.** We emit `fixedRateTicks` internally and `fixedRate` in the artifact; he takes a
float. Small, but it is exactly the class of thing that is wrong by a factor of 10,000 and still looks
plausible.

**Determinism of the request itself.** The same cut must produce a **byte-identical** request, or the
same portfolio prices differently on two runs and nothing downstream can be reproduced. This is the
outbound mirror of Rule 2 and it is on us, not him.

## What this is explicitly NOT

- **Not a pricing library.** We do not re-implement or check his valuations. If we disagree with a
  number, that is a conversation, not a second implementation.
- **Not a synchronous dependency of the trading path.** Nothing in the order flow waits on this service.
  Analytics arrive between sessions and enter as sequenced state; if the bridge is down, the gate keeps
  using what it last accepted, and refuses what has gone stale (Rule 3).
- **Not a market data vendor.** See above; the tiers exist so that what we supply stays traceable to
  something we observed.
- **Not a general integration layer.** One consumer, one producer, one artifact family. A second
  consumer is a reason to revisit this, not to generalise it in advance.

## Consequences

**The credit gate becomes tenor-aware**, which is the first real reason to build it and the only one
needed.

**A derived number becomes replicated state.** Once DV01 enters the log it is snapshot content, it is
subject to `MIN_READABLE`, and changing its meaning later costs a format bump. That is the price of Rule
1 and it should be paid deliberately rather than discovered.

**One shared blind spot is not fixed by this ADR and gets worse with time.** Our contracts file states
`lifecycle=NOT MODELLED` — terms as booked, no resets, no fixings — and his docs name an aged-instrument
discounting gap. **These are the same problem meeting in the middle.** It is invisible today because
every contract is young, and it becomes wrong quietly, as a function of time, most wrong on the
instruments a risk number most needs to be right about. **Neither side owns it. Naming an owner is
cheaper than discovering it in a demo.**

**Real market data changes the shape of the calibration question, not its ownership.** If a real feed
arrives, Tier 1's sample covariance stops being our best available answer and starts being a fallback —
but the bridge still decides what enters the log, and still has to fail closed when the feed is stale.

## Open questions

1. **What lands in the log, and at what grain?** DV01 per contract is the answer for the credit gate.
   NPV per contract would unblock netting, which we have documented as impossible without it. Are they
   one event type with a payload discriminator, or two?
2. **Who fabricates the calibration config?** Us (with the honesty tiers), him (with defaults), or is a
   session simply not priced when no defensible input exists? **Fail-closed argues for the third.**

   **This question is load-bearing beyond this ADR.** `issues/open/HANDOFF-fx-instrument-class.md`
   defers FX-as-a-tradable-class partly because **FX forwards cannot be priced without a curve in each
   currency, and we have no curve in any currency** — and it names as its own revisit condition *"the
   rates curve work happens anyway and forwards become cheap on the back of it."* The bridge is the most
   likely thing to force that answer. Deciding curve ownership here is therefore not only about DV01.
3. **Where does the result artifact live?** A third file beside the two, under the same `cutSha256`, has
   symmetry with ADR-064; sending analytics only into the log leaves no auditable record of what was
   priced.
4. **What is the staleness rule?** Rule 3 says fail closed, but "stale" needs a number — one session? A
   configured maximum age? This is a `size-a-configuration-bound` question, not a preference.
5. **Does the bridge run per session, or on demand?** The announcement is per-EOD; the engine took ~52
   seconds for four trades and 4096 scenarios, which is fine per-session and not fine per-order.

## Related

- [ADR-064](adr-064-two-artifacts-one-cut.md) — the contracts artifact this consumes; terms and no valuation
- [ADR-063](adr-063-swap-risk-gate.md) — the credit gate whose notional reservation is the defect
- [ADR-068](adr-068-external-price-sources.md) — what we may source externally, and the licensing frame
- [ADR-070](adr-070-the-tape-is-the-reference.md) — a real tape changes the calibration inputs, not the ownership
- `docs/handoff/INTEGRATION-jax-risk-engine-2026-08-17.md` — what we send, and the market-data tiers
- `docs/handoff/INTEGRATION-jax-risk-engine-inbound-2026-08-17.md` — the four delivery rules, and DV01 as the first ask
- `issues/open/HANDOFF-fx-instrument-class.md` — FX as a tradable class, **deliberately deferred**. Read
  it before proposing FX here: his cross-asset simulator's FX legs **need rates, not instruments**, and
  the rate fix (`TYPE_FX_RATE`, `7256a33c`) already satisfies them. The instrument work is blocked behind
  curves and settlement and serves no consumer that has asked. Its revisit condition is open question 2
  above — the dependency runs both ways.
