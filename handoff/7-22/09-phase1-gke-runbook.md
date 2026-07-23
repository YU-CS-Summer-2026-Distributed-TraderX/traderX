# 09 — Phase 1 GKE runbook: saturate the per-order path, read the funnel

> Campaign: "find the real per-order ceiling." Phase 0 (isolation-proven generator) is done and
> committed. This is the GKE ladder. Quota is live: global `CPUS_ALL_REGIONS`=64, regional `CPUS`=200,
> `N2_CPUS`=200. Sizing: 3 c4d members (24 vCPU) + ~5 n2 load nodes (40) = **64, the real ceiling.**
>
> Infra/GCP commands below are **hand-over** (yaakov runs them; Claude prepares + interprets). The
> source edits and the two rigs are already done and committed on `YU13-limit-order-book`.

## Already done (committed, no push)

| commit | what |
|---|---|
| `463f1f30` | `BinGen.java` + `BinEcho.java` + `run-bin-isolation.sh` — Phase 0 generator, isolation-proven 2.19M/s |
| `98003aec` | `BinGen` START_AT_MS barrier + `run-bin-blast-gke.sh` — distributed JDK-container ladder runner |
| `1f86f1c7` | **pin gke `cluster-node:yu13 → :yu13-idempfix`** (pre-flight item 1 — without this a clean apply deploys the degrading pre-fix engine) |
| `67aba634` | runner sends `PRICE=150` (seeded price) + `QTY` so the two-account flow crosses |

## What the deployed manifests already give you (no change needed)

- **Members** (`statefulset-emptydir.yaml`): `nodeSelector: blp-c4d-tuned-pool`, **required** pod
  anti-affinity on hostname ⇒ one member per node = **dedicated node per member, CPU measured clean**.
  Guaranteed QoS, cpu request==limit==3. Pool is tainted `workload=blp:NoSchedule` so nothing else lands.
- **Gateways** (`gateway.yaml`): 4 replicas, **required** one-per-node anti-affinity, **no node pin** →
  they land on any non-member untainted pool.
- `kubectl apply -k .` from the gke dir also runs `seed-accounts-job` (seeds 42422/22214 + the other
  bench accounts at **price 150**) and `backup-cronjob`.

## Two things the manifests DON'T give you (must set for the ladder)

