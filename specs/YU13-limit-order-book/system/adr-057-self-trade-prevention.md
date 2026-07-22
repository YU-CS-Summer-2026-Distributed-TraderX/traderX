# ADR-057: Self-trade prevention policy

## Status

**Proposed — not implemented.** This ADR exists to settle the policy *before* any code, because a
wrong STP policy compiles, is deterministic, is zero-allocation, passes every gate, and is still
wrong semantics. Unwinding it later is expensive: downstream proofs bake in the behaviour.

## Context

[ADR-049](adr-049-crossing-limit-order-book.md) closes with the consequence *"Self-crossing (an
account matching its own resting order) is permitted; the book has no self-trade prevention."*
That was accurate and remains true. `MatchingEngine.cross()` takes `book.headAt(restingSide, opp)`
and fills it; `accountId` is carried on `RestingOrder` and never compared between aggressor and
resting order.

Two consequences make this worth deciding now:

1. **It is a regulatory gap for a sell-side venue.** A wash trade between two orders of the same
   account is a reportable event at every real venue; most offer STP as a mandatory or opt-in
   control.
2. **It silently shaped our own measurements.** Every wire-to-wire latency run to date used a
   self-crossing single account "so the flow keeps booking." Those runs are valid as throughput
   measurements, but *every fill in them was a self-trade*. Under any STP policy that load shape
   books nothing at all.

### The constraint that narrows the field

`cross()` is a `while` loop that re-reads the level head each iteration:

```java
while (a.remaining > 0 && opp != LimitBook.NO_LEVEL) {
    final RestingOrder r = book.headAt(restingSide, opp);
    ...
    opp = buy ? book.bestAskSlot() : book.bestBidSlot();
}
```

`headAt` always returns the head of the level. **A policy that "skips" a self order without
removing it from the book returns the same order on the next iteration, `opp` never advances, and
the loop does not terminate** — inside the replicated state machine, on the apply thread. Every
member would wedge identically, and so would replay. Determinism does not save you here; it
guarantees all three members hang the same way.

So "skip the self order and match deeper" is not available without adding a per-level traversal
cursor. That cursor would be new traversal state, and it must not outlive the apply or it becomes
snapshot-bearing. **Any viable policy must remove one of the two orders, or terminate the loop.**

## Options considered

Worked against one scenario throughout. Aggressor: **buy 100 @ limit 105**. Resting asks:

| Level | Order | Account | Qty |
|---|---|---|---|
| 100 | A | other | 30 |
| 100 | **S** | **same as aggressor** | 40 |
| 102 | B | other | 50 |

The aggressor fills 30 against A, then meets its own order S with 70 remaining. This is the
"partway through consuming several price levels" case, and it is where the policies diverge.

