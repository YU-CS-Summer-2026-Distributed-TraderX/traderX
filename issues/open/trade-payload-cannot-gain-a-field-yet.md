# The trade payload cannot gain a field, and `sourceOrderId` is the field it needs

**Raised 2026-08-21** while costing a request to populate `sourceOrderId` on trades, so the console
can link a trade to its order's trace.

## What is wanted, and why it looks trivial

`Trade` **already declares** `sourceOrderId` (YU16 layer, with getter and setter). It is simply never
populated: the leader-side bridge offers `(tradeSeq, accountId, ticker, side, qty, px)` and no order
ref, so the field arrives null on every trade. The console names it as the missing link — a trade
carries no way back to the order that caused it, and therefore no way to derive that order's trace id.

Adding it reads like a one-line producer change.

## Why it is not

**`Trade` carries no `@JsonIgnoreProperties(ignoreUnknown = true)`** — checked across both layers that
define it (YU05 and YU16). Its siblings all do: `PriceTick`, `OrderUpdate`, `InstrumentMetadata`.

`PriceTick`'s own comment records what that annotation is worth, and it is the most expensive lesson
in this repo:

> the shared `NatsJSONSubscriber` ObjectMapper is left at Jackson's default
> `FAIL_ON_UNKNOWN_PROPERTIES=true`, so a payload that GAINS a field is not additive for this
> consumer — it is fatal. […] every one of them was rejected and DROPPED: 10,812 deserialization
> failures, no UST rows in the EOD closing snapshot, and YU06's fail-safe then halted every account
> holding a bond. Nothing logged the ticker; the only visible symptom was three halted accounts in a
> proof two states away.

So adding `orderRef` to the trade payload today would drop **every trade** at the consumer. Orders
would be accepted, the engine would book them, all three members would agree, and the blotter,
positions and read model would go silent — the exact signature of the wedge, from a different cause.

## The ordering is the whole fix

Two changes, and shipping them in the wrong order is an outage:

1. **First**, add `@JsonIgnoreProperties(ignoreUnknown = true)` to `Trade` and deploy trade-processor
   alone. Harmless by itself; the payload has not changed.
2. **Then** carry the order ref on the bridge and populate `sourceOrderId`.

Reversed, step 2 breaks the projection until step 1 lands. This is not a rollback-safe pair either:
once step 2 is out, rolling trade-processor *back* re-breaks it.

**Do step 1 regardless of whether anyone ever wants `sourceOrderId`.** `Trade` is the only model on
this path still intolerant of an added field, and it is a latent trap for whoever next extends the
payload for an unrelated reason — which is precisely how the 10,812 drops happened.

## Related

The console's `traceIdFor` order-ref fallback was found the same day to derive ids the gateway never
produces — 404s that read as head sampling. It has been changed to return undefined, so once
`sourceOrderId` is populated the trace link starts working with no further console change.

Related: [[an-epoch-roll-silently-drops-instrument-classes]]
