# ADR-053: Multiplier-aware notional arithmetic in the risk gate

## Status

Accepted (YU14-listed-equity-options)

## Context

Every notional the risk gate computes — order reservation, market-trade execution, executed
exposure on fill, and the concentration projection — was quantity x price. For an option that
understates economic exposure by the contract multiplier: a $2.50 premium on a 100-multiplier
contract controls $250 of notional. The credit limit, order-notional cap, and concentration cap
would all admit ~100x the intended exposure.

## Decision

`BlpRiskState` holds a dense per-security `contractMultiplier` array (default 1 when never set),
written on the cold registration/bootstrap paths and read once per decision:

- `decideAndReserve` and `decideMarketTrade` compute
  `notional = quantity x validationPrice x multiplier` inside the existing overflow-checked
  `multiplyExact` chain; overflow rejects ORDER_NOTIONAL exactly as before.
- `consume` accumulates executed exposure as `fillQty x execPrice x multiplier` with the
  inherited saturate-on-overflow behavior.
- The concentration projection multiplies the projected quantity by the multiplier before the
  price comparison; the product is bounded by construction (projected quantity is bounded by
  position/order caps, multiplier by the registered set), so no new overflow path opens.
- Reservations, releases, and reaccumulation are unchanged: they operate on the stored
  already-multiplied notional, so partial-fill proration and snapshot order rows carry the
  multiplier's effect with no format change.

## Consequences

- The caps fire at the contract's economic exposure. The behavioral acceptance is symmetric: an
  order rejected on a multiplier-100 security is accepted verbatim on a multiplier-1 security.
- The decision path cost is one array read and one multiply; the zero-allocation and
  determinism disciplines are untouched.
- Equity behavior is bit-identical to YU13: multiplier 1 leaves every product unchanged.
