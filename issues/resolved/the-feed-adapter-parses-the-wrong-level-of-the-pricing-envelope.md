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

---

## 2026-08-24: FIXED, deployed, and proven under the full suite — plus the second defect the fix exposed

**The parse is fixed** (commit `a7ec2c8b`): `parsePriceTicks()` reads `payload.price` with a bare
top-level `price` fallback, extracted as a static so `FeedAdapterParseTest` can drive it with the
wire-captured envelopes. The test fails exactly the two envelope cases against the old read and
passes the malformed ones — discriminating. Drops are now counted, the first unparseable message is
printed with its bytes, and a `FEED received= dropped= sequenced= symbols=` line prints per minute.

**Fixing the parse exposed a SECOND lifelong defect.** The adapter never sends a session keepalive
and slept the whole `FEED_FLUSH_MS` between polls. The consensus module expires an idle session
(the gateway's own keepalive comment records the identical finding), after which `offer()` answers
CLOSED forever and `register()`'s bare `while (offer < 0)` spun silently — measured as one status
line then nothing, `received=0`, while NATS had delivered 2,556 messages. At the 50ms default this
was masked (every flush was traffic); at the measured-and-chosen 15s it was fatal. Fixed in the same
commit: egress poll + keepalive every 1s, flush on the interval, and a lost session throws out of
`main` so the kubelet restarts a clean connect instead of leaving a wedge reading `1/1 Running`.

**Deployed and measured** on `traderx/cluster-node:yu17-tick` (= `:yu17-band` + FeedAdapterMain
only), `replicas: 1`, all four cluster-node workloads on the one tag, manifests declaring it:

```
FEED received=2074 dropped=0 sequenced=531 symbols=68 pendingRegistrations=0
```

68 instruments registered through sequenced `SymbolRegisterMessage`, ~264 ticks/min sequenced —
within 4% of the capture-replay prediction for `FEED_FLUSH_MS=15000`. Three members agree on
`applied` throughout.

**The full suite ran twice with ticks flowing.** Run 2: **25 passed / 2 skipped / 2 failed**, and
no failure was price-shaped — every one traced to disk-crash fallout (service images pruned off
kind nodes; a stale stp boundary pair; NATS's lost control stream). The seeder now crosses
NVDA/AAPL/IBM at live prices (`hold NVDA 25 @ 945.25` where 200 was refused), and
`yu06-consumer-halt`, `yu05-recon` and `yu15-risk-extract` all passed on the holdings that
crossing produces.

Remains open here: nothing about the adapter. Residuals live in their own files —
the seeder finding is resolved by the same commit; the yu04 skip is NATS crash damage
(outbox republish blocked by the permission classifier; command handed to the coordinator).

---

## RESOLVED — fixed in `a7ec2c8b`, live on the rig 2026-08-23

`FeedAdapterMain` reads `payload.price` (not the top-level `price` that never existed), keeps the
consensus session alive across flush intervals, and **counts what it drops** instead of swallowing
malformed ticks in a bare `catch`. `FeedAdapterParseTest` covers the envelope shape.

Measured on the live rig, with the FRED-backed publisher upstream:

```
FEED received=39384 dropped=0 sequenced=11082 symbols=69 pendingRegistrations=0
```

Zero drops across 39,384 ticks, all 69 instruments registered, and `applied` advances continuously
on all three members. The counter is what makes this checkable at a glance — the old code could not
have told you the difference between "nothing to do" and "discarding everything", which is precisely
how it stayed broken from the day it was written.

### Residual, filed separately rather than left in here

The adapter **fail-fasts on a failed cluster connect and does not recover from backoff**. That is
correct on a cold start, but it means any event that briefly makes the members' pod DNS unresolvable
— notably a proof-suite run, which rolls the cluster — leaves the adapter in CrashLoopBackOff long
after the cluster is healthy again. Observed 2026-08-23 at 8 restarts with DNS and all three
endpoints verified good; `kubectl delete pod` fixed it immediately. See
`issues/open/the-feed-adapter-does-not-come-back-after-the-cluster-rolls.md`.
