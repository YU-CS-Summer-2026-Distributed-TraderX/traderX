# The cluster rig sequences no live ticks, so the collar follows `/seed`, not the feed

**Raised 2026-08-23** while proving ADR-066 (the price band follows the market). Split out of
`issues/resolved/a-books-price-band-is-anchored-by-its-first-order.md` because it is a deployment
gap, not the engine defect that file closes.

## What was measured

On `kind-traderx-yu12-cluster` the only code path that puts a `TYPE_PRICE_TICK` into the consensus
log is `ClusterGatewayMain.handleSeed` (`POST /seed`). `FeedAdapterMain` (ADR-045, *"the ONLY
market-data path into the deterministic core"*) exists in the YU12 layer and is **not deployed** —
no `feed-adapter` Deployment exists in the namespace. The gateway subscribes to the control feed
only. So the engine's reference for a security is whatever `/seed` last said, and price-publisher's
walk never reaches it:

```
NVDA   publisher 893.74   /seed (fixtures) 200   BUY @893.74 -> PRICE_COLLAR, no re-anchor
```

The collar is doing exactly what ADR-066 says — following the sequenced reference — and the
sequenced reference is stale by construction. `seed-proof-fixtures.sh` seeds the universe at
*live* prices for everything except the three `hold()` crossings (IBM/NVDA/AAPL at 200), which is
why NVDA in particular sits $700 from its published level on every fresh epoch.

## What this is not

Not an engine gap: `bandSlot()` reads `BlpRiskState.lastPrice[]`, which is what the feed adapter
would write. Not a unit-test gap: `LimitOrderBookTest` drives ticks directly. The change to make is
operational — deploy the feed adapter on the rig (and then re-run `yu17-band-follows-market.sh`
with a publisher-priced ticker instead of `/seed`), or accept that on this rig "the market" means
the seeder and say so in the demo notes.

## Also worth knowing

`issues/open/HANDOFF-collar-price-sourcing.md` models the collar reference as the random walk. It
was wrong for the band (which read nothing) and is now half-right: the band follows the feed price
**as sequenced**, which on this rig is `/seed` only. Whoever picks that handoff up should start from
this file and the ADR, not from its own premise.

---

## 2026-08-23: the adapter was deployed, and it is not merely undeployed — it is broken

**Still open, and the reason changed.** The Deployment now exists
(`specs/YU17-otc-rates/generation/kubernetes/cluster/feed-adapter.yaml`), wired into the
kustomization, the members' NetworkPolicy, the bring-up wait list and run-proofs' baseline repin.
It comes up, connects to the cluster and subscribes `pricing.>`. It sequences **nothing**:

```
nats /connz:  feed-adapter  subs ['pricing.>']  out_msgs 2862
traderx_cluster_applied      516 -> 516   on all three members, over 45s
```

`FeedAdapterMain` reads `price` at the top level of the NATS message; price-publisher wraps every
quote in `{topic, payload:{ticker,price,…}, date, from, type}`. `node.get("price")` is null, the
NPE is swallowed by its own `catch (Exception ignore)`, and it has therefore never worked against
this feed. Full account, wire capture and the one-line fix:
**`issues/open/the-feed-adapter-parses-the-wrong-level-of-the-pricing-envelope.md`**.

It is deployed at `replicas: 0` for that reason — a `feed-adapter` pod reading `1/1 Running` while
closing nothing is the failure shape, not the fix.

### What was established anyway, all on the rig

**The engine half works.** Sequencing by hand the one event the adapter would emit closes the loop
end to end on NVDA — seeded at the fixtures' 200, published 899.448:

```
1. /seed NVDA @200 (the fixture price)                   {"seeded":true}
2. BUY NVDA @200                                         orderRef 30  kind=1
3. BUY NVDA @899.448  -- TODAY'S RIG                     orderRef 31  kind=2  PRICE_COLLAR
4. one PRICE_TICK @899.448 (what the adapter emits)      {"seeded":true}
5. BUY NVDA @899.448  -- WITH A FEED                     orderRef 32  kind=1   <-- accepted
6. BUY NVDA @1200     falsification arm                  orderRef 33  kind=2  PRICE_COLLAR
```

Three members agreed on `applied` (604), `traderx_band_reanchors` (3) and `traderx_book_order_hash`
throughout. Step 3 vs step 5 is the whole claim: the *only* difference is one sequenced tick. Step 6
is the discriminating arm — the collar still collars, so step 5 is not "the band gave up".

**The flush interval is measured, not guessed.** See the block in `feed-adapter.yaml`: 4,770 real
`pricing.>` messages over 299s replayed through the adapter's own conflation gives 57,448 log
entries/hour at the 50ms default versus 16,367/hour at 15s, for a reference no more than $9.87
stale — 15% of the collar's ±$65.50 half-width. `FEED_FLUSH_MS=15000` is set on that basis.

**The fixtures break the moment ticks flow**, deterministically rather than flakily:
`issues/open/a-live-feed-refuses-the-fixture-seeders-nvda-crossing.md`.

### Not proven

The suite has **not** been run with a live feed, because there is no way to make the feed live
without the parse fix and a rebuilt image. Whether `run-proofs.sh` is stable with ticks flowing is
open; the NVDA fixture finding above says at least three proofs are not, and that is a prediction
verified by hand, not a suite observation.
