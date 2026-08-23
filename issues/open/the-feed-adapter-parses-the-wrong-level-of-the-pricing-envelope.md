# The feed adapter parses the wrong level of the pricing envelope, so it sequences nothing

**Raised 2026-08-23** while deploying `FeedAdapterMain` to close
`issues/open/the-cluster-rig-sequences-no-live-ticks.md`. That file said the adapter was merely
undeployed. It is undeployed **and** incompatible with the feed it is specified to consume, and the
second half is why deploying it changes nothing.

## What was measured

`feed-adapter` was deployed on `kind-traderx-yu12-cluster` on `traderx/cluster-node:yu17-band`
(same image as the members, `command:` pointing at `finos.traderx.ordermatcher.cluster.FeedAdapterMain`,
the risk-extract shape). It came up clean and did nothing:

```
FEED ADAPTER up: nats=nats://nats:4222 flushMs=15000     # the only two log lines it ever printed
nats /connz:  10.244.3.203  subs ['pricing.>']  out_msgs 2862
traderx_cluster_applied{member="0"} 516  ->  516         # over 45s, all three members
```

2,862 NATS messages delivered to it, **zero** ticks sequenced, no `SYMBOL` registration lines. Not a
NetworkPolicy problem (fixed separately, see below), not a cluster-connect problem (`AeronCluster.connect`
returned before the "up" line), not backpressure (the process idled at ~2% CPU).

**The cause is in `FeedAdapterMain`'s NATS handler**
(`specs/YU12-aeron-cluster/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/FeedAdapterMain.java`):

```java
final JsonNode node = JSON.readTree(message.getData());
final double price = node.get("price").asDouble();      // <- null at the top level
latestTicks.put(ticker, Math.round(price * 1_000_000d));
} catch (final Exception ignore) { }                     // <- and this swallows the NPE
```

price-publisher wraps every quote in the house envelope (`publishTick()` in
`price-publisher/src/main.js`). Captured off the wire, not read off the source:

```
pricing.DFS {"topic":"pricing.DFS","payload":{"ticker":"DFS","price":127.16,"openPrice":126.4,
             "closePrice":127.65,"asOf":"2026-08-23T17:14:30.178Z","source":"snapshot"},
             "date":"...","from":"price-publisher","type":"PriceTick"}
```

The price is at **`payload.price`**. `node.get("price")` is null, `.asDouble()` throws NPE, and the
adapter's own `catch (Exception ignore)` — there to tolerate a malformed tick — swallows every tick
the feed has ever sent it. The one-line fix is to read `payload.price` (falling back to top-level
`price`, so a raw quote still works).

**The envelope is the house convention, and this is the outlier.** `PriceTickHandler` extends
`NatsJSONSubscriber<PriceTick>`, which unwraps `Envelope` and binds the payload; the Angular
front-end does the same. Note the comment in
`.../trade-processor/src/main/java/finos/traderx/tradeprocessor/model/PriceTick.java`:

> the other two `pricing.*` consumers (the feed adapter and PricingNatsSubscriberService) already
> read the tree rather than binding a type, so they were never affected

That is a statement about a component nobody had ever run. It reads the tree at the wrong level.

## Why it went unnoticed for five states

ADR-045 calls the adapter *"the ONLY market-data path into the deterministic core"*, and nothing
read the sequenced feed price until ADR-066 (2026-08-23) made the collar band follow it. Before that
a working adapter and a broken one were indistinguishable from every observable on the tier. There
is no test: `LimitOrderBookTest` drives ticks directly, and no test exercises the NATS handler.

**Its failure mode is silent by construction** — up, connected, subscribed, quiet. A `feed-adapter`
pod reading `1/1 Running` is exactly the vacuous shape this tier keeps paying for, which is why the
Deployment this issue ships alongside is pinned at `replicas: 0` with the reason written into it.

## What was landed anyway (all of it verified except the adapter itself)

- `specs/YU17-otc-rates/generation/kubernetes/cluster/feed-adapter.yaml` — the Deployment, correct
  env, `replicas: 0`. Verified as far as it can be: it schedules, starts, launches its media driver,
  connects to the cluster and subscribes the right NATS subject.
- `networkpolicy.yaml` — `app: feed-adapter` added to the members' UDP 21800-22200 ingress
  allowlist. **This was needed and is not optional**: without it `AeronCluster.connect` blocks for
  its full 60s timeout with nothing saying a policy dropped the packets.
- `kustomization.yaml`, `scripts/yu15/start-cluster-kind.sh` (rollout wait),
  `scripts/yu15/run-proofs.sh` (baseline repin — it is the third Deployment running the cluster-node
  image, and the file already states the rule about checking all of them).

## To finish this

1. Fix the parse (one line) and build a cluster-node image carrying it. **A rebuild was deliberately
   not done here**: the task that raised this was scoped to deployment and wiring on the explicit
   premise that no build was needed, and that premise is what turned out to be false.
2. `kubectl scale deploy/feed-adapter --replicas=1`, then assert `traderx_cluster_applied` advances
   on all three members with no `POST /seed` in play.
3. Before that lands, read
   `issues/open/a-live-feed-refuses-the-fixture-seeders-nvda-crossing.md` — the fixtures break
   deterministically the moment ticks flow, and it is a two-line fix in the seeder.
