# Two orders sharing a ClOrdID share one trace id, and the trace cannot tell them apart

**Filed 2026-08-21**, measured on the GKE bench rig while proving out the read-model trace id
(`bfc3ace8`, "orders: persist the trace id on the read-model row").

**Status: BY DESIGN — do not "fix" it.** This follows necessarily from the property that makes
distributed tracing work across the consensus boundary at all. It is filed because it will be read
as a bug the first time someone meets it, not because it is one.

## What happens

Submit two orders with the same `clientOrderId`. They get different order refs and different rows in
the `orderbook` projection — and **the same trace id**. Opening either row's trace shows the spans of
both orders, interleaved, with nothing distinguishing which span belongs to which order.

Measured, two `POST /orders` both carrying ClOrdID `TRACE-REJ-0821`:

```
1-66  17017  REJECTED  limitprice 5000.000000  traceid 8787023ec4036ea7f2b8b487dca9e692
1-67  17017  REJECTED  limitprice    0.010000  traceid 8787023ec4036ea7f2b8b487dca9e692
```

`GET /tempo/api/traces/8787023ec4036ea7f2b8b487dca9e692` returns **10 spans** — two complete
five-span traces (`gateway.queue`, `cluster.consensus`, `order` from `traderx-cluster-gateway`;
`cluster.commit`, `cluster.apply` from `traderx-cluster-member`) sharing one trace id. A single order
returns 5, verified alongside on `1-60`.

## Why it is necessary, and why it must not be "fixed"

The trace id is a **pure function of the client idempotency key** — the gateway hashes the ClOrdID
(`ClusterGatewayMain.clientOrderKey`, FNV-1a) and both tiers run the same derivation
(`OrderTrace.keyOf` → `traceIdHi`/`traceIdLo`). That purity is the whole design, and
`OrderTrace`'s own header states the constraint it exists to satisfy: a trace must span gateway →
sequence → consensus commit → apply → egress, across processes joined only by the replicated log,
with **zero bytes added to the log**. Putting a `traceparent` in the sequenced message is the one
thing the design may not do — it would be a schema change, a member roll, and a permanent
determinism risk taken on behalf of a debugging feature.

So the only way to give two same-ClOrdID orders distinct trace ids is to mix in something that
distinguishes them — a counter, a timestamp, the assigned `orderRef`. Every candidate is either
non-deterministic across members, or not yet known when the derivation happens. The gateway sets
`p.traceKey` at `ClusterGatewayMain` ~1040, **before** `offerPipelined` and before the ack it then
waits on, so it can record a start time for a span it may have to emit retroactively; the `p.orderRef`
it passes is still the pre-submission value (0 for a NEW). The member deliberately mirrors that,
deriving *before* the sequenced generator assigns the ref — the comment at
`MatchingEngineClusteredService` ~537 says so outright: at that instant the decoded event holds
exactly the field values the gateway held. The agreement is built on both sides reading identical
pre-assignment state.

To be exact rather than absolute: this is not a theorem, it is a consequence of that key selection.
Spans are emitted on both sides *after* the committed ack, by which point both know the ref, so a
ref-mixing variant is not obviously impossible. It is just not free — it would move the member's
derivation past the generator, re-open the question of what the two sides agree on and when, change
which orders `sampled()` selects, and touch the log-line correlation path
(`OrderTrace.traceIdHex` at `ClusterGatewayMain` ~829). Nobody has costed that, and the merge below
is coherent enough that nobody has needed to. **Treat it as settled unless someone brings a reason,
not as a defect waiting for a volunteer.** What is genuinely ruled out is the naive fix: any
uniquifier that has to be *carried* through the log, which is the thing the design refuses.

The merge is also coherent rather than merely tolerable: `clientOrderId` **is** the idempotency key.
Two orders sharing one are, by the engine's own definition, claims about the same client intent, so a
shared trace is arguably the honest rendering. (In the case above both orders were rejected and each
still received its own ref and its own row. How duplicate suppression behaves for *accepted* reused
keys is a separate question this measurement did not characterise — do not read the two rows above as
evidence about it either way.)

## The demo-facing framing

**Reuse a ClOrdID and you merge two orders into one trace.** Say it that way, because the symptom
presents backwards: it looks like the tracing has duplicated spans, or joined unrelated orders, or
lost track of which order it is describing. It has done none of those. It is showing every span that
was emitted under the id the client's own key derives.

Consequences worth knowing before someone hits them live:

- **Load generators and scripts are the likely source.** Anything that sends a constant or templated
  ClOrdID gives every order it ever sends the same trace id — one trace accumulating spans without
  bound. The REST path defaults to `""` (no key, no trace) rather than a constant precisely because a
  shared default would also make every keyless order a duplicate of the first; a caller supplying its
  own constant re-creates the problem the default avoids.
- **The console does not and need not de-duplicate.** A trace opened from an order row may legitimately
  contain more than one order. The row → trace direction is always right; the trace → order direction
  is one-to-many whenever a key was reused.

## How it surfaces in the console (so nobody builds a second mitigation)

The console **names it rather than hiding it** — `99d035bb`: when a trace holds spans from more than
one order it heads the panel *"This trace covers 2 orders — refs 72, 73"* and adds an order column,
both suppressed in the ordinary single-order case. It reads the `traderx.order_ref` attribute that
every span already carries (measured off the live payload, not assumed), so the separation is
detectable rather than merely possible. Verified in the panel on `1-72`/`1-73`: five spans each,
73 starting 86.8 ms after 72.

Worth knowing that this case was **unreachable from the UI until `c8f30fd3`**. Rejected orders do
land in the projection, but `GET /accounts/{id}/orders` returns only `OPEN_STATUSES` (NEW,
PARTIALLY_FILLED) unless asked with `?status=all`, and the blotter fetched without it — so the rows
that most often carry a reused ClOrdID (rejects from a script) had no way to appear. That commit adds
an opt-in `all states` toggle; it is deliberately **off** by default, because "Open orders" has to go
on meaning open orders.

**Roll state goes stale faster than this document, so check it rather than reading it here** — the
first version of this paragraph said both commits were unrolled and was wrong within the hour. Each
of the two is independently detectable in the served bundle, with a marker introduced by that commit
and no other (`git log -S` confirms both):

| commit | marker in the bundle |
|---|---|
| `99d035bb` (names a multi-order trace) | `This trace covers` |
| `c8f30fd3` (`all states` toggle) | `status=all` |

Read the bundle name out of a cache-busted `/` and grep it. Take markers from the diff you are
testing for, at the moment you test: an earlier commit's string can be revised away by a later one in
the same roll, and then a correct build reads as a failed one.

Note the shape of the near-miss, since it is the same one this project keeps paying for: the absence
of REJECTED rows from the open list was read as "rejections do not persist", and the reading was
taken as a property of the system rather than of the query. Two sessions measured correctly and
disagreed for an hour because they were asking different questions of the same table.
- **This is not the wrong-id-space error** the same commit's `FLAG_RESTING_UPDATE` rule guards
  against. There, a row would carry a *different* order's id — a wrong answer that resolves and reads
  as convincing. Here every row carries the id its own key derives; the id is simply not unique
  because the key was not.

## What to do instead

Use a unique `clientOrderId` per order — a ULID, a monotonic suffix, anything per-submission. That is
already what the idempotency contract asks for; this just makes the cost of ignoring it visible in the
trace viewer as well as in duplicate suppression.
