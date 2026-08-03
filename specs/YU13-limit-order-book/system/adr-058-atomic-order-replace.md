# ADR-058: Atomic order replace

## Status

**Accepted and implemented** (2026-07-22). `InputEvent.TYPE_ORDER_REPLACE` (11) →
`MatchingEngine.onReplace`, with REST `POST /replace` and FIX `OrderCancelReplaceRequest` (`G`)
ingress on the cluster gateway.

## Context

An earlier brief said *"model replace as cancel + add — not a shortcut."* That is right about
**priority** and wrong about **atomicity**, and the two were conflated.

Priority: real venues only preserve queue position when the quantity strictly decreases at an
unchanged price. Anything else — a reprice, a size-up — goes to the back of its level. That is
genuinely "cancel and add" and it is correct.

Atomicity is a different question, and modelling replace as two *sequenced* operations gets it
wrong. Two sequenced commands leave a committed window between them in which the client's order does
not exist:

| Order of the two | Failure it admits |
|---|---|
| cancel, then add | a rejected add (risk gate, price collar, bad quantity) leaves the client with **nothing** — they asked to modify an order and lost it. It also answers ONE request with TWO lifecycle messages, a cancel confirm plus a reject, which most client state machines mishandle. |
| add, then cancel | no total loss, but exposure is briefly **doubled** — and the risk gate sees the doubled figure, so a legitimate reprice can be rejected on credit the account is in the middle of giving back. An immediately marketable new order can also fill *while the old one is still live*, filling both. |

The window is not a race in the usual sense: the state machine is single-threaded and deterministic,
so it is a *committed* window, reproduced identically on every member and on every replay. Being
deterministic makes it worse, not better — it is reliably wrong.

## Decision

**One sequenced command, `TYPE_ORDER_REPLACE`, performing cancel-and-add inside a single apply.**

There is no point between the two halves at which any observer — a member, a replay, or a client —
can be scheduled, so the window does not exist. The command either commits in full or changes
nothing.

### It needs no SBE template, and that was checked across the whole lineage

`AeronReplicationCodec` copies `commandType` through without interpreting it, so a new `InputEvent`
type rides the existing `InputEventMessage` (template 1) exactly as `TYPE_ORDER_CANCEL` does.

This matters beyond convenience. Template ids **1–8 are allocated across the lineage**, and 8 is
YU15's `RiskExtractMessage` — which a YU13 worktree **cannot see**, because it holds no YU15 spec
directory. "Add a template at 8, following the pattern" is a collision that would stay invisible
until the change reached YU15. Checking the allocated range across the lineage before claiming an id
is now the rule; not needing an id at all is better.

Payload, on the established type-discriminated slots:

| Slot | Meaning for REPLACE |
|---|---|
| `orderRef` | the order being replaced (the same slot CANCEL uses) |
| `qty` | the new **total** quantity |
| `limitPx` | the new limit price |
| `priceTicks` | the `clientOrderKey` of the replace request (idempotency) |
| `accountId`, `securityId`, `side` | **not carried** — FIX forbids changing them, so the engine reads them off the original order, which is also the only copy the log can prove |

### The order keeps its orderRef

FIX 4.4 permits a replaced order to retain its `OrderID(37)`, and keeping it is the strongest form
of the atomicity this command exists for: the client's order identity is never even momentarily
absent. It also means a replace consumes **no value from the orderRef generator**, so
`nextOrderRef` stays exactly what the snapshot header says it is, and the "generator strictly
exceeds every ID ever issued" load invariant is untouched.

The gateway's FIX `ClOrdID → orderRef` map gains an entry for the *new* ClOrdID pointing at the same
ref, so a follow-up cancel by `OrigClOrdID` resolves.

### Everything that can fail is evaluated before anything is mutated

In order: unknown ref → not-found; terminal order → reject; quantity at or below what already
executed, an absent limit price, or a price off the grid or outside the band → reject; then the risk
decision. (A replace always carries a limit price. `limitPx <= Px.NONE` is rejected rather than read
as "make it a market order": a market order never rests, so there would be nothing left to replace.) Only after
all of those does the book change. A rejected replace therefore leaves the original order
**bit-identical**, including its risk reservation.

