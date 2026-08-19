# The algo engine never registers child-order fills on the cluster tier

**Filed** 2026-08-19 by the coordinator, from the UI lane's observation, verified independently.
**RESOLVED 2026-08-19** (see the resolution at the foot). Was YU08-ownable. Not urgent — the demo narrates fills from the blotter — but it makes every
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

## THREE VICTIMS AND COUNTING, 2026-08-19 — this is a CLASS, not one component's bug

The composed `web-front-end-angular` blotter was found broken by the **same gap, independently**: the
tier publishes on bare `/orders` and keys the order `id`, so the blotter's guard discarded **every**
live order update. It was fixed in the new
`specs/YU17-otc-rates/generation/runtime-overrides/web-front-end/` layer.

**A THIRD was found within the hour**, after that question was asked: the same UI's **admin/oversight
page** subscribed to the raw `/orders` feed and dropped every update on the identical `id`/`orderId`
mismatch. Three consumers, all written against the single-BLP dialect, all silently deaf on the cluster
tier, none failing loudly.

**So the question for any fix is not "does the algo engine work now" but "who else subscribes to order
updates".** Two were found by accident and the third only because the question was asked out loud —
which is direct evidence that accident is not a search strategy. Before closing this issue, grep the
whole tree for consumers of that subject family rather than trusting the count here.

Note also that the same UI carried a *third* symptom of the same tier move: `POST /orders/{id}/cancel`
fell through to the gateway's NEW-ORDER handler and **booked an order instead of cancelling one** (the
gateway's own comment at `ClusterGatewayMain.java:332-336` records having measured exactly that). Fixed
in the same round. Different mechanism, same root cause: **order flow moved to the gateway and the
things that talk to it were never re-pointed.**

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


---

# RESOLVED 2026-08-19 — engine-side consumption fixed, proven on the cluster rig

## The diagnosis above was right and INCOMPLETE: there were THREE mismatches on that payload line

The subject and the epoch-qualified id are both real and both fixed. The third one was found while
implementing and would have left the engine deaf after fixing the other two:

3. **Field-name mismatch.** The cluster bridge names the id field **`id`**
   (`OrderNatsPublisher.encode`, and trade-processor's `OrderUpdate` model, whose own javadoc says
   "`id` is epoch-qualified (`epoch-orderRef`)"). The engine read **`orderId`**, which is the
   single-BLP `OrderResponse` field. So even on the right subject, `handle()` returned at
   `if (!body.hasNonNull("orderId")) return;` before it ever reached the id join.

**And the subject fix is not a swap.** Bare `/orders` is published on **both** tiers, not just the
cluster one: `NatsBridgeHandler` publishes each update twice on the single-BLP tier — once on
`/accounts/<id>/orders` and once on `ALL_ORDERS_TOPIC = "/orders"` (asserted by that tier's own
`OutputDisruptorHandlersTest`: `List.of("/accounts/22214/orders", "/orders")`). The filter now
accepts any subject ending `/orders`, so the engine works on both tiers rather than being re-pointed
from one to the other. The single-BLP tier's double delivery is harmless: every downstream mutation
is a full field replacement rather than an increment, and a completed parent is not re-completed.

## The fix

`specs/YU08-execution-algo-engine/generation/runtime-overrides/execution-algo-engine/`, two files:

- **`fills/OrderUpdateSubscriber.java`** — subject filter widened to `endsWith("/orders")`; id read
  from `orderId` **or** `id`; epoch prefix stripped on comparison; and the bridge's `0.000000`
  rendering of "no execution yet" (`Px.NONE`) mapped back to null, so a resting child is not
  reported as having executed at zero.
- **`service/AlgoOrderService.java`** — javadoc, plus a **bound on `pendingUpdates`**. That stash
  was unbounded, and the subject now carries every order in the venue rather than one account
  family, so every unrelated order was a miss stashed and never claimed. It is a `LinkedHashMap`
  capped at 512 with insertion-order eviction; the race it closes is one HTTP round trip wide.

**Epoch handling: normalised ON COMPARISON, not stored at submission — deliberately.** The engine
never learns the epoch: `CLUSTER_EPOCH` is a member-side env and the gateway's `POST /orders`
response carries only the bare `orderRef`. Storing the qualified form would mean plumbing a second
copy of the cluster's epoch into the engine and keeping the two in lockstep, and the next epoch bump
that missed that copy would break this join again exactly as the subject did. Stripping on
comparison is epoch-agnostic by construction — the engine records no epoch, so there is nothing to
keep in step.

