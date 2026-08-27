# The ADR-072 counter countermeasure stops one metric short

**Status:** open
**Found:** 2026-08-27, by the coordinator, while verifying the ADR-072 lane's suite handback
**Class:** vacuous / false reading — a proof measuring a venue-wide number as if it were its own

## What ADR-072 broke, and what was built to fix it

ADR-072 turns replayed TAQ prints into real order flow. Its amendment names the four counters a
replayed order therefore moves:

    traderx_cluster_next_order_ref    ORDER_NEW consumes a ref on apply
    traderx_cluster_trades            any replayed fill books legs
    traderx_band_reanchors            the band slot is reached from order placement
    traderx_band_stranded_cancels     same path

The countermeasure was an **operator-scoped twin for each**, so a proof can measure its own effect on
a venue with a continuous foreign writer in it. All four exist:

    traderx_cluster_operator_next_order_ref
    traderx_cluster_operator_trades
    traderx_band_operator_reanchors
    traderx_band_operator_stranded_cancels

`lib-consensus-readings.sh` reads the twins, and its `assert_order_effects` bracket — the ref delta
is what makes the trade delta attributable — is the right shape. **Proofs that go through the library
are protected.**

## The gap

**`traderx_book_open_orders` is a fifth contaminated reading and has no operator-scoped twin.**
Replayed flow rests and pulls orders on the tape's own books continuously, so the gauge moves for
reasons that belong to no proof. It carries only a member label; there is no per-ticker or
per-operator cut of it.

**17 proof scripts read it directly**, outside the library:

    yu12-gke-failover-transparency   yu13-cancel-ingress            yu13-gke-replace-proof
    yu13-readmodel-effect-end        yu13-stp-and-replace           yu16-invisible-orders-repro
    yu17-band-follows-market         yu17-book-retick               yu17-closed-survives-restart
    yu17-fine-grid                   yu17-fnma-collar               yu17-halt-survives-failover
    yu17-keyed-ack-correlation       yu17-option-collar             yu17-preopen-queue-open
    yu17-retick-determinism          yu17-session-closed-rejects

**Most of those uses are sound.** Comparing the gauge across m0/m1/m2 for agreement is valid no matter
who wrote the orders — that is the book-digest idiom and it is not affected. **The exposed form is the
delta: reading it before and after a window and attributing the difference to this proof's order.**

## The instance already found

`yu13-readmodel-effect-end` step 4 asserted *"exactly one order left the replicated book"* across it
and measured **287 -> 284** on a correct cancel (fixed in `c691b849` by scaling `price-publisher` to 0
for the measurement, restored on the `EXIT` trap).

**It had passed the run before.** At ~6 prints/s the window is often quiet and the delta is often
exactly 1 — so the failure mode is a **flaky green**, not an honest red, and the proof was reporting
success on a reading it could not support. Two of the other 16 readers scale the publisher down the
same way (`yu13-stp-and-replace`, `yu16-ready-tracks-commit`); the rest do not.

## Why the per-site fix is not the end of it

Pausing the tape is a **workaround for a missing counter**, and it has costs the twins do not: it
mutates shared rig state mid-suite, it needs a trap to avoid stranding the rig with no feed, and it
makes the proof's reading depend on a scale-down succeeding. The lane was right to reject the cheaper
alternative — falling back to read-model readings would have traded away step 4's only consensus-level
ground truth — but the durable version of that argument is **a gauge that is ground truth without
needing a quiet venue.**

## What to do

1. ~~**Add `traderx_book_operator_open_orders`** (or an operator label on the existing gauge), matching
   the four twins that already exist.~~ **SUPERSEDED 2026-08-27 — do NOT build this twin.** See
   *"Correction to the recommendation above"* below: the gauge is over resting state, so there is no
   monotonic shadow to subtract, and by the persistence rule its shadow would need snapshotting —
   a format bump and a mandatory fresh-epoch mint. **Its call sites want an identity claim.**
