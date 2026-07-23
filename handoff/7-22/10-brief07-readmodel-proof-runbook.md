# 10 — Brief 07 read-model live effect-end proof (GKE, dedicated pass)

> The live headline for Brief 07: order `NEW → CANCEL` with the effect asserted at the SQL read-model
> row via `GET /accounts/{id}/orders`, on GKE. Runs AFTER 08's throughput ladder (teardown is gated on
> this pass — runbook 09 §"Hand to the Brief 07 chat, THEN tear down").
>
> **Why this is more than "flip the env"** (the gap 09 line 151 didn't see): the deployed members run
> `cluster-node:yu13-idempfix`, which PREDATES `OrderNatsPublisher` — flipping `TRADE_BRIDGE_NATS_URL`
> on them publishes nothing to `/orders`. And 08's cluster kustomization is pure-ingress: no
> trade-processor, no database. So this pass stands up the read-model half from scratch: my member tip
> image + MariaDB (orderbook schema) + trade-processor (my consumer + REST).
>
> Infra (kubectl/gcloud) is **hand-over** — yaakov runs it, Claude drives the curls + interprets. The
> two images are already built + pushed (below).

## Ready in GAR (built + pushed this session)

| image | digest | contains |
|---|---|---|
| `us-east1-docker.pkg.dev/traderx-501015/traderx/cluster-node:yu13-readmodel` | `sha256:31603510…` | member tip: `OrderNatsPublisher` + the `drainOutputs` order tap (superset of `yu13-idempfix`; internal SBE schema `blp-replication.xml` UNCHANGED, so peer-auth checksum matches — a fresh all-`readmodel` cluster is internally consistent) |
| `us-east1-docker.pkg.dev/traderx-501015/traderx/trade-processor:yu13-readmodel` | `sha256:588f8fac…` | `OrderFeedHandler` (`/orders` → `orderbook` upsert) + `OrderController` (`GET /accounts/{id}/orders`) |

Proof overlay (self-contained, reuses the rendered app manifests): `handoff/7-22/readmodel-proof/`
(MariaDB pvc+deploy+svc+init-configmap with the orderbook schema, trade-processor deploy+svc on the tip
image, dev secrets). Validated with `kubectl kustomize`. Endpoints are OPEN (no spring-security, JWT
filter not wired) — the proof is a plain curl, no token.

## Preconditions

- 08's ladder is done; cluster is UP (`order-matcher-cluster-{0,1,2}`, `cluster-gateway`, `nats-broker`).
- A fresh epoch is fine — this pass does not need 08's accumulated book. emptyDir members ⇒ scale cycle wipes.

## Step 1 — members onto the tip image, fresh epoch, bridge ON (hand-over)

```bash
NS=traderx
TIP=us-east1-docker.pkg.dev/traderx-501015/traderx/cluster-node:yu13-readmodel

# fresh epoch + tip image + bridge on, all before scale-up so members boot in the target state:
kubectl -n $NS scale statefulset/order-matcher-cluster --replicas=0
kubectl -n $NS rollout status statefulset/order-matcher-cluster --timeout=120s   # waits for 0
kubectl -n $NS set image statefulset/order-matcher-cluster cluster-node=$TIP
kubectl -n $NS set env statefulset/order-matcher-cluster TRADE_BRIDGE_NATS_URL=nats://nats-broker:4222
#   CLUSTER_EPOCH defaults to "1" in code — no need to set it. Set it (any string) only to prove a
#   second incarnation doesn't collide; the default is correct for a single-epoch proof.
kubectl -n $NS scale statefulset/order-matcher-cluster --replicas=3
kubectl -n $NS rollout status statefulset/order-matcher-cluster --timeout=300s

# members must report the tip image on all three:
kubectl -n $NS get pods -l app=order-matcher-cluster \
  -o custom-columns=POD:.metadata.name,IMAGE:.spec.containers[0].image
# the gateway (cluster-gateway) may stay on yu13-idempfix — the egress ack layout and ingress SBE are
# unchanged by the read-model commits, so it is wire-compatible. Swap it too only if you want a
# single-image cluster: kubectl -n $NS set image deploy/cluster-gateway cluster-node=$TIP
```

## Step 2 — stand up the read-model half (hand-over)

```bash
kubectl apply -k handoff/7-22/readmodel-proof/
kubectl -n traderx rollout status deploy/database --timeout=180s
kubectl -n traderx rollout status deploy/trade-processor --timeout=180s
# trade-processor logs should show it subscribed to /orders (no errors, NATS connected):
kubectl -n traderx logs deploy/trade-processor | grep -iE 'orderFeed|/orders|nats|started' | tail -5
```

## Step 3 — enable the account + security in the cluster risk state (hand-over)

The DB ships the account rows (initialSchema seeds 22214 etc.), but the CLUSTER must have the
account+ticker enabled or the order risk-rejects. Reuse the gateway `/seed`:

```bash
GW=$(kubectl -n traderx get pod -l app=cluster-gateway -o jsonpath='{.items[0].status.podIP}')
kubectl -n traderx exec order-matcher-cluster-0 -- curl -s -XPOST localhost:18110/seed \
  -H 'content-type: application/json' -d '{"accountId":22214,"tickers":"AAPL","price":150}'
```

## Step 4 — the proof (Claude drives; read-only exec/port-forward)

Open a port-forward to the read model (leave running):
```bash
kubectl -n traderx port-forward svc/trade-processor 18091:18091 &
```

**Falsifiable arm first (break it, show the real failure).** With the read model up but BEFORE any
order flows, the blotter is empty — and it stays empty for an order submitted while the bridge is OFF,
proving the read model is driven ONLY by the tap, not pre-populated:
```bash
curl -s localhost:18091/accounts/22214/orders            # expect: []   (empty)
# turn the tap OFF, submit an order, show it does NOT land in the read model:
kubectl -n traderx set env statefulset/order-matcher-cluster TRADE_BRIDGE_NATS_URL-
kubectl -n traderx rollout status statefulset/order-matcher-cluster --timeout=300s   # (re-seed after, step 3)
#   ... submit a NEW order (below) ... then:
curl -s localhost:18091/accounts/22214/orders            # expect: []   <-- the real failure: no tap, no row
# turn it back ON and continue with the pass:
kubectl -n traderx set env statefulset/order-matcher-cluster TRADE_BRIDGE_NATS_URL=nats://nats-broker:4222
kubectl -n traderx rollout status statefulset/order-matcher-cluster --timeout=300s   # (re-seed, step 3)
```

**The pass (bridge ON):**
```bash
GW pod exec, submit a resting BUY:
kubectl -n traderx exec order-matcher-cluster-0 -- curl -s -XPOST localhost:18110/orders \
  -H 'content-type: application/json' \
  -d '{"accountId":22214,"security":"AAPL","side":"Buy","quantity":100,"limitPrice":150}'
#   → response carries the assigned orderRef R. (Also readable from the member nextOrderRef.)

# 1) NEW is enumerable as an open order for the account:
curl -s localhost:18091/accounts/22214/orders            # expect one row, id "1-R", status NEW
# 2) cancel it:
kubectl -n traderx exec order-matcher-cluster-0 -- curl -s -XPOST localhost:18110/cancel \
  -H 'content-type: application/json' -d '{"orderRef":R}'    # expect 200 canceled
# 3) the effect-end — gone from open, CANCELED in the full list:
curl -s localhost:18091/accounts/22214/orders            # expect: []   (gone from the blotter)
curl -s 'localhost:18091/accounts/22214/orders?status=all'  # expect id "1-R", status CANCELED
```

Pass criteria: NEW appears with epoch-qualified id `1-R`; after cancel it is absent from the open list
and present-and-`CANCELED` in `?status=all`; the falsifiable arm produced an empty read model with the
tap off. That is the SQL effect-end order-level proofs never had.

## Step 5 — hand back for teardown

This pass is the last gate before 09's teardown. When done:
```bash
kubectl delete -k handoff/7-22/readmodel-proof/          # remove the read-model stack
kubectl -n traderx delete pvc database-data --ignore-not-found
# then resume runbook 09 teardown: restore risk caps (Claude may run the tightening curl),
# scale every pool to 0, verify zero nodes, confirm the backup cronjob stays suspended.
```

## Notes / honesty

- Fresh epoch, `CLUSTER_EPOCH=1` (default). Order ids are `1-<orderRef>`; a second incarnation would be
  `2-<orderRef>` and not collide — settable via the env if you want to demonstrate that too.
- This proves the read-model half live. The tap CONTENT (epoch id, status mapping, Px) and the
  consumer mapping + rejection signal are already unit/integration/falsifiably proven in the suite; this
  pass adds the live cluster→NATS→DB→REST plumbing that only GKE can show.
- The proof overlay is scaffolding (dev secrets, fresh DB) — NOT committed spec manifests. Promoting a
  real read-model deployment to the committed GKE manifest set is a separate follow-up.
