# ADR-063: The risk gate gets a swap path, because the rate is not a price

**Status**: Accepted
**State**: YU17-otc-rates
**Date**: 2026-08-13

## Context

`BlpRiskState` admits every order and market trade through one ordered pipeline with a stable
rejection precedence, and the quantity it measures against the notional cap, the credit line and
the concentration limit is:

```
notional = quantity × validationPrice × multiplier
```

For a swap, `quantity` is the notional and `validationPrice` would be the fixed rate. That product
is 10,000,000 × 0.042 × 1 = **420,000** for a 10mm swap: an understatement of roughly 24x that
produces no error, no log line and no anomalous-looking number anywhere.

Four further checks in the same pipeline read state a swap does not have. Security enabled,
security restricted, price present and price freshness all index by `securityId` — which for a swap
booking carries a convention-table index, not a symbol id, so each would test an unrelated number
and refuse every booking. Position limit and concentration limit project `(account, security)`
quantity forward, which is the grain a swap does not have at all.

## Decision

Add `BlpRiskState.decideSwapBooking(clientOrderKey, principalKey, accountId, notionalTicks,
contractRef)`: the same ordered pipeline and the same stable precedence, measuring the **notional
directly** and running only the checks that are about the account rather than the instrument —
duplicate suppression, kill switch, account existence, account enablement, entitlement, a positive
notional, the per-booking notional cap, and credit.

An accepted booking's notional accrues immediately to the account's executed exposure. A swap is not
a resting order, so there is no reservation to release later.

## Consequences

**A bypass would have been worse than the wrong formula.** A swap admitted with no notional measured
consumes no credit at all, so the account's exposure is understated by the entire notional rather
than by 24x. The swap path is a dozen lines and gets the number right.

**Dropping the position and concentration limits is the load-bearing part.** Both are projections of
`(account, security)` quantity. Applying them to swaps would net a receive-fixed leg against a
pay-fixed leg to zero exposure *inside the admission decision* — reintroducing, in the gate, the
exact netting error this state exists to demonstrate the cost of. They are not omitted for
convenience; applying them would be wrong.

**The notional is carried in Px ticks** (whole units × 1e6) so it is measured against the same
`maxOrderNotionalTicks` and the same credit line as every other instrument. Nothing about the limits
is swap-specific, which keeps one account exposure number meaningful across asset classes.

**No new `RiskReason`.** Every outcome the swap path can produce already has a value —
`ACCEPTED`, `KILL_SWITCH`, `UNKNOWN_ACCOUNT`, `ACCOUNT_DISABLED`, `NOT_ENTITLED`, `INVALID`,
`ORDER_NOTIONAL`, `CREDIT_LIMIT`, `CAPACITY`. That matters beyond tidiness: `RiskReason` ordinals are
serialized into every snapshot's order rows, so adding a value in the wrong position misdecodes every
snapshot ever written.

**Capacity is checked before the gate, not inside it.** The contract store is bounded, and a booking
refused for capacity must not first accrue its notional into the account's credit usage for a
contract that will not exist. Capacity is a pure function of replicated state, so checking it first
is deterministic on every member.

**The limits are, in this configuration, effectively unbounded.** `CREDIT_LIMIT_TICKS` and
`MAX_ORDER_NOTIONAL_TICKS` are both `Long.MAX_VALUE / 4`, so the shipped rig admits any
representable notional. The gate is nonetheless wired and provable: the account checks refuse a real
booking, and the accrual moves by the notional rather than by quantity × rate, which is what the
test asserts rather than asserting a rejection the configuration cannot produce.

**What is not modelled.** There is no tenor-weighted, DV01-weighted or currency-aware limit. Each of
those requires a valuation, and valuation is deliberately the consumer's half of the boundary
(ADR-064). Recorded as TD-OTC02.
