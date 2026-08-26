# ADR-072: Replayed prints become order flow, and the attribution counter must move again

## Status

**Accepted** (2026-08-26) by yaakov, after being shown what it costs. **Not implemented.** Intended as
its own state.

**This reverses a decision, and says so rather than quietly contradicting it.**
[ADR-070](adr-070-the-tape-is-the-reference.md) states under *What this is explicitly NOT*:

> **Not a market simulator.** The tape drives a *reference*; the book is still made by the orders this
> system receives. **Replayed prints are never injected as trades**, and the resampled series is never
> presented as depth.

That first sentence still holds — the tape is a reference and the resampled series is still never
presented as depth. **The prohibition on injecting prints is lifted.** ADR-070's bullet is amended to
point here.

## Why the reversal, stated honestly

ADR-070's own argued payoff is that *"a demo becomes explicable — 'this is Apple on February 4th' is a
sentence an audience can check."* It delivered a **price** an audience can check. It did not deliver a
**system** an audience can watch work: on an idle rig **3 of 69 books have ever printed**, six trades
total, and the blotter is empty. The reference moves and nothing else does.

Injecting replayed prints as orders makes the engine do the thing it exists to do — match, fill, move
positions, move P&L, exercise the collar — driven by activity that genuinely happened. **That is a
better demonstration of this system than any number of correct prices, and it is the reason to accept
the cost below.**

## The cost, which is the whole reason this ADR exists

### The attribution counter moves for the second time, and this is the third instance of the pattern

`scripts/proofs/lib-consensus-readings.sh` exists because the feed adapter, going live 2026-08-24,
broke three proofs that read the cluster's **global `applied`** counter as though it were private to
their own bookings. Its remedy was to attribute work through the **order-ref generator**, on an
explicit promise recorded in its own header:

> `order-shaped commands -> quiesced_order_refs (the ORDER_NEW generator; ticks never touch it)`

**Replayed prints are order-shaped. They will advance that generator.** The promise becomes false the
day this ships, and **eight files depend on it** — `yu16-bond-position`, `yu17-band-follows-market`,
`yu17-closed-survives-restart`, `yu17-halt-survives-failover`, `yu17-retick-determinism`,
`yu17-preopen-queue-open`, plus the library and its self-test.

**So: a second writer broke `applied`; a third writer breaks the counter we retreated to.** The
lesson generalises past this change — *any* new producer of sequenced commands invalidates whatever
counter the proofs currently use to say "this was mine", and the answer cannot be a fourth retreat.

**The fix must ship with the feature, not after it**, and the library forbids the tempting version:
*"widening the tolerance does not fix this — it deletes the check."*

**Decision: replayed flow must be attributable and excludable at the source.** Route it through a
dedicated account (or an equivalent tag carried into consensus) and expose counters that separate
externally-generated order flow from everything else, so a proof can still bracket its own work
without knowing what else is running. The library's own admission test applies unchanged: *name a
counter the replay does not advance, and show it standing still on a live rig while the replay runs.*

### It is FOUR counters, not one (corrected 2026-08-26)

This ADR first said "the attribution counter", singular, naming only the order-ref generator. **That
understates the cost fourfold.** Traced by the implementing lane and verified here — replayed order
flow moves every counter the library reads:

| counter | why the replay moves it |
|---|---|
| `traderx_cluster_next_order_ref` | `ORDER_NEW` consumes a ref on apply |
| `traderx_cluster_trades` | any replayed fill books legs |
| `traderx_band_reanchors` | the band slot is reached from order placement |
| `traderx_band_stranded_cancels` | same path |

**All four are global, so no choice of ticker or symbol avoids any of them.** The library already knew
this about the last two and said so, at the point where it explains why band movement takes a baseline
rather than a floor: *"GLOBAL over order writers, exactly like the trade counter."* **It had named the
category — "order writers", plural — while its own promise at the top still assumed the proofs were
the only one.**

### And therefore a snapshot format bump

Operator-scoped siblings for the ref and trade counters must **survive a snapshot**: `quiesced_*`
requires all three members to agree, and two proofs restart a member. So this is **snapshot format 9
and a fresh-epoch mint**, arriving weeks after format 8. The two band shadows are not snapshotted, for
the same reason their existing siblings are not.

**This is the real price of the reversal** and it was not visible when the decision was taken. It does
not change the decision — but a format bump and a mint is a different thing from adding a counter, and
the record should not pretend it was foreseen.

### The side is invented, and must be labelled as invented

**TAQ trades carry no side.** Buy/sell is not in the data, and it cannot be recovered here: inferring
it from the quote requires an NBBO, and ADR-070 records that `QU_COND`, `QU_CANCEL` and `NATBBO_IND`
were dropped at ingest, so no national best bid/offer can be reconstructed from this corpus.

**Decision: use the tick rule** — an uptick is a buy, a downtick a sell — which works from prints
alone. **It is an approximation and the UI must say so.** This is the same discipline ADR-070 applied
to `asOf`: a number that is real in one respect and fabricated in another gets a label, not silence.

### The rate is sampled, and the sample is the design

AAPL alone carries roughly **560,000 prints per trading day** (22.36M over 40 days, measured). A
hundred symbols is millions of prints against a **30-minute** wall-clock tape day. Replaying prints
one-for-one is not a throughput problem to be solved; it is the wrong target.

**Decision: sample to a target order rate** — order 5–20/sec, tunable — chosen so the blotter is
visibly alive without the gateway becoming the subject of the demo. The sampling rule is part of what
must be recorded, because two runs that sample differently are not reproducible against each other.

## What does not change

- **The reference is still the reference.** Replayed orders do not set the collar's reference;
  `ref` remains the resampled median series from ADR-070. A replayed print that breaches the collar is
  **rejected, and that is a demonstration rather than a defect.**
- **The resampled series is still never presented as depth.** ADR-070's second clause stands.
- **Nothing enters the repository.** ADR-068's durability rule is untouched; the print sample is drawn
  from the same bucket at bring-up.
- **This is not a claim about market microstructure.** We are replaying prints with an invented side
  at a sampled rate. It shows the system working on real activity; it is not a backtest and no number
  derived from it is reference-grade.

## Consequences

**The blotter, positions and P&L become products of replayed history rather than of the operator's own
order flow.** Every trade-derived number on screen changes meaning, and the UI must make clear which
account's activity is replayed and which is the operator's — otherwise a demo cannot tell the audience
which fill was theirs.

**The risk and credit gates now see continuous flow.** That is mostly a benefit: they get exercised.
It also means a replayed session can consume credit and trip limits, so replayed flow needs its own
accounts rather than sharing the demo ones.

**One-for-one reproducibility is lost unless the sample is deterministic.** Two runs from the same
epoch should produce the same orders; that argues for deriving the sample from the tape position
rather than from a random draw, in the same spirit as ADR-070's stateless clock.

## Related

- [ADR-070](adr-070-the-tape-is-the-reference.md) — the tape as reference; this lifts one of its
  exclusions and is bound by the rest
- [ADR-066](adr-066-price-band-follows-the-market.md) — the collar the replayed flow will be tested against
- [ADR-068](adr-068-external-price-sources.md) — the durability rule, unchanged
- `scripts/proofs/lib-consensus-readings.sh` — the attribution contract this breaks, and the test any
  replacement must pass
