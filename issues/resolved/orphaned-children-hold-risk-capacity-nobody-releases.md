# Orphaned children hold risk capacity that nobody will ever release

**RESOLVED 2026-08-24** on `YU17-otc-rates` — as a **decided report**, not a mechanism. The title is
wrong and the *Resolution* says why: the reservation is correct accounting for a live order, not a
leak, and the separation this file asks for is not available. Measurement and pins in `82292970`.
**Yaakov's no-auto-cancel decision stands** and is reinforced, not revisited.

> A record, not a rig you can query. Account ids, order refs and share counts come from the epoch
> this was measured on and will be rolled. Read them as the shape.

Follow-up to `issues/resolved/a-torn-algo-log-replays-clean-and-orphans-live-children.md`, which made
a torn log *say so* but deliberately did not repair it.

## The decision this sits under

**Yaakov's call, 2026-08-21: do NOT auto-cancel orphaned children.** That is the right call — cancelling
live orders automatically, on the strength of an engine having lost its own memory, is a destructive
action taken on incomplete information. This issue is not a request to revisit it. It is the
consequence that decision leaves behind, written down so it is chosen rather than inherited.

## The consequence

An open order is not free. The risk gateway holds a **reservation** against the account for as long as
the order rests — `reservedNotional`, `reservedBuyNotional`, `reservedSellNotional` and
`reservedBuyQtyByExposure` / `reservedSellQtyByExposure` in `BlpRiskState`, which the class comment
describes as rebuilt from open-order reservations at snapshot restore.

An orphaned child rests forever:

- it will never fill (unless the market comes to it),
- it will never be cancelled, because the parent that would cancel it no longer exists and, by the
  decision above, nothing else will,
- so its reservation is never released.

Every torn log therefore takes a permanent bite out of that account's tradeable capacity. It does not
recover on its own, and it **compounds** — each subsequent tear removes more. In the worked example a
single evening's two tears left twelve children resting, several hundred shares' worth of reservation,
against one account.

The failure mode when capacity finally runs out is a *rejection of a legitimate new order*, arriving
long after the incident that caused it, with nothing connecting the two.

## Status of the evidence — READ, NOT MEASURED

**Measured:** the orphaned children exist and rest with `status=NEW` in the read model, and the engine
holds no parent for them.

**Read from source, not observed:** that they consume reservations and that nothing releases them. The
reservation fields were read off the operative `BlpRiskState`; the leak is inferred from the mechanism,
not watched.

**Not established:** the size of the bite, whether a snapshot restore rebuilds reservations for orders
whose parent is gone (the comment implies it rebuilds from the open orders themselves, which would mean
the leak *survives* a restore, but that was not tested), and whether an epoch roll clears it.

**There is no read endpoint for reserved notional per account.** The gateway exposes `/risk/control` but
no risk *read*. That absence is why this is inferred rather than measured, and it is arguably the first
thing to fix — an invariant nobody can observe is one nobody can prove is holding.

## Directions, not a decision

1. **Make it observable first.** A per-account reserved-notional read would turn this whole issue from
   an argument into a measurement, and is useful well beyond this case.
2. **Release on orphan detection, without cancelling.** The engine now names the orphaned parents. If
   the reservation can be released while the order continues to rest, capacity is recovered without any
   destructive action — this may not be coherent, since the order is still live and could still fill,
   but it is the option that respects the no-cancel decision.
3. **Surface, don't fix.** Report orphaned resting exposure on an operator surface and let a human
   cancel deliberately. Consistent with the recovery-not-repair line already drawn.
4. **Accept it.** Legitimate if tears are rare and capacity is generous — but then say so, with the
   compounding written down, so the eventual mystery rejection has a documented cause.

## Lineage warning

`BlpRiskState.java` is carried by **three** layers — `YU03-in-memory-risk-gateway`,
`YU14-listed-equity-options` and `YU17-otc-rates` — and **YU17 is operative** (verified by diffing each
against the generated tree). A fix to the YU03 or YU14 copy is inert and looks exactly like a fix that
did not work. This file has already cost this project one such mistake; see
`.claude/skills/propagate-spec-fix`.

---

## Resolution — DECIDED 2026-08-24, and it is a finding, not a mechanism

`82292970` — `risk: pin why a live order's reservation cannot be released without cancelling it`.

Nothing in the risk gateway's behaviour changed, and that is the result rather than a shortfall. The
separation this issue asks for — release the capacity accounting, leave the live order alone —
is not available in this design, and the reason is worth more than the fix would have been.

### The reservation is not leaked. It is CORRECT.

An orphaned child is a live order that can still fill. Its reservation is the accurate accounting
of that obligation. The risk gateway never knew the order had a parent — it has no concept of one —
so there is nothing for it to release, and releasing it would make risk state lie about exposure
that genuinely exists.

That reframes the title. What is left behind by a torn log is not leaked capacity; it is **resting
exposure that no engine owns**, which is the deliberate, decided consequence of
**yaakov's 2026-08-21 call not to auto-cancel orphaned children**. That call stands, and it stands
for a stronger reason than this issue gave it: releasing the reservation of a live order is
economically identical to assuming a cancel without performing one — the destructive act, taken
silently instead of deliberately.

### Why the separation is impossible — MEASURED, not read

