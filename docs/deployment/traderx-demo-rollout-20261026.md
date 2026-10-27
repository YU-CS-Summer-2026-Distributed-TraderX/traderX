# Rolling `traderx-demo` to the YU18 tip (plan, not performed)

Prepared 2026-10-26 (evening ET) from `traderX-risk-integration` tip `de5e5d53`, after the whole stack
was brought up on kind from an empty-root render of that tip and proven as one system
([kind-full-stack-20261026.md](../risk-integration/kind-full-stack-20261026.md)). Nothing here has
been applied, pushed or scaled. The roll itself is yaakov's call.

Target: project `project-compsci-484414`, cluster `traderx-demo`, zone `us-east1-b`, namespace `traderx`.
Procedure of record: [compsci-gke.md](compsci-gke.md) (render, admission, apply, HTTPS, Desk). This plan
is the delta from the October 11–12 pack to the tip, in the order it has to land.

## 0. What the rig runs today (read 2026-10-26 ~8:00 pm ET, read-only)

| Workload | Replicas | Image today |
| --- | --- | --- |
| order-matcher-cluster (sts), cluster-gateway, risk-extract | 0 | `cluster-node@sha256:26b20f36…` (compsci-6156ad22-20261011) |
| trade-processor | 0 | `trade-processor@sha256:0d7ffb5c…` (c4d-delivery-ef43517aea1f) |
| position-service, price-publisher, reference-data, execution-algo-engine | 0 | Oct 11 digests |
| people/account/trade-service, web-front-end-angular, api-explorer | 0 | Oct 11 digests |
| web-front-end-console | **1** | `web-front-end-console@sha256:ade2b173…` |
| web-front-end-desk | 0 | `web-front-end-desk@sha256:c912d7a0…` |
| tempo, loki, prometheus, grafana, otel-collector, nats, eod-price-db, edge-proxy | 0 | public pins |

Nodes: `default-pool` 1 × e2-standard-4 running; `engine-pool` (c4d-standard-8, taint `traderx-engine`)
at 0 nodes. PVCs present: `eod-price-db-data`, `desk-users`. Members are emptyDir (`data`), heap
`-Xms1g -Xmx3g`, `CLUSTER_HEARTBEAT_TIMEOUT_MS=200`, no archive volume.
Secrets present: `auth-secrets` {console-admin-hash, console-session-secret, dev-token-master-secret,
jwt-secret, risk-control-token}, `mariadb-credentials`, `grafana-credentials`, `risk-extract-gcs-hmac`.
`database-init-sql` ConfigMap is already `DECIMAL(18,6)` (27 columns; 20,6 and 24,6 for the wide ones).

## 1. Images to build (amd64) and push

Macs build arm64 by default; every image below must be built with `--platform linux/amd64`
(`YU15_PLATFORM=linux/amd64` for the yu15 build scripts; `build-images.py` does it itself). The
registry digest exists only after the push, so the table carries the local amd64 image ID; fill the
`@sha256:` column from `docker buildx imagetools inspect` after yaakov pushes.

Tag: `yu18-de5e5d53` (tip commit). Registry: `us-east1-docker.pkg.dev/project-compsci-484414/traderx-demo/`.

| Image | Context in the render | Built from | Local amd64 image ID | Registry digest (after push) |
| --- | --- | --- | --- | --- |
| cluster-node (members, gateway, risk-extract, feed-adapter) | `order-matcher` (`Dockerfile.cluster`) | host `gradlew clean bootJar` | §1a | — |
| trade-processor | `trade-processor` (`Dockerfile`) | host bootJar | §1a | — |
| position-service | `position-service` (`Dockerfile`) | host bootJar | §1a | — |
| execution-algo-engine | `execution-algo-engine` (`Dockerfile`) | host bootJar | §1a | — |
| price-publisher | `price-publisher` (`Dockerfile.compose`) | in-container | §1a | — |
| reference-data | `reference-data` (`Dockerfile.compose`) | in-container | §1a | — |
| account-service, trade-service | `Dockerfile` | host bootJar | §1a | — |
| people-service | `Dockerfile.compose` (.NET 9) | in-container (emulated) | §1a | — |
| web-front-end-angular | `web-front-end/angular/Dockerfile.compose` | in-container | §1a | — |
| api-explorer | `api-explorer/Dockerfile` | nginx + static | §1a | — |
| web-front-end-console | root `web-front-end-console/Dockerfile` | `npm run build` + server.mjs | §1a | — |
| web-front-end-desk | `scripts/gcp-compsci/Dockerfile.desk` (FROM the console image) | combined prototype `ng build` | §1a | — |

