# TraderX ↔ JAX Risk Engine: where the two actually meet

Notes for Alex, after reading [JAX_Risk_Engine](https://github.com/AlexNeugroschl/JAX_Risk_Engine)
and `docs/planning/traderx-integration.md`.

Short version: there is a clean architectural fit and one blocking mismatch. The mismatch is that
**we do not currently trade a single instrument your engine prices.** Everything else follows from
what we do about that, so it is up front rather than buried.

---

## 1. What TraderX is, from your side of the fence

A sell-side OMS: a matching engine that takes orders, crosses them, and holds positions. The
matching engine is an LMAX-style single-threaded deterministic core running as a 3-member Aeron Raft
cluster, so every member replays the same totally-ordered log and reaches byte-identical state.

The part that matters to you is the **end-of-day risk extract**. At close the cluster stops
accepting business, freezes, and writes down every open position as of one exact point in its own
history — a consensus sequence number. All three members render that cut independently and produce
byte-identical files, and it is reproducible after a member is killed and recovers.

That is a stronger provenance guarantee than a database query gives you. There is exactly one answer
to "what did we hold at the close", it does not depend on which machine you asked, and it is
replayable.

---

## 2. What we can hand you today

One CSV per session, self-describing, plus a `.cut` sidecar carrying the same state as integer ticks
with a SHA-256 in the CSV header. Full detail in
[`risk-extract-consumer-guide.md`](risk-extract-consumer-guide.md) — that doc is written for you and
you can produce a real one on your laptop in about ten minutes with no cloud account (§2A).

Columns:

```
accountId, security, instrumentType, quantity, contractMultiplier,
costBasis, closingMark, markSource, markQuality,
marketValue, unrealizedPnl, currency, counterpartyId, nettingSetId
```

Properties worth knowing before you design against it:

- **Un-netted**, at `(accountId, security)` grain. Netting is your decision, not ours; the
  `nettingSetId` is there so you can make it.
- **Signed quantity** — negative is short.
- **Fails closed.** No partial files, ever. A missing mark, an unmapped account, or a value that
  would need rounding aborts the whole extract rather than emitting a degraded row. If the file
  exists, every row in it is well-formed. You never need defensive row-skipping.
- **`markQuality` carries the pricing pipeline's verdict through** — `OK`, `OVERRIDDEN`, `STALE`, or
  `LAST_TRADE`. If a degraded mark should block a risk run rather than flow into one, say so and we
  can fail the extract instead of labelling the row.

---

## 3. The blocking mismatch

| | TraderX holds | JAX Risk Engine prices |
|---|---|---|
| Cash equities | ✅ `EQUITY` rows | a **simulated factor**, not a priced instrument |
| Listed equity options | ✅ `OPTION`, OCC symbols, ×100 | ✖ — swap*tions*, not equity options |
| Interest rate swaps | ✖ never traded | ✅ |
| European / Bermudan / American swaptions | ✖ | ✅ |

Not one row of our extract maps to a pricer you have. Conversely, every instrument you have built is
one we have never traded and have no current plan to.

**So the question that determines everything else is:**

> Can your engine price a portfolio of **cash equities and listed equity options**?

If yes, integration is small. If no, the honest position is that our systems are complementary
demos that do not yet share an instrument, and a pipeline between them would compute nothing
meaningful. Either answer is fine and worth knowing early.

---

## 4. Three seams, cheapest first

### Seam 1 — equity VaR needs no new pricer

Your simulator already models equity spot with dividend yields, rate mappings and cross-asset
correlations. Our `EQUITY` rows are a **linear position in a factor you already simulate**. You
would not be writing a pricer; you would be mapping our rows onto your existing factor set and
supplying a covariance matrix.

This is the cheapest real integration available and the one I would do first. It produces a genuine
number — VaR and Expected Shortfall on the equity sleeve of a portfolio with provable provenance.

### Seam 2 — listed equity options reuse your machinery, not your pricers

Ours are American-style listed equity options, OCC-symbol identified, multiplier 100. Your American
swaption backward induction is the right *numeric* approach; the payoff and the underlying process
are different. The path generation, Sobol sequencing and VaR aggregation around it all transfer.

Real work, but bounded, and it does not start from zero.

### Seam 3 — XVA lines up by accident

You list XVA as planned. Our extract already carries `counterpartyId` and `nettingSetId` **on every
row**. Nobody coordinated that — we emitted them because a sell-side OMS should, and you need them
because XVA does. If you get there, the position side of the input is already shaped for it.

---

## 5. The gap that belongs to neither of us

Your engine needs a **calibrated market**. TraderX does not produce one and was never designed to.

| You need | We have |
|---|---|
| Zero curves per rate factor | nothing |
| Cross-asset covariance matrix | nothing |
| Dividend yields | nothing |
| Hull-White calibration (initial rate, theta, mean reversion) | nothing |
| Volatility surface | one **flat** implied vol for every contract |

That last row is worth being blunt about. Our option marks come from a price publisher that applies
a single constant IV across the entire chain, deliberately, because it exists to make a demo move —
not to be a model. You would consider it degenerate and you would be right.

So the division is:

- **We are authoritative about what is held**, and about the provenance of that answer.
- **You are authoritative about what it is worth**, and about the risk math.
- **The market data that connects the two belongs to neither system**, and right now nobody owns it.

If we integrate, that ownership question needs an answer before the numbers mean anything. It is the
real work item, not the plumbing.

---

## 6. Two things we noticed reading your repo

**Your priority-1 gap is the right one.** A non-PSD correlation matrix making Cholesky silently
produce NaN paths is exactly the failure class that has cost this project the most time — a
computation that looks like it ran and produces a confident wrong answer. We have found roughly
fifteen instances of that shape in our own proof scripts over the last month: checks that could not
fail, verdicts printed without being asserted, a validator that skipped every input whose id shape
it did not recognise. Our extract fails closed for the same reason. It seems we independently
arrived at the same instinct, which bodes well for the seam between us.

**Your integration doc is about your input surface, not about a data contract with us** — validation,
maturity-pillar assembly, range checks. All sensible, and all things that need doing regardless. But
it means neither of us has yet specified the thing in the middle. This document is the first half of
that from our side.

---

## 7. If you want to see one

No Google Cloud account, no deployment, nothing to ask anyone for. Docker and
[kind](https://kind.sigs.k8s.io/), then:

```bash
bash scripts/yu15/build-cluster-image.sh
bash scripts/yu15/start-cluster-kind.sh
bash scripts/sim/run-session.sh --minutes 10 --symbols 12
```

The third command runs a compressed trading day against the live book with distinct synthetic
participants — a market maker, a momentum taker, a mean-reversion taker, and an institutional order
sliced by the execution algo engine — so the resulting extract has varied cost bases and both long
and short holders per security rather than flat mirrored fixtures.

Then close the session and take a cut. Step by step in
[`risk-extract-consumer-guide.md`](risk-extract-consumer-guide.md) §2A.

One caveat, stated plainly: **this does not make the prices real.** No market data is involved.
Synthetic participants produce a synthetic price. What it makes real is the price *formation* — the
mark moves because someone lifted the offer, because depth was consumed. Fills, marks and P&L all
derive from one internally consistent market, which is what makes the file worth testing against.
The levels are ours, not the market's.

---

## 8. What we would need from you

In rough order of how much they change what we build:

1. **The answer to §3** — can you price cash equities and listed equity options?
2. **Netting grain.** We emit raw `(account, security)`. Would you rather receive netting-set-level
   exposure, or do that yourself?
3. **Should a degraded mark block a run?** `markQuality` of `OVERRIDDEN` or `STALE` currently flows
   through as data. We can make it fail the extract instead.
4. **Anything missing from the schema.** Trade-level detail, greeks, FX rates for non-USD, accrued
   interest. The engine holds more state than it renders; adding a column is cheap now and annoying
   after you have built against the current shape.
5. **Format.** CSV was chosen for inspectability. Parquet or JSON-lines are both easy from here, and
   Parquet is probably the better fit for something JAX is going to consume.
