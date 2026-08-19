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

## Mechanism — one part measured, one part hypothesis. The distinction matters.

**Measured:**

- `OrderUpdateSubscriber` subscribes to NATS **match-all `>`** and filters client-side for
  `/accounts/{id}/orders` (`OrderUpdateSubscriber.java:36-38`); it logged `subscribed to >` at startup,
  so the subscription itself is alive.
- The known publisher of that subject is **trade-processor's `OrderController`** (YU13 layer) — i.e.
  the REST order path.
- The engine posts children to **`ORDER_MATCHER_URL=http://order-matcher:18110`** — the cluster
  gateway — which **bypasses that controller entirely**.

**Hypothesis, NOT proven:** nothing publishes `/accounts/{id}/orders` for gateway-submitted orders, so
the subscriber is alive and correct and simply never hears anything. This is plausible and unverified —
the gateway does carry `/accounts/` references (4 classes) and there is a leader-side `/orders` egress
mirroring the trade bridge, so a publish path may exist and be failing for a different reason
(payload shape, correlation key, or a strict-consumer drop — see
`feedback_additive_payload_strict_consumer`, where a typed subscriber silently dropped 10,812 messages).

**The decisive next step is cheap:** attach a NATS client to `>` and watch while a child fills. Either
the subject never appears (publish gap) or it appears and the engine ignores it (parse/correlation
gap). Those need completely different fixes; do not start coding before knowing which.

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
