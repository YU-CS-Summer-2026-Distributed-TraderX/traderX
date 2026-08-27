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

1. **Add `traderx_book_operator_open_orders`** (or an operator label on the existing gauge), matching
   the four twins that already exist. This closes the class rather than the instance.
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

1. **Two gauges need twins, not one:** `traderx_book_operator_open_orders` **and**
   `traderx_stp_operator_cancels`.
2. **The larger gap is adoption, not coverage.** Twins exist for `cluster_trades` and
   `next_order_ref` and are the minority of reads — 18 raw `cluster_trades` reads against 5
   operator-scoped, 22 raw `next_order_ref` against 8. Proofs keep reaching for the raw name.
3. **Prefer the identity claim where one exists** (`yu13-cancel-ingress`'s pattern): a count standing
   in for "THIS order did X" can be replaced by asking the order. That needs no counter at all.
4. **Leave the fourteen cross-member comparisons alone.**

`yu13-gke-replace-proof` is unreachable from `run-proofs.sh` (0 mentions), so **nothing in the suite
will ever red it** — it fails months from now, on GKE, far from the change that broke it. Recorded in
the GKE proof family issue (`aea72ac1`) rather than fixed here.