The reservation needs care. The new order is decided *after* releasing the old one's reservation,
because evaluating it on top of the old one double-counts the account's exposure and would reject an
ordinary size-**down** for credit it is in the middle of returning. On rejection the reservation is
restored exactly: `release` zeroed both the account aggregates and the per-order holder, so putting
the same two numbers back through `reaccumulateReservation` reproduces the prior state precisely.

### Queue priority

| Case | Priority |
|---|---|
| strict size-**down** at an unchanged price | **kept** — the order never leaves its level; only the level aggregate shrinks |
| everything else (reprice, size-up, size unchanged) | **lost** — unlinked and re-appended at the tail of the (possibly new) level |

The kept case is the only one where nothing about the order becomes more aggressive, which is why
venues allow it and why it is safe here: it cannot become marketable, so it does not re-cross.

### Replace × self-trade prevention — the compound case

Neither this ADR's first draft nor [ADR-057](adr-057-self-trade-prevention.md) covered what happens
when a replace's new order is itself self-marketable against the participant's *other* resting
orders. Inside one apply, both the cancel-and-add and the STP cancel-oldest can fire.

**Decided: the replaced order is always the aggressor, and cancel-oldest only ever cancels the
resting side. So the replaced order can never be STP-cancelled.**

This is not a rule bolted on; it falls out of the code path and is therefore not something a future
edit can silently violate without also breaking matching:

1. `onReplace` unlinks the order from the book **before** calling `cross()`. It is not resting, so
   `book.headAt` can never return it — it cannot meet itself.
2. `cross()` cancels `r`, the resting order, never `a`, the aggressor.

Consequences, stated plainly:

- A client who asked only to **modify** an order can never be left with **nothing** by STP.
- A replace **can** cancel that client's *other* resting orders, in the same apply, unsolicited.
  They are distinguishable: `KIND_ORDER_CANCELED` + resting-class 1 + `SELF_TRADE_PREVENTED`.
- Ordering is fixed by the code path, not by timing: the replace commits, then matching runs, then
  STP acts within matching. Identical on every member and on replay.
- In the size-down-keeps-priority case the order never leaves the book and never crosses, so no STP
  interaction is possible at all.

## Consequences

- **No snapshot change and no format bump.** Replace mutates only fields already in the order tuple
  (quantity, remaining, limitPx, status, updatedAt, and the reservation pair). Snapshot format
  stays 3.
- **Determinism**: the decision reads only replicated state and event-carried time; no clock, no map
  iteration. Every member and every replay reaches the same result from the same log position.
- **Zero allocation**: no allocation on the path. The replace branch is driven inside the measured
  window of `AllocationGateTest`, not merely argued to be free.
- **Idempotency comes for free.** The replace's `clientOrderKey` goes through the same replicated
  `BlpRiskState` table as a new order, so a resent replace returns the original decision instead of
  applying twice.
- **A replace of an already-terminal order is a reject, not a republish.** Cancel republishes a
  terminal order unchanged (009 parity), but doing that here would emit `KIND_ORDER_FILLED` for an
  order that was already done — indistinguishable from a replace that crossed and filled on the way
  in. The gateway would report the first as a success. A reject is unambiguous, and the order is
  untouched either way.
- **`ExecutionReport` on the FIX side is `ExecType=5`**, with `OrderCancelReject` carrying
  `CxlRejResponseTo=2` (`ORDER_CANCEL_REPLACE_REQUEST`) on failure — a replace reject carrying
  `ORDER_CANCEL_REQUEST` would tell the counterparty their *cancel* failed.
- **The known limitation is the effect end, not the command.** There is no order read model: the
  `orderbook` table holds 0 rows for every order ever, because the cluster tier bridges only
  `/trades`. So a replace can be asserted against member book state and the trades table, and not
  against SQL order state. That gap is named rather than papered over.

## Alternatives rejected

- **Two sequenced commands** (cancel + add) — the window above. This is the option the earlier brief
  implied and it is the one thing this ADR exists to rule out.
- **A new SBE template** — unnecessary (the codec is command-type agnostic) and actively risky from a
  single worktree that cannot see the whole lineage's allocated ids.
- **Minting a new orderRef for the replacement** — needs a free field the 64-byte record does not
  have, advances the generator, and re-introduces an identity discontinuity in the one command whose
  entire purpose is to remove one.
