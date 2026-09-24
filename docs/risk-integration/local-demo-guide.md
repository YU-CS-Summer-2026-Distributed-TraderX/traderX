# Local demo and acceptance guide (RI-07)

Maintained command guide for the full local rig: trading tier, console, algo engine and
observability on your own disposable kind cluster. Last verified 2026-09-24 18:45Z on macOS arm64
(Docker Desktop, 11 CPU / 10 GB) from `claude/demo-acceptance` at `e96bf28a` (integration
`9931b3e5` plus RI-07), with no local patches.
Component contract: [demo-acceptance](../../specs/YU18-risk-integration/components/demo-acceptance/README.md).

**Labels used below.** *Live*: exercised on the kind rig. *Synthetic*: fixture or generated
inputs. *Assumed*: the named `flat-3pct-v1` curve. *Not supported*: refused by design today.
Nothing here is financial validation; `usableForRisk` stays `false` throughout.

## Known blockers before you demo

| ID | What | Effect | Owner |
|---|---|---|---|
| F1 | FIXED in integration `9931b3e5`. Builds before it encode typed orders (96 bytes) into a 64-byte buffer. | On an older build every order with `orderType` returns 504 `no committed ack` and is never sequenced. | Fixed by the RI-06 lane, integrated by the coordinator |
| F3 | A trailing stop's watermark ratchet emits no order update. | The blotter shows the stop level from the order's last event. The console now labels it that way. | RI-01; proposal to the coordinator |
| O1 | Trades booked while trade-processor is down are not replayed when it returns. | Read-model rows are lost for that window (4 of 4 in the repro). | RI-06 |
| O2 | `orderbook.orderid` is `1-<ref>` but `trades.sourceorderid` is `0-<ref>`. | Joins between the two tables on order id miss. | RI-06 |

Build from `9931b3e5` or later. Section 4 exits 3 (INCOMPLETE, not acceptance) while F3 is open.

## Prerequisites

Docker, `kind`, `kubectl`, Java 21, Node 20+, Python 3.10+. Nothing reads GCS or pushes images.
Check `kind get clusters` first: two kind clusters on one laptop starve the Aeron cluster. Post
on the board before starting one next to another lane's rig.

```bash
export RIG=traderx-ri07-demo                    # any unused name; never a retained rig's name
export KUBECONFIG="$PWD/.kube-$RIG"             # keeps ~/.kube/config and other rigs out of reach
export KIND_CLUSTER_NAME=$RIG CTX=kind-$RIG RIG_OFFLINE=1
TAG=ri07-$(git rev-parse --short=8 HEAD)
```

## 1. Build (source → generated → local images)

```bash
TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state-YU18-risk-integration.sh
CLUSTER_IMAGE=traderx/cluster-node:$TAG bash scripts/yu15/build-cluster-image.sh
YU15_SERVICE_TAG=$TAG bash scripts/yu15/build-service-images.sh \
  trade-processor position-service price-publisher execution-algo-engine reference-data
(cd web-front-end-console && npm ci && npx ng build)
```

The UI stack uses the existing local `traderx/*:state009` images and `traderx-api-explorer:local`.
`start-frontend-kind.sh` names any that are missing.

## 2. Bring up the full rig (order matters)

```bash
export CLUSTER_IMAGE=traderx/cluster-node:$TAG
for s in TRADE_PROCESSOR:trade-processor POSITION_SERVICE:position-service PRICE_PUBLISHER:price-publisher \
         ALGO_ENGINE:execution-algo-engine REFERENCE_DATA:reference-data; do
  export YU15_${s%%:*}_IMAGE=traderx/${s#*:}:$TAG; done
bash scripts/yu15/start-cluster-kind.sh        # members, gateway, read model, EOD chain, algo, feed
bash scripts/yu15/start-observability-kind.sh  # collector, Tempo, Prometheus, Loki, Grafana
bash scripts/yu15/start-frontend-kind.sh       # account/people/trade services, edge proxy, API docs
kubectl --context $CTX -n traderx port-forward svc/order-matcher 28110:18110 &
kubectl --context $CTX -n traderx port-forward svc/edge-proxy 28080:8080 &
(cd web-front-end-console && PORT=28090 EDGE_PROXY=localhost:28080 POD_HTTP_VIA_EXEC=1 node server.mjs) &
```

Observability must come before the frontend: the edge proxy's config names `tempo`, `grafana`,
`prometheus` and `api-explorer`, and nginx will not start while one is missing. The frontend
script now checks this and restarts the proxy after applying. With `RIG_OFFLINE=1` equities are
a synthetic walk and no tape order flow is replayed. The first bring-up pulls public images (MariaDB, NATS, nginx and
the observability stack); MariaDB alone took about 3 minutes here.

The console runs from this checkout's build. Its server calls `kubectl` with no `--context`,
so keep `KUBECONFIG` scoped to this rig.

## 3. Validate readiness

```bash
MATCHER_URL=http://localhost:28110 CONSOLE_URL=http://localhost:28090 bash scripts/ri07/rig-ready.sh
```

Expect `[ready] full rig ready` and 16 `[ok]` lines. Each check tests function, not a pod phase:

- Every member applies the probe's orders, with exactly one leader.
- The isolated probe books 4 legs into the read model.
- The console reaches the gateway through its proxy.
- The algo engine is ready.
- Prometheus sees all 3 members.
- Tempo returns the trace of one deliberately collar-rejected probe order.

Pods alone are not enough. With the algo engine scaled to 0, the workload check still passes;
the algo check fails.

The startup probe can also run on its own:

