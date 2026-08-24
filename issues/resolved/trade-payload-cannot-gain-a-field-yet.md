# The trade payload cannot gain a field, and `sourceOrderId` is the field it needs

**Raised 2026-08-21** while costing a request to populate `sourceOrderId` on trades, so the console
can link a trade to its order's trace. **Fixed 2026-08-21** — both steps landed in the stated order
and the field is live on the cloud rig. See *Resolution* below; the ordering argument is the part
worth keeping, because the next payload extension faces the same trap.

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

## Resolution

Both steps shipped, in the order above. Verified on the cloud rig 2026-08-21: every trade the engine
books carries `"sourceOrderId":"<epoch>-<orderRef>"` (e.g. `1-4`), zero null, and the projection did
not drop a single trade — the blotter, positions and read model stayed populated throughout.

**It did NOT make the trace link work by itself, and the note that said it would was wrong.**
`sourceOrderId` is a trade→ORDER link. Trace ids are derived from the CLIENT ORDER ID, which the
engine never sees and no trade payload carries, so no field on a trade can reach a trace on its own.
What closed the gap was a console-side join: the console generates the client order id for orders it
submits and already keeps `orderRef` + `traceId` together in its activity log, so a trade reaches its
trace as `sourceOrderId` → order ref → this page's own activity entry. That is a join across state
the console happens to hold, not a derivation — a trade from FIX, from another browser, or from
before this page loaded has nothing to join to, and the console says so rather than guessing a hash.

Two consequences worth knowing before anyone reads a 404 as a regression:

- The link is **per page load**. The activity log is in memory; a refresh drops every join.
- A joined id **usually 404s on GKE anyway**, by design. GKE sets `OTEL_SAMPLE_MASK=127` (1 in 128)
  where the kind manifests set `0` (trace everything). Measured: nine consecutive accepted orders all
  404 while a rejected order's trace answered 200 within 15s from the same page and stayed available
  for 90s. Rejections escalate past the mask; accepted orders — the only ones that become trades — do
  not. So on the cloud rig expect roughly 1 trade in 128 to resolve, and on kind, all of them.

## Related

The console's `traceIdFor` order-ref fallback was found the same day to derive ids the gateway never
produces — 404s that read as head sampling. It has been changed to return undefined.

Related: [[an-epoch-roll-silently-drops-instrument-classes]]