| Policy | Outcome for this scenario | Loop progress | Reaches B? |
|---|---|---|---|
| **Cancel newest** (cancel the aggressor's remainder) | filled 30, **70 canceled** | terminates (remaining → 0) | **no** |
| **Cancel oldest** (cancel the resting order) | S canceled; aggressor continues, fills 50 vs B; **20 rests** | S leaves the book | **yes** |
| **Cancel both** | S canceled, aggressor's 70 canceled | terminates | **no** |
| **Decrement and cancel** | both reduced by min(70,40)=40 → S gone, aggressor 30; fills 30 vs B, **fully done** | S leaves the book | **yes** |

The critical asymmetry: **cancel-newest and cancel-both let one stale self quote at a price level
block access to every legitimate counterparty behind it.** The aggressor's order dies with B — a
willing counterparty at an acceptable price — untouched. That is a worse execution outcome than
the self-trade it prevents, and it is invisible to the client, who sees only a partial fill and a
cancel.

## Decision

**Adopt cancel-oldest: on a self-match, cancel the resting order and let the aggressor continue.**

Recommended, subject to the review this ADR exists to trigger.

Rationale:

- **Best execution.** The aggressor still reaches genuine liquidity behind its own order. This is
  the only property that distinguishes the options in a way clients can feel.
- **It reuses proven machinery.** Cancelling a resting order is exactly what `onCancel` already
  does — `book.remove(o)`, `risk.release(...)` exactly once, `status = STATUS_CANCELED`,
  `markTerminal(...)`, `emitOrderUpdate(FLAG_CANCEL)`. That path is now covered end-to-end by the
  cancel-ingress work and its falsifiable proof, so STP inherits a tested mechanism rather than
  introducing one.
- **Loop progress falls out for free.** The self order leaves the book, so the next `headAt` is a
  different order. No cursor, no new traversal state.
- **Smallest client surprise.** The passive side is cancelled. Decrement-and-cancel silently
  shrinks a *live* aggressor without a fill, which is the least intuitive outcome of the four and
  the hardest to reconcile against a FIX client's own order state.

Deliberately **not** chosen:

- **Decrement-and-cancel**, despite being the policy regulators most often favour, because its
  purpose is to stop firms using STP to gain queue position — a threat model that does not apply
  to a single-tenant venue at this stage. It is the natural upgrade if that changes, and cancel-
  oldest does not foreclose it.
- **Cancel-newest**, the common opt-in default elsewhere, because of the liquidity-blocking
  behaviour above.

### Scope: global, not per-account

The policy ships as a **global compile-time constant**, not per-account or per-trading-group
configuration. This is load-bearing for the snapshot analysis below — see Consequences.

### What the client sees

| Side | Message |
|---|---|
| Resting order (cancelled) | `ExecutionReport`, `ExecType=4` (Canceled), `OrdStatus=4`, `Text(58)="self-trade prevention"` |
| Aggressor | unchanged — ordinary fills against A and B, then rests or is done |

Internally the cancelled order carries a new `RiskReason` ordinal, `SELF_TRADE_PREVENTED`, in its
existing `RestingOrder.riskReason` byte, so the audit log and any future read model can tell an STP
cancel from a client cancel. FIX 4.4 has no dedicated field for this (FIX 5.0 added
`SelfMatchPreventionID`), so `Text(58)` is the honest carrier on the wire.

**The new enum value must be APPENDED to `RiskReason`, never inserted.** The ordinal is persisted
in snapshots via `riskReason`; inserting a value renumbers every ordinal above it and silently
misdecodes every existing snapshot.

## Snapshot impact — confirmed, not assumed

The brief's expectation was "STP likely adds no snapshot state since it's a matching decision
rather than stored state — confirm that rather than assume it." Confirmed, **conditionally**:

| Element | Already snapshotted? | Verdict |
|---|---|---|
| `accountId` on aggressor and resting order — the whole input to the decision | yes (`bootstrapOrder` takes `accountId`) | no change |
| Outcome: an order becomes `STATUS_CANCELED`, leaves the book, reservation released | yes — identical to an ordinary cancel | no change |
| `RiskReason.SELF_TRADE_PREVENTED` | it is a new *value* in the existing snapshotted `riskReason` byte, not a new field | no new tuple; **append-only** |

**No new snapshot tuple type, and no snapshot format bump — provided the policy stays global.**

The condition is the finding. If STP is ever scoped per-account or per-trading-group, as real
venues do, that scoping IS new replicated state: it needs its own sequenced control event
(alongside `TYPE_ACCOUNT_CONTROL`), a new snapshot tuple type, and a format-version bump. Shipping
it global first is what keeps snapshot format 3 intact.

## Determinism

The decision is `aggressor.accountId != resting.accountId` — an `int` comparison between two
fields of replicated state, evaluated on the apply thread in consensus-log order. No wall clock, no
map iteration, no arrival-order input outside the log. Every member reaches the identical verdict
from the same log position, and replay reproduces it.

Allocation: an `int` compare plus the existing cancel path, which is already on the zero-allocation
hot path. Expected to pass `noGcTest`, `riskNoGcTest` and all four allocation gates unchanged — to
be verified, not assumed, when the code lands.

## Consequences

- **Every existing benchmark load shape is invalidated.** The wire-to-wire latency runs and the
  crossing-flow throughput runs use a single self-crossing account; under this policy they book
  zero fills and cancel the resting book instead. Every bench harness must move to ≥2 accounts
  before STP is enabled, and **no post-STP number may be compared against a pre-STP number taken
  on single-account flow.**
- **A price level stacked with the aggressor's own orders costs one cancel per order, in a single
  apply.** A level holding 10k self orders means 10k cancels inside one consensus-log apply — a
  real tail-latency spike and an apply-thread barrier. Bounded by level depth, but worth measuring
  rather than assuming.
- ADR-049's closing consequence — "the book has no self-trade prevention" — is superseded on
  implementation and must be edited, not merely contradicted here.
- Self-trades become impossible, so any future wash-trade surveillance has nothing to detect from
  this path — which is the point, and is worth stating in the compliance narrative.
- `MatchingEngine.cross()` gains a branch on the hot path. The branch is perfectly predicted in the
  common case (accounts differ), so the steady-state cost should be nil; confirm on the bench.

## Open question for review

Cancel-oldest lets a participant remove their own resting order by aggressing into it. That is a
queue-position vector at multi-tenant venues, and it is the reason decrement-and-cancel exists.
Here it is not a real gain — the participant can simply cancel the order directly, which is now
possible via the cancel ingress. **Flagging it explicitly rather than leaving it implicit**: if
this platform ever becomes multi-tenant, this ADR should be revisited in favour of
decrement-and-cancel.
