# ADR-089: A market order reserves at the worse of the mark and the opposite best

Status: accepted 2026-10-28 (yaakov); code only. This is a deterministic-core change, so it ships only in a roll
of its own (§4) and NOT in rollout 4. Requirement: YU13 FR-LOB04 (amended for YU18). Amends the
reservation half of YU13 [ADR-051](../../YU13-limit-order-book/system/adr-051-last-trade-price-output.md):
the mark keeps its meaning, but it is no longer the market order's reference on its own.

## 1. Context

ADR-051 made the mark (the last trade price, or a tick-seeded mark before the first print) the reference
a market order is validated and reserved at, falling back to the opposite best only when there is no mark.
Proof-audit suspect 11 confirmed the engine does exactly that, and it surfaced the cost. With a tick at
$150.00 and the only ask at $150.20, a market Buy of 100 is sized at $15,000.00, passes an order-notional
cap of $15,010.00, and fills at $15,020.00. The gate approved an order that executes above its own limit,
and the credit reservation understates the fill by the gap between the mark and the book. The gap is
unbounded: a stale tick, or a thin book far from the last print, makes it as large as the band.

## 2. Decision

`MatchingEngine.marketValidationPx` returns:

| Side | Mark and opposite best | Only one | Neither |
|---|---|---|---|
| Buy | `max(mark, best ask)` | that one | `Px.NONE` → PRICE_MISSING |
| Sell | `min(mark, best bid)` | that one | `Px.NONE` → PRICE_MISSING |

The reference is the side's worse execution price of the two, i.e. its expected fill at the top of the book.
Every market-order reservation routes through this one function:

- `MARKET` on the legacy admission path (`onNewOrder`) and on the typed path (`admitImmediate`, including IOC);
- a fired `STOP` / `TRAILING_STOP`, which FR-OT38 re-decides as a market order (`fireTrigger`).

The risk gate is unchanged. `BlpRiskState.decideAndReserve` sizes `quantity × validationPrice × multiplier`
from the price it is handed, so listed options (YU14's multiplier, carried in the YU18 `BlpRiskState`
override) and bonds (a fraction of par, YU16 ADR-057) take the same rule with no second site. The trade ticket
(`TYPE_TRADE_NEW`) is not a market order on the book: it books at the mark and walks no levels, so it keeps
the mark.

## 3. Consequences

- A market Buy into asks above the mark is now sized at the best ask. Where that crosses a cap it is refused
  (ORDER_NOTIONAL, CREDIT_LIMIT, CONCENTRATION_LIMIT) instead of being filled above it.
- **A market Sell is sized LOWER than before** when the best bid is below the mark, because the reference is its
  expected fill. `reservedNotional` is gross notional on both sides, so such a Sell now reserves less credit,
  and passes a notional cap that the mark would have refused. This is the decided rule, stated here so that
  nobody reads it as an accident.
- The reference is the top of the book only. A market order larger than the best level still walks deeper
  levels at prices it was not sized at (as FR-OT16 already records for stops). Sizing the whole walk is a
  separate decision.
- No snapshot, wire, ack or egress format change. `lastPxBySecurity`, `marketPx` and the ADR-051 mark are
  unchanged.

## 4. Rolling it

The function runs inside the replicated apply path. Members on the old and new code reach different verdicts
for the same committed market order, so a mixed-version window diverges the cluster **permanently** (same
applied sequence, different books; see the engine-roll snapshot-barrier issue). The same is true of replay:
the recon's shadow replay of a log written under the old rule, run by the new code, re-decides those orders.
Roll it in one of two ways only:

1. **Snapshot barrier, all members switched together.** Take a snapshot that `traderx_cluster_snapshots`
   shows advanced on all three members; stop every member; start all three on the new image so they restore
   that snapshot and replay only the tail written after it. The recon's full-history replay of the pre-roll
   log is not authoritative for market-order verdicts after this roll.
2. **A fresh epoch** (wipe to a new `CLUSTER_EPOCH`, with the read model), which has no old log to disagree with.

Never one member at a time.

## 5. Evidence

`MarketOrderReservationTest` (YU18 layer, `lmax`) on an empty-root render:

- `buyAgainstAnAskAboveTheMarkIsSizedAtTheAsk`, `sellAgainstABidBelowTheMarkIsSizedAtTheBid`: both fail on the
  ADR-051 engine. The Buy is accepted, fills the 50 resting at $150.20 and cancels the rest, where the rule
  refuses it ORDER_NOTIONAL. The Sell is refused ORDER_NOTIONAL, where the rule fills it at $149.80.
- `emptyOppositeSideStillUsesTheMark` (both sides) and `anAskBelowTheMarkLeavesTheBuyAtTheMark`: controls
  that pass on both engines.
- Each case first asserts that its opposite order really rests. The first draft rested 100 at $150.20, over
  the same cap, so the ask was refused and the Buy case measured the mark-only fallback on both engines: it
  "failed before the fix" and failed after it, for a reason that had nothing to do with the rule.