```bash
MATCHER_URL=http://localhost:28110 bash scripts/ri07/startup-probe.sh
```

The probe mints `PRB<unix seconds>`, refuses a non-empty book and trades only between reserved
accounts 880001 and 880002, leaving them flat. It fails if any complete non-probe order or trade
row changed; it compares a sha256 over all such rows, not a truncated summary. Every read-model
query must succeed. An existing account in the probe range is used only if it carries the
automated probe name and has no user mappings. Anything else is refused, never overwritten
(choose other `PROBE_A`/`PROBE_B`). Those two accounts appear in the account directory as "RI-07 startup probe A/B
(automated, not for trading)". The residual window, someone typing that exact unseen ticker
between the check and the first order, is detected afterwards, not prevented.

## 4. Show the order behaviours (live)

```bash
BUSINESS_DATE=$(date +%F) CONSOLE_URL=http://localhost:28090 python3 scripts/ri07/order-types-live.py evidence.json
```

This runs 30 ordinary cases plus 1 known-gap case, all through the console's `/order-matcher`
proxy. Exit codes:

- 0: every case matched, the known-gap case included. This is the only acceptance.
- 3: INCOMPLETE. The ordinary cases matched but F3 still reproduces. This is today's result.
- 1: a mismatch, or a failed or malformed read. The run aborts and draws no verdict.
- 2: a precondition refused the run. Each case is judged at the read model and member `/bbo`, never by the HTTP answer alone.
It covers:

- all seven types, with DAY, GTC, IOC and FOK where eligible;
- boundary refusals (422);
- engine refusals: STOP_ALREADY_TRIGGERED, FOK_UNFILLABLE, PEG_REFERENCE_MISSING,
  TRAIL_REFERENCE_MISSING and NO_TRADING_DAY;
- stop and trailing triggers, and iceberg replenishment;
- peg reprice and suspension;
- cancel, and replace (including a refused TIF change);
- DAY expiry at DAY_END.

The engine never reads a clock. `BUSINESS_DATE` must be later than any date this rig has
already opened; a repeat on the same rig needs the next date, and a stale date exits 2.

In the console (Trading page) the ticket offers the seven types with the FR-OT06 TIF matrix.
Submit a STOP and watch it rest as PENDING_TRIGGER; expand the row for type, stop and trail.
The console has no replace or business-day controls; those steps go through the same proxy
with `curl`.

## 5. Trade to risk (closed RI-03 profile)

```bash
python3 -m venv .venv-ri07 && .venv-ri07/bin/pip install -r \
  specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles/requirements-container.txt
PATH="$PWD/.venv-ri07/bin:$PATH" bash scripts/ri07/risk-flow-local.sh /private/tmp/traderx-ri07-risk-$(date +%s)
```

The supported path is synthetic, with the assumed curve:

1. The in-process sequenced engine and the production exporters build the dated bill export.
2. The bundle goes through the coordinator.
3. The accepted engine image (`88ad80a6…`, run by the external RI-02 launcher as `traderx-ri07-ri03`) prices it.
4. Intake validates the result.
5. The job reads `CONTAINER_PRICING_VALIDATED`.

The script checks the result independently against a USD 1e-8 Decimal reference, runs the
four upstream refusal cases, and removes its container on exit.

**Profile restriction.** RI-03 admits only bundle `c3211337…` with origin `synthetic`
(`container_adapter.inputs`). Every other bundle is refused before staging, and so is every
trade booked on a live rig. The script shows that the refusal happens and names the mechanism.
Status reports only `WORKER_FAILURE` ("The worker attempt failed."), not the reason. Arbitrary
trades therefore receive **no** risk result today. Economics are never re-entered to fake
lineage. The live rig's EOD cut is written to the member's local file sink. Publishing EOD
prices needs a console administrator sign-in, and this guide does not script it.

## 6. Collect evidence

Keep the outputs of `rig-ready.sh`, `startup-probe.sh`, `order-types-live.py` (the JSON file),
and `risk-flow-local.sh` (its directory). Add the rig identity:

```bash
kubectl --context $CTX -n traderx get deploy,sts -o jsonpath='{range .items[*]}{.metadata.name} {.spec.template.spec.containers[0].image}{"\n"}{end}'
shasum -a 256 <files>
```

Evidence stays outside Git. The 2026-09-24 run is in
`coordination/eod-integration/review-evidence/ri07-demo-acceptance-20260924/`.

## 7. Clean up only what you own

```bash
kill %1 %2 %3                                   # the two port-forwards and the console server
bash scripts/yu15/stop-cluster-kind.sh          # deletes kind cluster $KIND_CLUSTER_NAME
kind get clusters                               # a retained rig must still be listed, untouched
```

`stop-cluster-kind.sh` deletes the historical `traderx-yu12-cluster` when `KIND_CLUSTER_NAME`
is unset. Always set it.

## Clean start versus retained state

This guide always starts clean: a new cluster, a fresh epoch and an empty database. It says
nothing about recovering a retained rig. Fresh-epoch safety over retained SQL is an open RI-06
item; trade ids restart per epoch and deduplicate against old rows. To reuse a rig, rerun
sections 3–5; section 4 needs a later `BUSINESS_DATE`. Minted `PRB`/`OTA`/`OTB` instruments leave
positions that an EOD run would report as unpriced, so use a disposable rig for section 4.

The source-level controls for the probe and the exercise need no rig:

```bash
python3 -m unittest discover -s scripts/ri07 -p 'test_*.py'
bash scripts/ri07/test-startup-probe-sql.sh   # disposable MariaDB container traderx-ri07-sqltest
```