The parent issue's weakness was that the leak was inferred from source. This was measured against
`BlpRiskState` in the `order-matcher` module — first with a throwaway probe, now permanently, as the
two tests described under *What landed* below:

```
reserve 100 @ 100.000      reservedNotional(22214) = 10,000,000,000
release() while still live  reservedNotional(22214) = 0,  holder qty = 0
the live order then FILLS   executedNotional(22214) = 0   <- that fill was worth 10,000,000,000
```

`consume()` opens with `if (reservedQty <= 0) return;` — its exactly-once guard. So releasing a
live order's reservation does not merely hand back the reservation: it makes that order's **real
fill** invisible to `executedNotional` permanently. The credit gate reads
`executedNotional + reservedNotional`, so a bounded and conservative over-hold would be traded for
an **unbounded under-count of real executed credit exposure**. That is the wrong direction for a
risk gateway.

The obvious alternative — decrement the account aggregate only and leave the holder — is undone by
the restore path, on the same probe:

```
before restore 10,000,000,000  ->  after reaccumulateReservation 10,000,000,000
```

`bootstrapOrder` re-accumulates for every OPEN order, so the aggregate is rebuilt from the holders
and the "released" capacity returns at the next snapshot restore. The class comment states this as
the invariant it is: aggregates and per-order reservations can never disagree.

The structure agrees with both measurements. **Every** `release()` call site in `MatchingEngine`
either removes the order from the book (cancel, STP, band-stranded, market remainder) or re-reserves
immediately (the amend path, which restores exactly on rejection). No call site anywhere releases
while leaving an order live and unchanged. Reservation ≡ live open order is one-to-one by design,
and the design is what makes credit accounting correct.

### What this issue asserted that did not survive checking

1. **"nobody will ever release it"** — false as stated. Every ordinary release path reaches an
   orphaned child without any parent: an explicit cancel, a fill, self-trade prevention, a band
   re-anchor stranding it, and `POST /risk/control/restriction <ticker>`, which cancels every
   resting order in that security. What is true is only that nothing releases it **automatically** —
   and per the decision above, nothing should.
2. **"There is no read endpoint for reserved notional per account"** — partly false.
   `traderx_risk_reserved_notional_total` is already exported on the Spring tier's `/metrics` as an
   all-accounts total. Per-account exists nowhere. The real gap is narrower and sharper than the
   issue states: the **cluster tier** — `ClusterNodeMain`, the only rig — exports no risk gauge at
   all.
3. **"whether a snapshot restore rebuilds reservations for orders whose parent is gone ... was not
   tested"** — now settled: **it does**. The reservation survives snapshot restore and failover.
4. **"the size of the bite"** — now settled: exactly `qty x validationPrice x contractMultiplier`
   in Px ticks, booked at `decideAndReserve`.

### The disposition

**Direction 4 — accept — but for the corrected reason.** Not "accept a leak because tears are
rare", which would have been accepting a defect. The reservation is right; the residual is un-owned
resting exposure, and that is the chosen cost of not cancelling live orders on the strength of an
engine having lost its own memory.

The compounding stands as written and is the part to keep: each tear leaves more resting exposure,
it does not recover on its own, and the eventual failure mode is a **rejection of a legitimate new
order long after the incident that caused it**. This file is the documented cause that mystery
rejection would otherwise not have. An operator who finds one has a deliberate remedy today — cancel
the named orders — and the algo engine's `REPLAYED_WITH_ORPHANS` verdict is what names the parents.

### What landed, and what deliberately did not

**The measurement became two permanent tests** (`82292970`, `BlpRiskStateTest` 16 → 18). This close
originally rested on a throwaway probe that was deleted — a decision resting on a number nobody can
re-run is a weaker artifact than the issue it closes. The tests assert the measured **behaviour**,
not the decision, so they fail if either property ever stops holding:

| defect injected | failed |
|---|---|
| `consume()` books a released order's fill anyway | `aReservationReleasedWhileItsOrderIsStillLiveLosesThatOrdersFillEntirely` alone |
| `reaccumulateReservation` stops rebuilding from the holder | `anAggregateOnlyReleaseIsRebuiltFromTheOrderAtSnapshotRestore`, plus the pre-existing restore-parity test |

They live in the **YU14** layer — `BlpRiskState.java`'s main is YU17 but its test is carried by
YU14. That is the trap this file already warned about, pointing the other way.

**Observability was NOT built, and that is a deferral rather than a judgement.** The issue's own
direction 1 — a per-account reserved-notional read — is right, and it is now
`issues/open/the-cluster-tier-exports-no-risk-gauge.md` with a ~15-line sketch. It was held back for
**collision, not merit**: it lands in `ClusterNodeMain.java`, which another lane had open in this
same worktree at the time. Two lanes in one file is how this project loses work. Until it exists,
the size of any real outstanding bite on a rig remains unmeasurable — which is the one part of this
issue that is still genuinely open.

### Still not established

- **Whether an epoch roll clears it was not exercised.** It follows from the roll procedure —
  scale to zero, wipe the PVCs, fresh epoch leaves no resting orders — but it was not watched, and
  the rig was off limits for this work.
- **No number was taken from a live account.** The mechanism is measured; the size of any actual
  outstanding bite on the rig is not, and cannot be until per-account reserved notional is readable
  somewhere.