2. **Enumerate which of the 17 take a delta rather than a cross-member compare**, and convert those.
   A regex cannot separate the two reliably — this needs reading the assertions.
3. **Leave the cross-member comparisons alone.** They are correct as written.

## The unfixed sibling, in the worse direction

`scripts/proofs/seed-option-chain.sh:88-96` brackets `traderx_order_events_total{event="fill"}` and
asserts `fills_after -gt fills_before` to prove an option cross booked — the gate it calls *"the
silent-reject gate, exercised."* That counter is venue-wide with no operator twin, so **a replayed
equity fill in the same second satisfies it while the option cross is silently rejected.** Not an
exact delta, so it does not go red; it goes **vacuously green**, which is the direction that hides
things. It is not invoked by `run-proofs.sh` today (it is a manual YU14 seeder, referenced from
`scripts/yu15/seed-proof-fixtures.sh:175`), so this is latent rather than live.

---

## Enumeration done (2026-08-27, ADR-072 lane) — and it widened

The seventeen `traderx_book_open_orders` readers, resolved. **Fourteen are the cross-member
agreement idiom and are correct as written**; the claim is *that the members agree*, not that any
number is right, so who wrote the orders is irrelevant. Of the remaining three:

    yu13-readmodel-effect-end:251   FIXED c691b849 (replay paused for the measurement)
    yu13-stp-and-replace:785        COVERED - pause_replay at :335, restore only on the EXIT trap
    yu13-cancel-ingress:465,475     SAFE   - computed, never asserted; appears only inside another
                                             assertion's failure text at :505
    yu13-gke-replace-proof:150,176  EXPOSED - both forms, identical shape to the 287->284 instance

**`yu13-cancel-ingress` had already been fixed for this exact class on 2026-08-26** (measured
585 -> 577 on a correct cancel) by switching to an identity claim — the read model's own
`CANCELED` status — rather than a venue-wide count. **That is the third proof this class has bitten**,
and the fix it landed on is the better pattern: *ask the order what happened to it.*

### The scope was mine and it was too narrow

I scoped the enumeration to one gauge, so that is what came back. Reading the exposed file's other
assertions shows the class is defined by **the shape of the reading, not the name of the metric**:

    yu13-gke-replace-proof
      trades_all() -> traderx_cluster_trades          RAW, though an operator twin EXISTS
      refs_all()   -> traderx_cluster_next_order_ref  RAW, though an operator twin EXISTS
      stp_all()    -> traderx_stp_cancels             RAW, and NO twin exists

and asserts exact per-member deltas on all of them (`t1 == t0` for "booked no self-trade",
`s1 == s0 + 1` for "exactly one STP cancel"). **Any replayed fill breaks the first; replayed flow
tripping self-trade prevention breaks the second.** So that file carries five-plus exposed
assertions, not the two found by gauge name.

### Why no metric-name search sorts this

**The same helper serves a sound purpose and an exposed one in the same file.** `trades_all` feeds
`uniq_one` for cross-member agreement — correct — *and* a per-member exact delta — exposed. This is
why a regex over metric names cannot separate them, and why the enumeration had to be a line-by-line
read of the assertions.

### Revised recommendation

1. ~~**Two gauges need twins, not one:** `traderx_book_operator_open_orders` **and**
   `traderx_stp_operator_cancels`.~~ **SUPERSEDED the same day — only the STP twin was correct**, and
   it is built and verified live (`6374c110`). **Do not build the book-gauge twin;** see the
   correction section below for why, and reach for the identity claim instead.
2. **The larger gap is adoption, not coverage.** Twins exist for `cluster_trades` and
   `next_order_ref` and are the minority of reads — 18 raw `cluster_trades` reads against 5
   operator-scoped, 22 raw `next_order_ref` against 8. Proofs keep reaching for the raw name.
