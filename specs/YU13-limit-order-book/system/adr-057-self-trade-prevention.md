# ADR-057: Self-trade prevention policy

## Status

**Accepted and implemented** (2026-07-22). Reviewed and endorsed as written; cancel-oldest is in
`MatchingEngine.cross()`, `RiskReason.SELF_TRADE_PREVENTED` is appended, and
[ADR-049](adr-049-crossing-limit-order-book.md)'s "the book has no self-trade prevention"
consequence is edited rather than merely contradicted.

Three things were required alongside the code and are recorded below: the global scope is written
down as a deliberate simplification with its named upgrade path (§*Scope*), the message the client
receives is specified (§*What the client sees*), and the append-never-insert rule for the new
`RiskReason` ordinal is enforced (§*What the client sees*).

This ADR was deliberately settled *before* any code, because a wrong STP policy compiles, is
deterministic, is zero-allocation, passes every gate, and is still wrong semantics.

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

Reviewed and endorsed; implemented as written.

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

### Scope: global — recorded as a deliberate simplification, not an oversight

The policy ships **global and unconditional**: every account, every order, always. It is not a
compile-time flag, not a per-account control, and not selectable per order.

**Real venues do not work this way.** STP is normally a per-order attribute the client chooses: the
order carries a self-match-prevention **ID** naming the group its owner's orders share, and an
**instruction** selecting the policy for that order. The widely deployed carrier is **tag 7928**
(`SelfMatchPreventionID`, CME's numbering, mirrored by several other venues) alongside tag 8000 for
the instruction; later FIX versions standardised equivalents. A single-tenant venue with one trading
group does not need any of it, which is why global is defensible here — but it is a simplification,
and naming it now makes the difference a *planned upgrade* rather than a later correction.

**What the upgrade costs, priced now so nobody re-derives it under time pressure:**

| Piece | Change |
|---|---|
| Per-account (or per-group) STP policy | new replicated state — a map the engine reads on every match step |
| Getting it into the state machine | a new sequenced control event alongside `TYPE_ACCOUNT_CONTROL`; it cannot be config, or members diverge |
| Snapshot | a new tuple type **and a format-version bump** (format 3 → 4), because a restored member that does not know the policy enforces a different one |
| Wire | the self-match-prevention id (tag 7928) on `NewOrderSingle`, carried into the engine — `InputEvent` has no free slot for it, so this is the first change here that would genuinely need a wider record |
| Decision | the group compare replaces the `accountId` compare in `cross()` |

Shipping global first is exactly what keeps snapshot format 3 intact today — see *Snapshot impact*.

### What the client sees

An STP cancel is **unsolicited**: the client did not ask for it, and it arrives against an order
they believe is resting. It therefore has to be distinguishable from the two things it could
otherwise be mistaken for — *"my cancel succeeded"* and *"my order was rejected"*. Three fields do
that, and all three are already on the wire:

| Field | STP cancel | Client's own cancel | Rejection |
|---|---|---|---|
| ack `kind` (byte 12) | `KIND_ORDER_CANCELED` (5) | `KIND_ORDER_CANCELED` (5) | `KIND_ORDER_REJECTED` (2) |
| resting-class (byte 21) | **1** — unsolicited, not the response to any input of this session | 0 — the direct response to the client's own cancel | 0 |
| `riskReason` (byte 22) | **`SELF_TRADE_PREVENTED`** | `ACCEPTED` (the order carried no rejection) | the rejecting reason |

So the rule a client implements is exactly: *canceled + resting-class 1 + reason
SELF_TRADE_PREVENTED = the venue prevented a self-trade.* The resting-class byte is not decoration —
it already exists because a counterparty's resting-order update must never complete this session's
offer correlation (FR-LOB07), and an STP cancel is precisely that shape.

The **aggressor** sees nothing special: ordinary fills against the genuine counterparties behind its
own quote, then it rests or is done. That asymmetry is the policy.

Mapped to FIX 4.4, the report is:

```
35=8  ExecutionReport
39=4  OrdStatus     = Canceled
150=4 ExecType      = Canceled
37    OrderID       = ord-<orderRef>       (the cancelled RESTING order, not the aggressor)
58    Text          = "self-trade prevention"
```

FIX 4.4 has no dedicated field for the reason — the self-match-prevention fields (tag 7928 and
friends) are later additions this acceptor does not speak — so `Text(58)` is the honest one here.

**The new enum value is APPENDED to `RiskReason`, never inserted.** The ordinal is persisted in
every snapshot's order rows via `RestingOrder.riskReason`; inserting a value renumbers every ordinal
above it and silently misdecodes every snapshot ever written. `SELF_TRADE_PREVENTED` is also the
first value in that enum that is *not* a pre-trade rejection — it rides a `STATUS_CANCELED` order
rather than a `STATUS_REJECTED` one, which is what makes it distinguishable at all.

### Delivery gap — stated, not papered over

**The engine emits that report; this tier cannot deliver it to the resting order's owner.** Cluster
egress is addressed to the client session that submitted the *input being applied* — the aggressor's
gateway replica — not broadcast. There is no orderRef → FIX-session map, no drop-copy stream, and
the only NATS bridge on the cluster tier is `/trades`. So today an STP cancel reaches the client
**only** when the same gateway replica happened to submit both orders.

This is not specific to STP: **no** resting order gets an unsolicited execution report today, so a
FIX client whose resting order is filled by someone else's aggressor is already not told. STP makes
an existing gap more visible rather than creating one. Closing it is a separate capability — an
egress fan-out keyed by orderRef, which is also what an order read model needs — and it is tracked
as such rather than claimed here.

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
- **Compound case with atomic replace** ([ADR-058](adr-058-atomic-order-replace.md)): a replace's
  new order is unlinked from the book before it crosses, so it is an aggressor. Cancel-oldest only
  ever cancels the *resting* side, so the replaced order can never be STP-cancelled — a client who
  asked only to modify an order can never be left with nothing. It *can* cause its own other resting
  orders to be cancelled, inside the same apply. Ordering is fixed by the code path, not by timing.
- **A rolling member upgrade is a divergence window, and this policy cannot be rolled gradually.**
  Observed live: mid-rollout the StatefulSet ran member 2 on the STP build while members 0 and 1
  were still on the old one. A self-cross applied in that window fills on two members and cancels on
  the third — three state machines, one log, three answers. Nothing in the system prevents it,
  because the policy is a compile-time property rather than replicated state. The operational rule
  is therefore: roll all three members with no self-crossing traffic in flight (or accept the
  window). Note that the *same* sequenced control event that makes STP per-account (see §*Scope*)
  is also what would make it safely rollable — the members would agree because the policy would be
  in the log, not in the binary. That is a second, independent argument for the upgrade.
- The engine counts STP cancels (`traderx_stp_cancels`, per member) so the rate is measurable
  instead of inferred. It is telemetry, not replicated state — like `ordersNew` and `ordersCancel`,
  it generates no identifier and is deliberately not snapshotted.

## Open question for review

Cancel-oldest lets a participant remove their own resting order by aggressing into it. That is a
queue-position vector at multi-tenant venues, and it is the reason decrement-and-cancel exists.
Here it is not a real gain — the participant can simply cancel the order directly, which is now
possible via the cancel ingress. **Flagging it explicitly rather than leaving it implicit**: if
this platform ever becomes multi-tenant, this ADR should be revisited in favour of
decrement-and-cancel.
