# ADR-072: Replayed prints become order flow, and the attribution counter must move again

## Status

**Accepted** (2026-08-26) by yaakov, after being shown what it costs. **Implemented on YU17
2026-08-26**, on `traderx/cluster-node:yu17-adr072` + `price-publisher:yu17-replay`, at snapshot
format 9 on a fresh epoch. Intended as its own state.

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


## What the implementation is, in one paragraph

`price-publisher` gains `print-replay.js` beside `taq-replay.js` — the same process, deliberately,
because position comes from `taqReplay.positionAt()` and a second derivation of
`(now − epochStart) × compression` would be a second clock that could put the order flow at a
different tape instant from the reference. At bring-up it reads a **sampled-print extract** (~777 KB
gzipped: 23 symbols × 40 days × 120 windows × **4 real prints per window**, chosen at evenly spaced
*ranks* within each window so a quiet window is sampled as thoroughly as a busy one) out of a
`taq-print-sample` Secret that `start-cluster-kind.sh` fetches from the same
`gs://traderx-501015-tick-store/replay/` prefix as the median extract — so ADR-068's durability rule
is untouched and the revert is deleting a Secret. The builder,
`scripts/yu17/build-taq-print-sample.{sh,py}`, reads its universe **out of the median extract itself**
rather than from a second copy of a symbol list, because a symbol sampled but not referenced would put
real prices into a book whose collar is anchored by the synthetic walk; `print-replay.js` refuses the
pair at runtime on the same grounds and says so on `/health.printReplay`. Every order is a pure
function of (symbol, absolute tape slot): the price is the print, the side is the tick rule over the
*strided* series, the account rotates so consecutive slots in one symbol never self-trade, and the
`clientOrderId` is `taq-<SYMBOL>-<slot>` — which makes each submitted order self-identifying against
the tape and gives free idempotency on a retry. Symbols are staggered across each slot's duration, so
one print per window across a wide universe is a smooth rate rather than a burst every fifteen
seconds. Members gain four operator-scoped counters and **snapshot format 9**;
`scripts/proofs/yu17-replay-attribution.sh` and `ReplayFlowAttributionTest` are the two halves of the
library's admission test.

## Measured on the way in (2026-08-26), because none of it was foreseeable on paper

**The transport ceiling is a RATE, not a universe size**, and that is not obvious: prints =
symbols × days × windows × slots, and slots is chosen as rate × window / (symbols × compression), so
the symbol count *cancels* and the artifact is `rate × 72,000` prints at 40 days. Widening the
universe costs nothing; raising the order rate is what costs. Measured against the 1 MiB Secret cap
at 1.62 bytes/print gzipped: 12.3/s = 1.43 MB (refused), 26.7/s = 2.87 MB (refused), 6.1/s = 777 KB
(ships). Delta+varint encoding measured 1.11 bytes/print and would buy ~11/s; it was not built,
because 6.1/s is inside the band this ADR asks for.

**Two downstream breaks that the ADR did not anticipate, both found by running it:**

- **`trades.accountid` is a FOREIGN KEY onto `accounts`.** Replayed fills are real trades, so without
  seeded rows for the replay accounts the trade-processor threw a constraint violation on *every*
  one and **the blotter stayed empty while the engine traded all day** — the exact opposite of this
  ADR's stated purpose, and silent unless you read the consumer's logs. Fixed in the DB init
  configmap, in both the initial-schema and the `900-migrations` halves.
- **The risk extract refuses an account with no counterparty mapping.** `RiskExtractCsv` throws
  rather than emit an unmapped row, correctly. The three replay accounts are mapped to
  `CPTY-TAPE-REPLAY-0{1,2,3}` in `counterparties.csv`, which also makes the replayed exposure
  *excludable by name* from any consumer of that artifact — the same discipline as the account range
  itself.

**The collar rejection is not hypothetical.** Within minutes of the first fresh epoch the rig had
refused replayed prints with `PRICE_COLLAR`, counted by reason on `/health.printReplay`. A real
February 2025 print that moved further from its window's median than the band allows is refused, and
that is the band working.

**The demo claim, measured.** This ADR opens on *"3 of 69 books have ever printed, six trades total"*.
Twenty minutes into the first epoch with the replay live: **23 of 69 books carrying live resting
depth — every one of them a tape symbol — and 430 trade legs.**