3. **Prefer the identity claim where one exists** (`yu13-cancel-ingress`'s pattern): a count standing
   in for "THIS order did X" can be replaced by asking the order. That needs no counter at all.
4. **Leave the fourteen cross-member comparisons alone.**

`yu13-gke-replace-proof` is unreachable from `run-proofs.sh` (0 mentions), so **nothing in the suite
will ever red it** — it fails months from now, on GKE, far from the change that broke it. Recorded in
the GKE proof family issue (`aea72ac1`) rather than fixed here.

---

## Correction to the recommendation above: the two twins are not the same size, and neither needs a mint

I recommended "two twins" without checking what a twin costs. **It is cheaper than feared and unequal
between the two.**

The existing operator counters are **derived by subtraction at scrape time, not stored**
(`ClusterNodeMain.java:438-460`):

    traderx_cluster_operator_trades            = trades - engine().externalTradeLegs()
    traderx_band_operator_reanchors            = bandReanchors() - externalBandReanchors()
    traderx_band_operator_stranded_cancels     = bandStrandedCancels() - externalBandStrandedCancels()

and the code states the reason: *"PER-PROCESS like their siblings — the replayed shadows are NOT
snapshotted either, so the subtraction stays consistent on a restarted member instead of going
negative."*

**That is true of the BAND pair only, and I first wrote it here as though it were true of all four.**
The comment quoted above sits directly over the band pair and describes the band pair. Corrected by
the ADR-072 lane (`7a527ac6`), confirmed here in the snapshot writer and reader:

    MatchingEngineClusteredService:1499   snapshotBuffer.putLong(52, externalOrderRefs)
    MatchingEngineClusteredService:1500   snapshotBuffer.putLong(60, engine.externalTradeLegs())
    MatchingEngineClusteredService:1631   externalOrderRefs = buffer.getLong(offset + 52)
    MatchingEngineClusteredService:1632   engine.bootstrapExternalTradeLegs(buffer.getLong(offset + 60))

    externalBandReanchors / externalBandStrandedCancels   0 snapshot-path hits

**`externalOrderRefs` and `externalTradeLegs` ARE snapshotted, and had to be** — their parents
`nextOrderRef` and `tradeCounter` are, so a restored member that kept the parent and lost the shadow
would subtract zero and report operator counts far too high. **That is what cost `SNAPSHOT_FORMAT` 9
and its mandatory fresh epoch.**

### The rule, which is the durable part

**A shadow must have the same persistence as its PARENT.**

    snapshotted parent  ->  snapshotted shadow  ->  format bump  ->  fresh-epoch mint
    per-process parent  ->  per-process shadow  ->  neither

Get it wrong and the failure is silent: a restored member subtracting a shadow its parent outlived
reports a **plausible wrong number**, not an error. Recorded above the four twins in `ClusterNodeMain`,
where anyone adding a fifth is already reading, with all four classified explicitly.

### The two are not equivalent

- **`traderx_stp_operator_cancels` — trivial, and it survives the correction above.** Checked against
  the rule rather than inherited from it: `selfTradesPrevented` is a plain field (`MatchingEngine:139`),
  incremented in the apply path (`:866`), read at `:1510`, and **absent from both the snapshot writer
  and the reader**. Per-process parent, so a per-process shadow, so **no format bump and no mint.** It
  needs an `externalSelfTradesPrevented()` beside the three that exist, plus one subtraction line.
- **`traderx_book_operator_open_orders` — genuinely harder, and probably the wrong fix.** Open orders
  is a **gauge over resting state**, not a monotonic counter, so there is no shadow to subtract; it
  would need resting orders tracked by account range. **And the two sites that want it
  (`yu13-gke-replace-proof:150,176`) are asking "did THIS order leave the book" — an identity claim.**
  That is fix #1 in the ranked order, needs no counter at all, and asserts something stricter.

**Revised: build the STP twin, and do not build the book-gauge twin.** Convert its call sites to the
identity claim instead — which is what `yu13-cancel-ingress` independently arrived at on 2026-08-26.

---

## A sweep is bounded by REACHABILITY, not by metric name (2026-08-27)

The seventeen-reader enumeration above was complete **for the code that runs**, and it still missed a
live site in a file it had already examined.

`yu13-cancel-ingress:470` asserts venue-wide digest **equality** across a window:

    [[ "${AFTER_PRE}" == "${BEFORE_PRE}" ]] || fail "the pre-fix gateway somehow changed the book"

`book()` (`:128-132`) reads `traderx_book_open_orders` — the venue-wide gauge with no operator twin —
and pairs it with the order hash. Under ADR-072's continuous replay this cannot hold: measured live
**436 -> 438** across the window, two orders arriving from the tape, and **the proof blamed the
gateway.** It fails in the accusatory direction, naming an innocent component.

**The enumeration marked this file's `:465,475` SAFE, and that was correct** — those are computed and
never asserted. `:470` is a different site, in the same file, reading the same gauge through a helper.

**It sat behind `if [[ "${SKIP_REGRESSION}" == "0" ]]`, which had never been true**, because
`IMAGE_PRE` did not exist. **Dead code hid a live defect.** No metric-name search, no diff and no green
run could have reached it; it became visible only when the missing artifact was built
(`traderx/cluster-node:precancel-36e693ab`, 2026-08-27) and the branch executed for the first time.

The comment at `:467-468` already says a claim contradicting the data printed beneath it *"is worse
than silence"* — sitting directly above the assertion that is itself in the class.

**Fix:** the identity claim, as everywhere else. The proof's actual claim is *"the pre-fix gateway
could not cancel"*, and the 404 already establishes there was no route; `:470` was only corroboration.
Assert the order's own state — **still resting, not CANCELED** — which needs no venue count and is
strictly stronger than "nothing moved".

**Do not point the proof's `IMAGE_PRE` default at the new image until `:470` is fixed** — that converts
a proof which *skips* in `run-proofs.sh` into one that *fails* it.

## The failure mode with nothing to detect: a right answer with a wrong mechanism

Worth its own row, because every other entry on this list produces a **wrong reading** and this one
produces a **right** one.

Measuring whether replay moves `traderx_book_reticks`, this coordinator recorded **0 over 60 replayed
trade legs** — true, reproducible — and explained it as *"the geometry is already stored in `T_BOOK`,
so replay never triggers a retick."* **That mechanism is bonds only.** Equities return `0` from
`derivedBookTickPxFor` and fall through to `decadeTickPx`, stepping at $1/$10/$100/$1000, so for every
symbol the replay trades the tick **is** a function of the reference price.

They are quiet for two different reasons: replayed flow keeps tape books **occupied**, so the
empty-book gate short-circuits; and no tape symbol's range crosses a decade (widest: GS $520-$670,
COF $170-$210, both inside $100-$1000).

**Same measurement, same verdict, opposite durability.** "Never, by construction" retires the question
forever. "Quiet because books are busy and no symbol crosses $100" is a standing hazard with a named
expiry — **the queued `PRICE_TICKERS` widening.** Only the mechanism decides whether the finding
survives, and no check that looks at outputs can tell the two apart.


---

## Note on how this file was wrong (2026-08-27)

**This issue recommended building `traderx_book_operator_open_orders` in three places and advised
against it in one.** A reader meets the first recommendation ~120 lines before the correction, and
would have spent a snapshot format bump and a mandatory fresh-epoch mint on a twin that was decided
against the same day. Caught by the chip working `yu13-gke-replace-proof`, which met the stale advice
in a tracking issue derived from this one.

**The cause is a habit, not an oversight: corrections were APPENDED rather than applied to the body.**
The same file already carries the lesson — *"corrected in place, editing the wrong claim rather than
appending beneath it, because a wrong rule in the durable artifact is worse than no rule and a reader
meets the body before the addenda"* — and it was applied to one claim while two live recommendations
were left standing above it.

**A superseded recommendation is not history, it is an instruction.** Strike it where it stands, and
point at what replaced it.