1. **`BINARY_ACCEPTOR_PORT=18140` on the gateways** — the binary acceptor is opt-in and is NOT in the
   manifest (the prior lane removed it at teardown). Without it BinGen hits `:18140` → connection
   refused → 0 connections. Set it as a live env (bench-only; don't commit it to source).
2. **`TRADE_BRIDGE_NATS_URL` UNSET on the members** — it's currently set to `nats://nats-broker:4222`.
   Unsetting it nulls both the trade bridge and Brief 07's new-order tap ⇒ egress adds zero overhead,
   you measure pure ingress (Phase 1 requirement #3).

---

## Pre-flight (hand-over)

```bash
# context + quota sanity
kubectl config current-context                      # must be the GKE context, not kind
gcloud compute project-info describe --project traderx-501015 \
  --format="value(quotas.filter('metric=CPUS_ALL_REGIONS').limit)"   # expect 64

# 1. bring the pools up.  Members: blp-c4d-tuned-pool = 3 nodes.  Load: an n2 pool = ~5 nodes.
#    Keep default-pool/std-pool at 0 so the 4 gateways + generators land on n2 by elimination
#    (gateways have no node pin; only c4d[tainted] + n2 are up ⇒ they pick n2).
gcloud container clusters resize traderx-lmax --node-pool blp-c4d-tuned-pool \
  --num-nodes 3 --zone us-east1-b --project traderx-501015 --quiet
#    Create the n2 pool if it doesn't exist (n2-standard-8 × 5 = 40 vCPU):
gcloud container node-pools create n2-load-pool --cluster traderx-lmax \
  --machine-type n2-standard-8 --num-nodes 5 --zone us-east1-b --project traderx-501015 --quiet
#    (if it already exists: gcloud container clusters resize traderx-lmax --node-pool n2-load-pool --num-nodes 5 ...)
kubectl get nodes -L cloud.google.com/gke-nodepool    # confirm 3 c4d + 5 n2, note the n2 pool label
```

## Bring-up (hand-over)

```bash
cd specs/YU13-limit-order-book/generation/kubernetes/cluster/gke
kubectl apply -k .                                    # members + gateways + seed job + backup cronjob
kubectl -n traderx rollout status statefulset/order-matcher-cluster --timeout=300s
kubectl -n traderx rollout status deploy/cluster-gateway --timeout=300s

# PRE-FLIGHT ITEM 1 VERIFY — the running members must report the idempfix digest, not :yu13:
kubectl -n traderx get pods -l app=order-matcher-cluster \
  -o custom-columns=POD:.metadata.name,IMAGE:.spec.containers[0].image,DIGEST:.status.containerStatuses[0].imageID
#   IMAGE must end :yu13-idempfix on all three. If any says :yu13, you're on the degrading engine — stop.

# requirement #3 — unset the trade bridge on members (pure ingress); one roll:
kubectl -n traderx set env statefulset/order-matcher-cluster TRADE_BRIDGE_NATS_URL-
kubectl -n traderx rollout status statefulset/order-matcher-cluster --timeout=300s
kubectl -n traderx exec order-matcher-cluster-0 -- printenv TRADE_BRIDGE_NATS_URL || echo "unset ✓ (expected)"

# turn ON the binary acceptor (bench-only, not committed):
kubectl -n traderx set env deploy/cluster-gateway BINARY_ACCEPTOR_PORT=18140
kubectl -n traderx rollout status deploy/cluster-gateway --timeout=300s

# suspend the backup cronjob so it doesn't perturb a run:
kubectl -n traderx patch cronjob order-matcher-journal-backup -p '{"spec":{"suspend":true}}' 2>/dev/null || \
  kubectl -n traderx get cronjob   # find the real name and suspend it
```

## Lift the risk caps for the bench window (hand-over — the classifier blocks Claude from weakening prod risk)

A >100k/s × 30s rung is up to 3M orders; at qty 10 the two-account crossing drives the BUY account's
position past `RISK_MAX_POSITION_QUANTITY` (1,000,000) in ~1s and every later order **risk-rejects**.
`nextOrderRef` still advances on a reject (so the ground-truth ingress number stays valid), but you'd
be measuring the reject path, not accept+match. Lift the caps so the load shape stays consistent, and
**rotate accounts across rungs** (seeded: 42422 11413 22214 44044 52355 10031 62654) since BLP position
accumulates for the pod's lifetime.

```bash
GW=$(kubectl -n traderx get pod -l app=cluster-gateway -o jsonpath='{.items[0].status.podIP}')
# raise position + concentration via the policy endpoint (version must exceed current; null keeps a cap):
kubectl -n traderx exec order-matcher-cluster-0 -- curl -s -XPOST localhost:18110/risk/control/policy \
  -H 'x-risk-control-token: dev-risk-control' -H 'content-type: application/json' \
  -d '{"version":<current+1>,"maxPositionQuantity":100000000000,"maxConcentrationNotionalTicks":5000000000000000000}'
# credit limit is an env — bench pods only:
kubectl -n traderx set env statefulset/order-matcher-cluster RISK_CREDIT_LIMIT_TICKS=5000000000000000000
kubectl -n traderx rollout status statefulset/order-matcher-cluster --timeout=300s
```

## The ladder (hand-over — the runner is the committed rig)

Set `GEN_NODESELECTOR` to the n2 pool label from `kubectl get nodes -L ...` above. Each rung raises
`TOTAL` (aggregate offered/s) and prints the funnel. Keep a price refresher alive the whole time
(`risk.price.max-age-ms`=30s or every order rejects `PRICE_STALE`):

```bash
# price refresher (background, whole ladder) — reuses the seed path to republish the 150 tick:
while true; do
  kubectl -n traderx exec order-matcher-cluster-0 -- curl -s -XPOST localhost:18110/seed \
    -H 'content-type: application/json' -d '{"accountId":42422,"tickers":"JPM","price":150}' >/dev/null
  sleep 8
done &

cd <repo root of this worktree>
export GEN_NODESELECTOR='cloud.google.com/gke-nodepool=n2-load-pool'   # <-- your real n2 label
for TOTAL in 20000 40000 80000 160000 320000; do
  PODS=4 SESSIONS_PER_POD=250 TOTAL=$TOTAL SECS=30 bash scripts/bench/run-bin-blast-gke.sh
done
# then the absolute-ceiling push:
MODE=blast PODS=4 SESSIONS_PER_POD=250 SECS=30 bash scripts/bench/run-bin-blast-gke.sh
```

## Reading the funnel (Claude interprets the pasted output)

Each run prints:
```
offered (generator)         = N/s
gateway decoded / offer-succ = M/s   (n/a if the idempfix image lacks the diagnostic counters)
member nextOrderRef delta    = C/s   <-- GROUND TRUTH (committed/s). NEVER the gateway 'accepted'
                                          counter — it's booked-only and has lied (330 vs 1,750/s)
client backpressure: write-stalls   (climbing = a real limit, not a harness one)
```

**The binding hop is the first one that reads lower than the one before it, AND where backpressure
appears** (write-stalls climb, in-flight balloons, or offer-success < decoded). Until then you're still
coasting. The knee + backpressure selects the next lever from Brief 08's table — do **not** pre-commit
to partitioning; it's only justified if a genuine consensus wall (members pegged, apply-lag growing)
is what binds. Also watch member CPU during the top rungs:
`kubectl -n traderx top pod -l app=order-matcher-cluster`.

## Hand to the Brief 07 chat, THEN tear down

When the ladder is done, leave the cluster **up** and hand it to the Brief 07 session for its live
effect-end proof (they flip `TRADE_BRIDGE_NATS_URL` back on for a low-volume NEW→CANCEL pass). Only
after they're done:

```bash
# restore risk caps (tightening — Claude may run this):
kubectl -n traderx exec order-matcher-cluster-0 -- curl -s -XPOST localhost:18110/risk/control/policy \
  -H 'x-risk-control-token: dev-risk-control' -H 'content-type: application/json' \
  -d '{"version":<current+1>,"maxPositionQuantity":1000000,"maxConcentrationNotionalTicks":5000000000000000000}'
kubectl -n traderx delete job binary-load --ignore-not-found   # if KEEP_JOB was set
# scale every pool to 0 and VERIFY zero nodes:
for pool in blp-c4d-tuned-pool n2-load-pool default-pool std-pool batch-private-pool; do
  gcloud container clusters resize traderx-lmax --node-pool "$pool" --num-nodes 0 \
    --zone us-east1-b --project traderx-501015 --quiet
done
kubectl get nodes    # must be empty before claiming teardown complete
kubectl -n traderx get cronjob   # confirm backup cronjob still suspended
```

## Honesty rules (this may feed a talk)

- A ceiling is real only with **backpressure at the binding hop** AND a generator proven to over-offer
  (Phase 0: 2.19M/s isolation) — anything else is a harness number.
- Ground truth is member `nextOrderRef` delta, never the gateway `accepted` counter.
- Load shape on every row: fresh keys (RUN_ID differs per invocation), two-account crossing, PODS,
  SESSIONS_PER_POD, TOTAL. Per-order and batch (438k) are different contracts — never blended.
- If the middle funnel hops read `n/a`, the deployed idempfix image lacks the decoded/offer counters;
  the offered → nextOrderRef two-point funnel + CPU + write-stalls still localizes the knee. The
  diagnostic-counter image `:yu13-binary-ceiling-r1` adds them but its push was never authorized.
```