The pattern is `\d+-(\d+)`, anchored that narrowly on purpose. The single-BLP scheme is
`ord-013-%04d` (`OrderSnapshot.orderIdFor`), which carries hyphens of its own and must survive
untouched — a looser rule would fix the cluster tier by breaking the tier that already worked. The
**cost** of not recording the epoch: two children with the same `orderRef` in different incarnations
collapse to one key. That needs a parent to outlive an epoch bump, which wipes the rig's state, and
parents live minutes. If algo parents ever have to survive an incarnation, the fix is for the
gateway to return the qualified id, not for the engine to guess.

## Proof

**Unit** — `OrderUpdateSubscriberTest` 2 → 10 tests, suite 29 → 37. Each element of the fix was
detonated by applying its exact inverse and watching precisely the right tests fail: the subject
widening (2 fail), the `id` field (4), the epoch strip (4), the zero-price guard (1). The other 29
stayed green with each defect in, which is what disproves "some existing test would have caught it".
The negative control (`clusterFillForSomeoneElsesOrderFillsNothing`) was detonated separately with a
correlation that matches anything, and fails alone.

**Rig** — `kind-traderx-yu12-cluster`, members on `traderx/cluster-node:yu17-ackB`, `CLUSTER_EPOCH`
unset so published ids are `1-<orderRef>`. Same script, one variable (the engine image):

| arm | engine image | fixture check | parent | engine says | orderbook says |
|---|---|---|---|---|---|
| before | `execution-algo-engine:yu15` | probe buy **FILLED** at 163.31 | `cddc6bc9` stays **RUNNING** | children 2560/2561 `filled:false`, remaining `null`, price `null` | `1-2560`/`1-2561` **FILLED**, remaining 0, 163.31 |
| after | `execution-algo-engine:yu17-fills` | probe buy **FILLED** at 167.16 | `42e82cee` **COMPLETED** | children 2572/2573 `filled:true`, remaining 0, **167.16** | `1-2572`/`1-2573` **FILLED**, remaining 0, **167.16** |

The fixture check is the load-bearing part of the before arm: it fills an aggressive probe buy at
the same book **before** the TWAP runs, so a RUNNING parent cannot be the refuted "slices rested
below the touch" case. That check passed on both arms.

**Negative control on the rig:** ten pre-existing parents' filled-bucket counts were recorded before
the run and re-read after — none moved, while `1-2572`/`1-2573` fills flowed past every one of them
on the shared subject. The proof asserts on the **delta**, not on "zero filled", because a parent an
earlier run legitimately completed would otherwise fail the control.

Proof script kept out of tree at `scratchpad/algo-fill-feedback.sh` (session-local). Its exit code
was observed at 0 on the passing run and 1 on two distinct failure paths.

## What this did NOT do

- **The parents already on the rig stay RUNNING.** Their fills were broadcast on core NATS hours
  ago and are gone; nothing replays them. They are fully submitted, so they do not resubmit or
  flood. One more was added by this work's own before-arm (`cddc6bc9`, GOOGL) and is the same shape.
- **`issue_algo_parent_retries_forever` is untouched.** A permanently *rejected* parent never
  reaches this code path at all — its buckets are never marked submitted, which is the retry loop.
  Fill feedback does not bear on it.
- **Only the YU17 kind manifest was re-pointed** at `traderx/execution-algo-engine:yu17-fills`, the
  first build of this service since `:yu15`. YU15's and YU16's own cluster layers still declare
  `:yu15` because no fixed image has been built for those tiers — their source carries the fix,
  their images do not.

## Carry

The file is carried by **ten** branches, not the five a first reading suggests — found by hash sweep
rather than by walking down from the tip. `OrderUpdateSubscriber.java`, `AlgoOrderService.java`,
`OrderUpdateSubscriberTest.java` and `contracts/contract-delta.md` were byte-identical on YU08, YU09,
YU10, YU11, YU12, YU13, YU14, YU15, YU16 and YU17 before the change and are byte-identical after.
`main` also carries them and was **excluded**: it is a PR target, not a propagation target.
