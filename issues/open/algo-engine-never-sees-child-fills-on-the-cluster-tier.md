# The algo engine never registers child-order fills on the cluster tier

**Filed** 2026-08-19 by the coordinator, from the UI lane's observation, verified independently.
**Open.** YU08-ownable. Not urgent — the demo narrates fills from the blotter — but it makes every
TWAP parent immortal.

## The symptom, measured

Nine children across three parents. **The book says filled; the engine says not.**

| child | book status | remaining | lastExecutionPrice | engine `filled` | engine `remainingQuantity` |
|---|---|---|---|---|---|
| `1-2536` | FILLED | 0 | 182.16 | **false** | **null** |
| `1-2537` | FILLED | 0 | 182.16 | **false** | **null** |
| `1-2538` | FILLED | 0 | 181.98 | **false** | **null** |
| `1-2541` | FILLED | 0 | 194.04 | **false** | **null** |
| `1-2542` | FILLED | 0 | 193.40 | **false** | **null** |
| `1-2543` | FILLED | 0 | 192.45 | **false** | **null** |
| `1-2545` | FILLED | 0 | 196.20 | **false** | **null** |
| `1-2546` | FILLED | 0 | 196.20 | **false** | **null** |
| `1-2547` | FILLED | 0 | 196.20 | **false** | **null** |

Consequence: **every parent stays `RUNNING` forever**, even when fully executed. Five parents were in
that state on the rig at filing time.

## NOT to be confused with the case that was refuted

An earlier report of the same *shape* was checked and **refuted** on 2026-08-19: parent `c0f5dd91`'s
children rested unfilled because their limits (182.84/183.86/183.05) were below the touch (200.00), so
`filled:false` was simply *true*. **That is not this.** The nine rows above are executions that
demonstrably occurred, at prices recorded in both the read model and the kdb capture. Check the book
before assuming a repeat of the refuted case.

## Mechanism — RESOLVED 2026-08-19 by the discriminator. Two mismatches, both engine-side.

The hypothesis this file first carried — "nothing publishes the subject for gateway-submitted orders" —
was **REFUTED**. A NATS watcher on `>` during a live child fill (parent `8e7e7c9f`, child `2549`) saw
the leader-side bridge publish the child's complete lifecycle: `status:NEW, remainingQuantity:10`
followed by `status:FILLED, lastExecutionPrice:196.20, lastFillQuantity:10`, plus the counterparty's
`PARTIALLY_FILLED`. **The feedback channel exists and works on this tier.** The engine speaks the old
tier's dialect on two axes:

**1. Subject mismatch.** The bridge publishes on bare **`/orders`** —
`MatchingEngineClusteredService.java:206` constructs `new OrderNatsPublisher(bridgeUrl, "/orders", …)`.
The engine filters for **`/accounts/{id}/orders`** (`OrderUpdateSubscriber.java:36-38`), a subject form
that **never occurs on this tier**. The `/accounts/{id}/trades|positions` family does exist —
trade-processor publishes those — which is exactly what makes the wrong filter look reasonable.

**2. Identifier mismatch.** The bridge's ids are **epoch-qualified** (`1-2549`); the engine stores its
child as a **bare orderRef** (`2549` — that is what `GET /algo/orders` reports as `childOrderId`). So
**fixing the subject alone will not fix the defect** — the correlation join must handle the epoch
prefix too. Two bugs on one payload line.

**Why the original hypothesis was plausible and wrong, worth keeping:** the gateway image does carry
`/accounts/` references, but `ClusterGatewayMain.java:1774` shows them to be a REST *call*
(`base + "/accounts/" + accountId + "/orders"`), not a NATS publish. An in-image grep could not tell
those apart; only watching the bus could.

**The fix is engine-side consumption — subject filter and id join. No new publisher is needed.**

## Why it is YU08's and not the tier's

YU08 was built on the single-BLP tier, where orders flowed through trade-processor's REST controller
and the broadcast was a side effect of the path everything took. The cluster tier moved order flow to
the gateway and the algo engine followed it — but the *feedback* channel was never re-pointed. This is
the same class as the reference-data membership defect: a component behaving exactly as designed, in a
topology its design predates.

## Adjacent, and worth reading together

`issue_algo_parent_retries_forever` — a permanently-rejected parent resubmits every tick with no
give-up. Both defects are the parent lifecycle depending on feedback that does not arrive. A fix that
restores fill feedback may or may not address the retry case; check, do not assume.

## Provenance

Engine state via `GET /algo/orders` on `execution-algo-engine:18120`; book state via the `orderbook`
table. Both read on 2026-08-19 with five parents live on the rig.
