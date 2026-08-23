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