The supported path is `python3 scripts/gcp-compsci/build-images.py --output <dir> --tag yu18-de5e5d53`
(derivation-admitted, writes `published.json` with image IDs and architectures). Its admission guard
hashes the whole checkout before and after an independent re-render and refuses if any file changed
in between; with two other chats writing into this checkout it refused twice tonight ("current source
changed during derivation admission"). Run it when the checkout is quiet, or build by hand from an
empty-root render as §1a did and record the IDs.

### 1a. Local amd64 image IDs

See the table at the end of this file (filled in once the amd64 builds finished). Nothing has been pushed.

## 2. Manifest changes since the October 11 pack

All on `specs/YU17-otc-rates/generation/kubernetes/cluster/gke/` (the YU18 pack has no cluster layer of
its own; `scripts/gcp-compsci/prepare.py` reads this directory explicitly) plus the generated
`kubernetes-runtime` base.

1. **ENGINE-REVIEW S1–S6 (2026-10-25, `99c70acc`…`fee23a87`)**
   - S1 `AUTH_JWT_SECRET` on members, gateway, trade-processor: the order routes require a bearer JWT
     bound to the account.
   - S2 `RISK_CONTROL_TOKEN` + `X-Risk-Operator` on `/seed`, `/risk/control`, `/session`, business-day
     roll. `scripts/gcp-compsci/initialize-accounts.py` and `initialize-reference-data.py` send it;
     the console server holds it (`DESK_RISK_CONTROL_TOKEN`).
   - S3 FIX acceptor credentials: `FIX_SESSION_COMPIDS` per-CompID account binding (gateway.yaml).
   - S4 symbols are registered only through `/seed`; order paths look up and never register. The
     reference initialisation must run before any order.
   - S5 `DevSecrets` refuses to start on a development secret. Members and gateway now also require
     `CLUSTER_CLIENT_SECRET` (`4b526bd0`): **`auth-secrets` on the rig lacks the `cluster-client-secret`
     key** and the Oct 11 pack never had it. Generate with `openssl rand -base64 32`, add the key to
     `auth-secrets` (keep the five existing keys), and carry the same value to the gateway, risk-extract,
     feed-adapter and the proof client. There is no `ALLOW_DEV_SECRETS` on GKE.
   - S6 bounded request bodies / batch size (gateway env, no secret).
   - The kustomization generates `auth-secrets` and `gateway-secrets` from `secrets/auth-secrets.env` and
     `secrets/gateway-secrets.env` (git-ignored; `*.env.example` beside them). `prepare.py` strips
     Secret objects and rewires `RISK_CONTROL_TOKEN` to `auth-secrets/risk-control-token`, so on this
     rig the operative Secret stays `auth-secrets`; `provision-secrets.py` is the tool that writes it
     (existing keys must match `~/.config/traderx/compsci/credentials.json`; add the new key only).
2. **12 GB member heap (`d9224e37`)**: `JAVA_TOOL_OPTIONS=-Xms12g -Xmx12g …`, member request 3 CPU /
   16 GiB (ADR-079 §6). A c4d-standard-8 node (8 vCPU / 31 GiB) holds one member. `MAX_ACTIVE_BOOKS`
   (`42ecfc44`) refuses a new book with `BOOK_CAPACITY` instead of OOMing.
3. **Dedicated archive volume (`858319c3`)**: `storageclass.yaml` `order-matcher-archive`
   (hyperdisk-balanced, ext4, WaitForFirstConsumer, Delete) and a generic ephemeral `data` volume of
   32 GiB in `statefulset-emptydir.yaml`. Same pod lifecycle as emptyDir. Hyperdisk attaches to C4D
   only; on an N2 pool set `type: pd-balanced`.
4. **`-XX:MaxGCPauseMillis=50` (`6af15608`)**: in the same `JAVA_TOOL_OPTIONS`; the default 200 ms pause
   equals the 200 ms heartbeat and tripped elections at 1,000 books.
5. **Projection and EOD chain (`dddcdc86`, `0a52d2d6`, `e31093f2`)**: trade-processor's downstream
   projection is bounded/batched and exposes `GET /v2/projection-feed`; the extract triggers on
   `eod.prices.ready` (new durable `risk-extract-prices`); the cut is chunked across NATS. No manifest
   change beyond the images, but the SQL read model is a projection of the **old** epoch's log.
6. **Console (`de5e5d53`)**: per-sign-in sessions; old-format cookies are refused, so every signed-in
   browser signs in again after the roll. Desk image is derived from the console image (`Dockerfile.desk`).
7. **NetworkPolicy (`networkpolicy.yaml`)**: applied with the kustomization; enforced only if
   `traderx-demo` was created with Dataplane V2, which has not been verified. Run
   `CTX=gke_… scripts/proofs/yu18-networkpolicy-enforced.sh` after the members are Running; until it
   passes, `CLUSTER_CLIENT_SECRET` is the only thing closing the ingress.

## 3. Apply order

0. **Pre-flight**: `kubectl config current-context` is the GKE context; `git branch --show-current`
   is `traderX-risk-integration` at `de5e5d53` or later; `engine-pool` is still at 0 nodes.
1. **Secrets first**: add `cluster-client-secret` to `auth-secrets` (§2.1). Verify with
   `kubectl -n traderx get secret auth-secrets -o jsonpath='{.data}' | jq keys` (six keys). Never print
   values.
2. **Database init ConfigMap first** (`DECIMAL(18,6)`): `kubectl -n traderx apply -f` the rendered
   `kubernetes-runtime/manifests/base/database-init-configmap.yaml` from the tip render. It is already
   18,6 on the rig; re-applying is idempotent. The ConfigMap matters only at first boot on an empty
   datadir, which is why the PVC wipe in §4 comes **before** the DB pod starts.
3. **Wipe for the fresh epoch** (§4), with `engine-pool` still at 0.
4. **Scale `engine-pool` to 3**: `gcloud container clusters resize traderx-demo --node-pool engine-pool
   --num-nodes 3 --zone us-east1-b` (the harness may refuse this command; hand it to yaakov).
   Wait for three Ready nodes before applying the StatefulSet or the members sit Pending against a
   correct spec.
5. **Render + admit** the candidate exactly as compsci-gke.md says: `prepare.py --output … --tag
   yu18-de5e5d53 --dns-ip <kube-dns IP>` with `--published`/`--public-lock`, then `admit.py`, then a
   `kubectl apply --dry-run=server -f candidate.yaml`.
6. **Apply the admitted pack**. The pack is one file; this is the rollout order to watch:
   nats → eod-price-db (first boot runs the 18,6 schema) → reference-data → trade-processor →
   price-publisher, position-service → order-matcher-cluster (3/3, one leader, `applied` moving) →
   cluster-gateway → risk-extract, execution-algo-engine → people/account/trade-service,
   web-front-end-angular, api-explorer, edge-proxy → observability → web-front-end-console →
   web-front-end-desk.
7. **Stale-image check** on every pod: running `imageID` digest equals the pushed digest; any mismatch
   → `rollout restart` that workload. Check env on the **serving** pod (`printenv`), not the Deployment.
8. **Initialise**: `initialize-accounts.py --apply`, then `initialize-reference-data.py --apply`
   (both send the S2 pair). S4 means no order books before this.
9. **Prove on the rig**: authenticated order → three members agree (`applied`, `trades`) → SQL row →
   `GET /v2/projection-feed` backlog 0 / dropped 0 / missing 0 → positions; EOD close → marks →
   `eod.prices.ready` → cut sha identical on all members → write-once object in
   `gs://project-compsci-484414-traderx-risk-extracts` (403 on overwrite); a trace in Tempo and the
   gateway's log line in Loki; `yu18-networkpolicy-enforced.sh`. A 2xx is not proof.
10. **Teardown** when done: `engine-pool` back to 0 nodes; confirm with `gcloud compute instances list`.

## 4. PVC / epoch wipe

Why a fresh epoch is required, not optional: the Oct 11 journal predates S4 (symbol registration only
from `/seed`), G1 (idempotency key scoped by account), the typed-order index, `MAX_ACTIVE_BOOKS`, the
ingress authenticator and the packed-snapshot option. A mixed-version window on a live log diverges
the members permanently (same `applied`, different books), and a replay of the old log through the new
apply path is not a supported upgrade. The SQL read model and the extract sink are projections of that
log and must go with it.

With `engine-pool` at 0 there are **no member PVCs** on this rig: `data` is emptyDir, so the members'
log, archive and snapshots are already gone. What has to be wiped by hand:

1. `kubectl -n traderx scale deploy/eod-price-db --replicas=0` (already 0), then
   `kubectl -n traderx delete pvc eod-price-db-data && kubectl -n traderx wait --for=delete
   pvc/eod-price-db-data --timeout=120s`, then **re-apply the PVC** (nothing recreates a Deployment's
   PVC). The DB then first-boots against the `DECIMAL(18,6)` ConfigMap from §3.2.
2. Extract sink: leave `gs://…-risk-extracts` as is (write-once, dated object names); the new epoch
   writes new objects.
3. Journal archive bucket `gs://…-journal-archive`: not read by this profile (`RESTORE_FROM_GCS` is
   stripped by `prepare.py`); nothing to wipe.
4. `desk-users` PVC: keep (workspace memberships; sessions are signed out by the cookie format change
   anyway).
5. After the members are up no projection clear is needed because the DB datadir is new;
   `initialize-*` seeds the directory and the catalog.

## 5. Cost estimate (us-east1, on-demand list prices, rough)

| Item | Rate | For a 4-hour roll + proof | Per day if left up |
| --- | --- | --- | --- |
| engine-pool 3 × c4d-standard-8 | ≈ $0.38/h each ≈ $1.14/h | ≈ $4.60 | ≈ $27 |
| 3 × 32 GiB hyperdisk-balanced (ephemeral, with the pods) | ≈ $0.03/h | ≈ $0.15 | ≈ $0.80 |
| default-pool e2-standard-4 (already running for the console) | ≈ $0.13/h | already paid | ≈ $3.20 |
| HTTPS LB + static IP (already provisioned) | ≈ $0.03/h | already paid | ≈ $0.70 |
| Artifact Registry storage, ~5 GB of new images | ≈ $0.10/GB-month | ≈ $0.50/month | — |

So the roll is about **$5 of new spend** if the engine pool goes back to 0 the same evening, and about
**$28/day** extra while the engine pool stays up. These are list-price estimates, not a quote; the
account's actual SKU prices apply.

## 6. Not in this plan

- No image has been pushed; no `kubectl apply` or scale has been run against `traderx-demo`.
- Alex's risk engine is not deployed or upgraded by this roll (compsci-gke.md scope).
- The hxb bench nodes (`gke-hxb-bench-20261022-*`, 9 instances) were RUNNING at 8:00 pm ET tonight;
  they belong to another lane and bill independently of this plan.

## 7. Update after the `traderx-tip` dry run (2026-10-26, 11:21 pm ET)

The plan was exercised end to end in a side namespace (results:
`docs/risk-integration/kind-full-stack-20261026.md`, GKE follow-up). Changes to the plan above:

- **Build from `8ea15b88` or later.** Earlier tips cannot seed reference data on a fresh epoch
  (`422 unknown instrument` from `/risk/control`).
- **Build recipe:** `export SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"`, render with
  `TRADERX_SKIP_LOCKFILE_REFRESH=1`, then `build-images.py`. Run from a checkout no other chat commits to.
- **StorageClass:** `order-matcher-archive` is in the rendered candidate since 2026-10-27 (`prepare.py`); it is
  also already present on `traderx-demo` from this run. Without it the members stay Pending.
- **Secrets:** `auth-secrets` needs six keys including `cluster-client-secret`; `mariadb-credentials` and
  `grafana-credentials` as before. Fresh random values worked first time; S5 never refused.
- **Seeding:** `initialize-accounts.py --apply`, then `initialize-reference-data.py --apply`.

Images proven in `traderx-tip` (`tip-8ea15b88`, linux/amd64, pinned by these digests):

| Image | Digest |
|---|---|
| cluster-node | `sha256:2b419c433fd7c71e9268e725e947074ac415acc9eff0a4f43d700e9fbce7f598` |
| trade-processor | `sha256:c781df34f3fda0b77e8f7100155a7b7d1e7ef6b1d5d47808ec0a5f6301eecd22` |
| position-service | `sha256:06865f3ec40f6c46d379df8cc7405fb75965a2cb9332281a65ce15c040a74652` |
| execution-algo-engine | `sha256:24b73a0b8ca3c768b065ba0be84b927f17b6e33807a5474840195b32e44d5c29` |
| trade-service | `sha256:f82058208bc461553154087dee38de47fbe26482da5d8c5292768b64647de329` |
| account-service | `sha256:ed504329afd075f46ea632074f915feefb7470111890ae26a5f93efc19b98bbc` |
| people-service | `sha256:dfa37b4db5a34c2b89d9a50f023101825a615978de7bdfa38f5ff6f7fbfacfc9` |
| price-publisher | `sha256:2dcd5e01bf62e9223ef1d2fd8378ffd465b755e550cc8e0aae0ca5632a4f10b6` |
| reference-data | `sha256:cfa33155a0537331f3efae53046bac1cd115a83111ad8fa06fb517396f1bb2fa` |
| web-front-end-angular | `sha256:ca5a9438185c03eaa39a907e16fb7c1f8ec2223e777fca2920bd2db60c8e535f` |
| api-explorer | `sha256:9c045168704e2426073a643a6d88e74371a4ce5b25fc2bbc09fad7ab0100c9e9` |
| web-front-end-console | `sha256:b705c6ff9e9749067212a6b422ae2126929fa8d1313764bcabe6bb3d9d25cb54` |

## 8. Live verification of the fixes in `traderx-tip2` (2026-10-27, 12:58–1:50 am ET)

Commit `951ed3c9` (the six 2026-10-26 findings) was built amd64 on a throwaway VM, pushed as `tip-951ed3c9`
and run in a fresh namespace with NO hand-applied StorageClass (the earlier one was deleted first; the
render's own class bound all three member volumes). Rebuilt images, pinned by digest; the other nine stay
at the `tip-8ea15b88` digests above:

| Image | Digest |
|---|---|
| trade-service | `sha256:ae8ce6dadfb996289d7dbbf678b295c50c32b843edf3d6c6f0da54b773de822b` |
| price-publisher | `sha256:6992127af590ef10bdabf1559338463357f57fa13fe3cdb4f4fcfe6cac03b22e` |
| web-front-end-angular | `sha256:47ff15fec32f8c119882deb99d3110821f45b93b3d53be2ef33428603f33e75c` — build it from `web-front-end/angular/Dockerfile` (node), not `Dockerfile.compose` (nginx): `prepare.py` runs the container with `npm run start` |

Results: legacy ticket `POST trade-service /trade/` booked (members 0 → 1 trades, applied 1292 on all
three) while a bare gateway `/trades` is 401; a qty-0 ticket is `422 {"detail":"Order matcher refused the
trade: INVALID"}`; `yu16-treasury-pricing` PASS (13-week bill 16 days out at 0.99816 / 4.28%);
`yu17-retick-determinism`, `yu17-halt-survives-failover` and `yu17-closed-survives-restart` PASS with
`DESTRUCTIVE=1 EXPECT=after`. Two proof fixes fell out: the destructive proofs' snapshot barrier now follows
`CLUSTER_SNAPSHOT_INTERVAL_MS` (300 s here, 60 s on kind), and the retick proof stages the scale-drift
re-derivation (ENGINE-REVIEW F4 closed "create-before-tick" by construction). TAQ replay proofs skipped:
TAQ path retired (yaakov 2026-10-27). Teardown: namespace deleted, pools 0/1, VM deleted.