**One reading in the first draft of the proof could never have failed**, and it is worth recording
because it is this project's recurring shape. "Count books that have printed" was written as "count
books carrying a `mark`", and ADR-051 stamps the mark from a market-data *tick* until a book first
crosses — so every seeded option, Treasury and corporate reports one whether it has ever traded or
not. It read 66 of 66 on a rig minutes old. Resting depth discriminates; the mark does not.

## What it actually cost the proof suite: eleven readings, not four counters

The section above says the attribution counter is four counters and not one. **The first full suite
run with the replay live found that the four counters were the easy half.** Eleven readings across
ten proofs stopped being about the thing they named, and every one of them had been correct when it
was written. The suite went **27 passed / 3 skipped / 11 failed** on the first run and **36 / 2 / 3**
once these were repaired.

They fall into two shapes, and neither was ever repaired by widening a tolerance:

**1. A global counter or gauge read as a delta, an absolute, or a stillness.** The four counters
this ADR names are only the ones `lib-consensus-readings.sh` already owned. The suite was also
reading `traderx_book_open_orders` (three proofs), `queueDepth` (two), an orphan count, a venue-wide
book digest compared **across time**, two members' reindexes compared for **equality**, and a
gateway in-flight depth sampled once. The repairs are the same three moves every time: read an
operator-scoped counter, measure a bracket instead of an equality, or assert the **identity** of the
thing itself — an order's own `CANCELED` row, a probe id named and then not named, a book's own
`tickPx`.

**Two of these had a private copy of a reading the library exists to be the only source of** —
`yu13-readmodel-effect-end` and `yu17-keyed-ack-correlation`, a seventh and eighth dependent file
beyond the six this ADR lists. They were missed because the audit after the *feed adapter* swept for
`applied`, and by then the proofs had already retreated to the counter this change breaks. **The
lesson generalises exactly as this ADR predicted, and it generalises to the audit too:** sweep for
the reading the proofs have retreated *to*, not the one they retreated *from*.

**2. An operator order left resting on a replayed book.** This one no counter can fix, and it is
worth stating plainly: the trade counter is scoped **per leg, by the account of the leg**, so when
the replay crosses an operator's resting order the operator's leg is genuinely the operator's. The
counter is right and the exact-delta reading is disturbed anyway. `yu17-retick-determinism` read
"trades moved by 3, expected 2" on a scenario whose own orders were on a freshly minted ticker and
were entirely correct — the extra leg was a **fixture-seeder order left resting on NVDA**, filled by
the tape at an unrelated moment. The remedy is hygiene, not accounting: `seed-proof-fixtures.sh`
sweeps every demo account's resting orders before each proof, and the rule is recorded where the
next proof author will meet it.

### Two proofs pause the replay, and the distinction matters

`yu16-ready-tracks-commit` needs a window in which **nothing commits** — that gap is its entire
content — and `yu13-stp-and-replace` rolls the members onto **yu15-era builds that export no
operator-scoped counter at all**. Neither can be repaired by a better reading, because both need the
*absence* of something a continuous writer denies. They scale `price-publisher` to zero for the
scoped step and restore it on every exit path, the same way one of them already removes quorum and
the other already removes the current engine.

**That is not the general escape hatch, and the record should not be read as licensing one.** Nine
of the eleven repairs kept the replay running, which is the whole point: *a green suite that only
passes because the replay happens to be off is not a green suite.*

### And the trap this ADR's own proof walked into

`yu17-replay-attribution` mints its own ticker and **crosses** on it — that is its anti-vacuity arm,
four of its own orders and four trade legs inside the live replay. A net-zero position in an
instrument no EOD universe will ever price halts that account's P&L, and the failure surfaces **a
full suite later** in `yu15-risk-extract` as "an unpriced holding blocks its P&L". The seeder's
throwaway-prefix list documents this trap at length and had already fallen behind by nine prefixes
once; adding a crossing proof without adding its prefix is exactly how it falls behind. Measured
cleanly: the run in which this proof first PASSED is the run after which `yu15-risk-extract`
reported `halted=2`. The two runs before it were clean only because the proof had failed before
reaching its crossing step.

## Related

- [ADR-070](adr-070-the-tape-is-the-reference.md) — the tape as reference; this lifts one of its
  exclusions and is bound by the rest
- [ADR-066](adr-066-price-band-follows-the-market.md) — the collar the replayed flow will be tested against
- [ADR-068](adr-068-external-price-sources.md) — the durability rule, unchanged
- `scripts/proofs/lib-consensus-readings.sh` — the attribution contract this breaks, and the test any
  replacement must pass
