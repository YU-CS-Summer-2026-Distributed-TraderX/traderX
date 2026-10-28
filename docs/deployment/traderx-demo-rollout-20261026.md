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

## 9. Production rollout of `traderx` to the tip (2026-10-27, 9:08–10:35 am ET)

yaakov's go (relayed by the coordinator): roll `traderx` to the proven tip images with this plan and
promtail in `--public-lock`. Source `9e0fd80b`; images built on a throwaway VM by `build-images.py`
(its derivation guard admitted all 11 contexts, `SOURCE_DATE_EPOCH` set, BuildKit on), tag
`tip-9e0fd80b`, pushed and checked one by one with `docker buildx imagetools inspect` (single linux/amd64
manifest, label `dev.traderx.source.commit=9e0fd80b`). Running digests:

| Image | Digest |
|---|---|
| cluster-node | `sha256:eefcbfe4aa2525be7f4903075ff1d3c8544749694c1da0c7e32cec84b037ca43` |
| trade-processor | `sha256:cc5e5119970b6d9a5cea3b09d8056e16372269a5e72fa7799dd4ccf7c3befa72` |
| position-service | `sha256:d39c4139dbe8c82ee7a4e007e68108ca05c3025e94fe222d0197f91753f46ac3` |
| execution-algo-engine | `sha256:13bbf19cec744009bbaffd5b8f181d2d7fce4b1a2532b137b8e719b74e72e79b` |
| trade-service | `sha256:dc1aa3817389e1e05c30abc9f7f5742cc52506083c4b5bd005f76a47769162c3` |
| account-service | `sha256:ee6ccae0af457e68fae6817d4c30c694f522d103a9a06ffa9478deffdb2fb619` |
| people-service | `sha256:fd790b6290f8a69b9a0fc23968cfc8f528035f3906e74eb0a884876fb9c70956` |
| price-publisher | `sha256:6006dcee6b218c20e295a0b6be3a1921dade0a3e2d6e81464b6ed1b82f6c2910` |
| reference-data | `sha256:69ad487b183348890f078d157d0726ecdc2d07f79407e1c59ae68234209706fb` |
| web-front-end-angular | `sha256:e6db5f7311d8a16a37c3878c76b53951be9f8ac42f54fde4b3781c81185977cf` |
| api-explorer | `sha256:c60f0c1bf6d78a77d406bb7f9272c8aa4de03beae07c8a51b7883c845a714832` |
| web-front-end-console | `sha256:52db8220f891c198f8d489f3c6163df258241e6f829fadfd56ec43b70e2ea4a6` |
| web-front-end-desk | `sha256:4451f06f0902bf99a9fc86427e69b590367c64470796215580452426b93978c6` |

Public images: `scripts/gcp-compsci/public-image-lock.json` (`1fdc1440`; the October 11 lock plus promtail).

**`admit.py` was not run for this rollout.** It pins the reviewed image dependency to the October 11 commit
`6156ad22` and requires an image built from it, so it refuses any tip population by construction; its
payload/executable evidence also existed only as hand-made October 11 artifacts. yaakov chose to gate this
rollout on the checks below and to fix the admission afterwards (moving the dependency to the deployed
commit and scripting the evidence). Gates used: the tip rebuild by `build-images.py`; digest pins plus
`imagetools inspect`; the public lock including promtail; `prepare.py` validate (24/24 slots pinned) and
`test_prepare` 25/25; `kubectl apply --dry-run=server` (68 objects); the post-apply stale-image check.

Order of operations: rollback record of every `traderx` object (9:08 am); DB disk snapshot
`traderx-eod-price-db-pre-tip-20261027` (9:55 am); `cluster-client-secret` added to `auth-secrets` (now six
keys); engine-pool 3, default-pool 2; DB init ConfigMap; `eod-price-db-data` deleted for the fresh epoch
(10:14 am); `kubectl replace` of order-matcher-cluster, nats, trade-processor and web-front-end-console,
`kubectl apply` of the other 63 objects (10:18 am); desk via `render-desk-deployment.py` (10:18 am).
**Why replace for four workloads:** those objects were never created with `kubectl apply`, so apply would
have kept eight hand additions from October 11–23 that are in no pack. One was a `restore-before-start` init
container on the members and nats that waits for an uploaded archive, so every new pod would have hung in
Init. The others (`batch-custody` on the console, `RISK_BATCH_*`, `EOD_PYTHON`, `CONSOLE_CLI_CONCURRENCY`,
trade-processor `PROJECTION_DELIVERY_*`) are no longer read by the tip.

Verified (10:28–10:35 am ET):

| Check | Result |
|---|---|
| Pods | 30/30 Running and ready |
| Stale-image check | 31 containers, 22 admitted references, 0 mismatches |
| Members | fresh epoch, one leader; 11 accounts and the reference catalog applied at 1291 on all three |
| TLS | demo.yaakovseif.dev and desk.yaakovseif.dev: 200, Google Trust Services cert valid to Jan 9 2027 |
| Gateway auth | `/orders` without a bearer: 401 |
| Order at the effect end | MSFT Sell 5 @393 (42422) x Buy 5 @394 (22214): applied 1291 to 1293 and 2 trade sides on all three members; SQL `1-S`, `2-B` at 393; positions 42422 -5, 22214 +5 @393; console blotter read shows both; projection feed backlog 0, dropped 0, missing 0 |
| Loki | `{namespace="traderx"}` streams from 25 services; Grafana healthy, its Loki datasource is `loki:3100` and reachable from the Grafana pod |

Findings, not fixed: the bare `yaakovseif.dev` has no certificate (the load balancer presents the demo
certificate, so TLS fails); `grafana.yaakovseif.dev` is not part of this plan; the gateway logs `FIX acceptor
INSECURE: FIX_SESSION_AUTH unset` (ENGINE-REVIEW S3 credentials are not configured in this profile).
Rollback: the recorded objects (shared notes, `prod-rollback-20261027T0908/`) plus the DB snapshot.

**Retroactive admission (10:39–10:59 am ET, yaakov's Option B).** `admit.py`'s reviewed image dependency is now
one constant, `REVIEWED_IMAGE_COMMIT = 9e0fd80b`, and `scripts/gcp-compsci/measure-admission.py` produces the
measured evidence the October 11 pack had only as hand-run helpers. Run on a throwaway VM, on a checkout of
exactly `9e0fd80b` (the generated contexts record HEAD; the new scripts uncommitted there), against the
applied candidate (sha256 `811901026c48…`, identical to the file applied):

| Check | Result |
|---|---|
| Population read from the candidate | 12 private + 9 upstream images, 24 slots |
| Image identity | every private image pulled by digest is linux/amd64 and labelled source commit `9e0fd80b`, one derivation input hash |
| Independent derivation | full ancestry, 11 contexts; `web-front-end` differs from the first build only by the GitHub origin, as expected |
| Payloads vs independent reference builds | 12/12 byte-identical (cluster-node 457 files, reference-data 22,173, web-front-end-angular 36,885, …) |
| OCI | registry manifests hash to the pins; configs, layers and labels equal the pulled images; 9 upstream pins complete linux/amd64 |
| Runtime | every image's own executable reports amd64/x64 (`os.arch`, `dotnet --info`, `process.arch`, the nginx ELF header) |
| `admit.py` | **admitted-offline**, 21 artifacts / 24 slots |
| `admission-controls.py` | 19/19 refusals still fire against this real population |

The build host was deleted after the rollout, so the build records were reconstructed from the image labels
plus the fresh derivation (`summary.json` says so); the payload comparison is what binds the images to the
source. Next time pass `--build-records <build-images.py output>` while the build VM is still up. Evidence:
the shared notes folder, `admission-9e0fd80b-20261027/`.

**Sign-in checks with yaakov (11:05–11:23 am ET).** yaakov signed in himself; no production password was typed by
the agent.

| Check | Result |
|---|---|
| Console order, effect end | ticket in the browser pane: 42422 Sell 5 MSFT @393 (orderRef 5, 11:13:32) then 22214 Buy 5 @394 (orderRef 6, 11:14:22); crossed at 393; members applied 1300 / 4 trade sides on all three; SQL `3-S`, `4-B`; positions 22214 +10, 42422 −10; console blotter shows `4-B`; projection feed backlog 0, dropped 0, missing 0 |
| Per-client sessions | pane `PPAlpildGx8m`, yaakov's Chrome `Y7AvjX`, both signed in at once; after Chrome signed out, the pane was still `PPAlpildGx8m` (11:23:26) |
| Grafana | signed in through the console's `/grafana/`; Explore on Loki `{namespace="traderx"}`: 8,590 lines in 30 minutes, newest 11:18 |

**Incident, cleaned up.** Filling the ticket with batched automation (a stale element reference after the
form re-rendered) set the "Equity — buy 100 IBM @ 200" preset and submitted it: order `1-3`, 42422 Buy 100 IBM
@200, rested at 11:09:50. A second stray submission, `1-4` (42422 Sell 5 IBM, STOP_LIMIT DAY), was rejected
NO_TRADING_DAY at 11:10:38 and never rested. On yaakov's go, `1-3` was cancelled through the gateway at 11:11:29;
open orders went 1 → 0 on all three members and the read model shows it CANCELED. Neither traded. The real order
was then entered one field at a time, the ticket read back before each Submit.

Further finding: the console's Grafana link is `/grafana`, which 301s to `http://demo.yaakovseif.dev:8080/grafana/`
and fails behind the HTTPS load balancer; `/grafana/` works.

## 10. Demo walk-through and fixes on `traderx` (2026-10-27, 11:30 am–12:26 pm ET)

Every tab of demo.yaakovseif.dev was exercised on the rolled namespace, signed in as admin, except what needs
data we do not hold (Replay, Corpus, Sandbox: the TAQ venue is retired). Working as found: all eleven ticket
stories, System, FIX (ExecutionReport New), Admin (bands, TCA, force-settle, reconciliation), Accounts
(enable/disable), EOD reading, and every legacy UI page. Not configured on this deployment, as in October: EOD job
status (`EOD_COORDINATOR_STATE`), the Treasury demo (`TREASURY_DEMO_STATE`), reference examples
(`RISK_DEMO_ARTIFACT`).

Four defects, fixed in `0c002f80` and the custody renderer, rolled 12:20 pm ET:

| Defect | Fix | Proof on `traderx` |
|---|---|---|
| Kdb "Order final state" / playback dropped a fill sharing its seq with the NEW (an aggressor filling on arrival read as resting) | dedup key adds status + remaining; `mergeOrders` spec | orderRef 17 (Buy 30 IBM, crossed on arrival) shows FILLED, 4 events |
| Algo child orders posted bare → 401 since S1; TWAP `ec765d89` retried every tick (354 401s in 2 min) | `OrderMatcherClient` signs a 60 s account-bound non-admin HS256 bearer; `AUTH_JWT_SECRET` (optional) in both algo manifests | children 14/15/16 accepted; filled by the Buy 30; parent COMPLETED; 0 401s since |
| `/grafana`, `/api/docs`, `/prometheus` 301 to `http://…:8080/` behind the TLS LB | `absolute_redirect off` in the edge-proxy config | all three now 301 to `https://demo.yaakovseif.dev/…/` |
| Risk tab Cloud Batch empty: §9's console replace dropped October's hand-added batch custody | `render-console-batch-custody.py` re-adds the init container, emptyDir and env; console is a risk-runtime layer | init container hydrated `risk-cpu-cloud-20261023-022416`, status VERIFIED; tab shows NPV 846,759.12 |

Images (`tip-0c002f80`, built on throwaway VM `yu18-demofix-20261027`, deleted 12:26 pm ET, ≈ $0.50):

| Workload | Digest |
|---|---|
| execution-algo-engine | `sha256:b7fc56bbd0acf749c3871a4a2d6b933f54c59f2407a572ce7494f7ddc4ac9411` |
| web-front-end-console (base for the next two) | `sha256:853e38fce4b63ad298d2d469a6cb7a3a6ca36250f561227e5229afc0164e0e71` |
| web-front-end-console, risk runtime (deployed) | `sha256:c1e9258d3b5327e9c539e630a0f829104d4c721127f35df523ae037b15c55eb4` |
| web-front-end-desk | `sha256:2e42c414fa81017f54fb085179218fcdc0334815dc5dd12141662d034d596d88` |

All four resolve as linux/amd64 and carry `dev.traderx.source.commit=0c002f80`. The risk layer's Python tools
come from `671029d1` (label `dev.traderx.risk-tools.commit`), the commit the October image was built from: it is
on `claude/risk-e2e-462e6cd`, not on this branch, whose `risk-portfolio-tools` has no `cloud_result.py`. The EOD
bundles and the console's batch reader are byte-identical between the two. These four images are outside the
`9e0fd80b` admission; the edge-proxy ConfigMap was patched live (one inserted line, resolver kept). Rollback specs:
shared notes `demo-fixes-20261027/`.

Test orders placed and removed: 10, 11, 12, 13 cancelled through the Admin control; 14–17 traded. Open orders 0;
members agree at sequence 1319, 12 trades.

**EOD close and publish on `traderx`, yaakov's go (12:51–12:52 pm ET).** "Close session" in the console minted
price version v1 for 2026-10-27 (67 instruments, 0 flagged), which published at 12:51:40. Position-service marked
18 P&L rows, 0 halted. The extract cut at consensus sequence 1320 and wrote three objects to
`gs://project-compsci-484414-traderx-risk-extracts/2026-10-27/v1/` (`.cut` generation 1793119904016740). The
`.cut` object hashes to `3fe8a8c8…cf18`, the `cutSha256` both CSVs carry. 6 position rows (the day's IBM, MSFT
and AAPL option trades, both sides) and 2 swap contracts (SW-1306, the SWPT-1308 swaption).

Finding: a cold first visit to the EOD tab can leave "Today's end of day" on "reading…". The page's cut-provenance
reads (one `gcloud storage cat` per archived cut) and the chain read share the console's two CLI slots, which
pushed the chain past the load balancer's 60 s timeout (502). With the provenance cache warm the chain reads in
about 10 s; pressing Refresh recovers.

Fixed in `5f3aed8d` (rolled 1:34 pm ET): the console's CLI runner gives background reads (the provenance panel's
GCS listing and per-cut `cat`) their own lane, started only when no foreground read waits and capped at limit−1
slots. On a restarted, cold console with the per-cut reads in flight, Refresh returned the chain in 16 s (200);
the background reads queued behind it. Images `tip-5f3aed8d`: console `sha256:18e2da3d…8e5d`, risk runtime
(deployed, tools `671029d1`) `sha256:2512ffb8…c887`, desk `sha256:5691aec0…db3f`; batch custody re-verified.
Build VM `yu18-demofix2-20261027` deleted at 1:37 pm ET (≈ $0.25).

## 11. Second production roll of `traderx`, to `b28fd9f3` (2026-10-27, 3:13–3:41 pm ET)

What it carries since `9e0fd80b`: generational-ZGC gateways with a 4-CPU limit (`e201a1bc`, `b28fd9f3`); the
cluster bridges' receipt publish (`48655be8`, member image); NATS on a 32 GiB PVC with the 2M / 1 GB ingest queue,
trade-processor snapshot locking and settlement/recon on the ledger (`0e624848`, `9f7fe45d`, `1bfe65f9`); the
afternoon's console and algo fixes (already live, relabelled). Approved by yaakov.

**No fresh epoch.** `48655be8` changes no apply-path or snapshot code (four read-only accessors; the bridges stay
leader-side, off consensus), so the log replays to the same book under the new build. Members rolled one at a
time on their pod-lifetime volumes: each rejoined empty and replayed from position 0 (one TERM entry on every
recording log, ~175 KB of log), gated by `/ready` catch-up, after a snapshot barrier (62 → 63 on all three).

| Step | Result |
|---|---|
| Build (VM `yu18-roll2-20261027`, 3:14–3:21 pm) | 12 private images + desk + `tip-b28fd9f3-risk` console, all linux/amd64, label `b28fd9f3` |
| `measure-admission.py --build-records` | **admitted-offline**, 21 artifacts / 24 slots; 12/12 payloads byte-identical to independent reference builds; 19/19 refusal controls; `admitted.yaml` = candidate (sha256 `5102b55d…`) |
| Server-side dry run | 69/69 objects across the seven phases |
| Apply (`roll.sh`, 3:30:35–3:37:49 pm) | DB ConfigMap + `projection_trade_days` → NATS on PVC (3:30:57) → trade-processor → barrier (3:33:31) → members (3:35:16, agree 1321 / 12) → rest → console + desk |
| Running images | 17 containers on exactly the pushed digests, 0 stale; console runs the `-risk` layer |
| Book + positions | before = after: members 1321 / 12 trades / same book hashes; SQL 25 trades, 18 positions, same fingerprints |
| Effect end (3:40 pm) | Buy 1 then Sell 1 MSFT, account 22214, through the demo ticket (trade-service → gateway `/trades`): applied 1323 / 14 trades on all three; SQL `13-B`, `14-S` at 393; MSFT position 10 @ 393 unchanged; leader bridge published 2, rejected 0, dropped 0; projection feed backlog 0 / dropped 0 / missing 0 |
| Recon | startup sweep matched 12/12, 0 missing, 0 mismatch |
| Gateway | ZGC flags and cgroup 4 CPU / 2 GiB on the serving pod; `/orders` without a bearer: 401 |
| Console | cold EOD chain 200 in 11.7 s; Risk batch result AVAILABLE / VERIFIED; demo and desk hosts 200 |

Two differences from the admitted candidate, both deliberate: the console carries batch custody (the tip
console's `/risk/batch-result` reads `RISK_BATCH_*`; without it the Risk tab says NOT_CONFIGURED), and
trade-processor keeps `RECON_POLL_INTERVAL_MS=86400000` on this tier. In core feed mode `classify()` checks SQL
with no grace and the cursor moves past, so a sweep landing between an engine fill and its SQL insert would count
a permanent false MISSING; the 10 s schedule belongs to snapshot mode.

NATS restarted once with an empty store, as planned. The algo and EOD streams and their three consumers came
back; `TRADERX_CONTROL_SECURITY` / `_ACCOUNT` are created on the next delta reference-data or account-service
publishes (`connectAndEnsureStream`), as on any fresh store. After the restarts the EOD chain reads P&L as
"unverified" and the extract as "remote": both come from the restarted pods' logs (position-service's `halted=`
line, risk-extract's cut line), not from data. The 18 P&L rows are in SQL and the three 2026-10-27 v1 objects are
in the bucket.

Spend: build VM 3:13–3:26 pm ET, deleted (≈ $0.20). Ongoing: NATS's 32 GiB `standard-rwo` PVC (≈ $3.20/month)
and its 1-CPU request on default-pool. Rollback record (68 objects + DB dump) and the admission evidence: shared
notes `roll2-20261027/`, `admission-b28fd9f3-20261027/`.

## 12. Third production roll of `traderx`, to `40d93f6b` (2026-10-27, 7:59–8:46 pm ET)

What it carries since `b28fd9f3`: authenticated FIX logon on GKE and the console `/fixorder` sign-in (`a961d89a`),
the Grafana redirect guard (`e3149bbd`), member SIGTERM forwarding (`334e9c45`), the gateway's YU04 control-feed
subscriber (`61fafad4`), the recon grace (`6ef26b36`), and trade-processor on the durable JetStream feed with row
projection, the persisted tradeSeq watermark and the recon waiting for feed catch-up (`40d93f6b`). Approved by yaakov.

**No fresh epoch, no NATS restart.** No apply-path change; members rolled one at a time after a snapshot barrier
(123 → 124). NATS, its PVC/config and `database-init-sql` had no diff and were not touched, so the algo and EOD
streams kept their consumers. **Feed switch:** `TRADERX_FEED` did not exist, so the new trade-processor created it
and its durables (`tp-trades-p0`, `tp-orders-p0`) start at sequence 1, the first side after the switch: no trade
already in SQL can be replayed and none after it skipped. The rolling update overlaps the old core consumer with
the new one; TradeService ignores an already-booked trade id, and the book was quiet (order hash 0, algo stream
empty, applied 1323 unchanged since 3:40 pm).

| Step | Result |
|---|---|
| Secret (8:37 pm) | `auth-secrets/fix-session-password` (hex, 32 bytes) saved to `credentials.json` first, merge-patched over stdin, read back: all 7 keys equal to `credentials.json`; no value printed |
| Build (VM `yu18-roll3-20261027`, 7:59–8:05 pm) | 12 private images + desk + `tip-40d93f6b-risk` console, all linux/amd64, label `40d93f6b` |
| `measure-admission.py --build-records` | **admitted-offline**; 12/12 payloads byte-identical to independent reference builds; 19/19 refusal controls; `admitted.yaml` = candidate (sha256 `ab707313…`, 69 objects); test_prepare 32/32 |
| Server-side dry run | 69/69 objects; NATS, NATS PVC/config and DB init: no diff |
| Apply (`roll.sh`, 8:38:05–8:43:04 pm) | trade-processor (jetstream, 8:38:27) → barrier (8:38:56) → members (8:40:53, agree 1323 / 14) → rest → console + desk |
| Running images | 18 private containers on exactly the pushed digests, 0 stale; 0 restarts |
| Book + positions | before = after: members 1323 / 14 trades / same book hashes; SQL 27 trades, 18 positions, same fingerprints |
| FIX (8:44 pm) | wrong password and no password: Logout `58=invalid credentials`, gateway `Logon rejected`; before the roll a passwordless logon was accepted. Console `/fixorder` signed out: 401 |
| Good logon trades (8:44–8:45 pm) | CLIENT1 logon with the pod's password, NewOrderSingle → ExecutionReport New. Same-account Buy/Sell is self-trade-prevented (`1-18` CANCELED `SELF_TRADE_PREVENTED`); a FIX Buy 1 MSFT @ 393 from 42422 crossed the resting `1-19`: applied 1326 / 16 trades on all three, book order hash 0 |
| Effect end (that cross) | SQL `15-S` (22214) and `16-B` (42422) at 393; MSFT 22214 10 → 9, 42422 −10 → −9; leader bridge published 2 trades / 6 orders, rejected 0, dropped 0 |
| Durable feed | `TRADERX_FEED` 8 msgs; `tp-trades-p0` delivered 8 / ack floor 8 / pending 0 / redelivered 0; `projection_cursor` `trade-seq:` = 16; first side since start 15 (= 14 + 1), `missingTradeSides` 0 |
| Recon (10 s) | cursor 14 → 16 on the sweep after the fill; matched 16, missing 0, mismatch 0, orphan 0 |
| Gateway / edge | `/orders` without a bearer 401; demo and desk 200; `/grafana` → 301 `https://demo.yaakovseif.dev/grafana/` |
| Members' exit | Roll: 137 on all three, because each old pod (revision `74b8f675c5`) still ran the old `& wait $!` command and a pod terminates under its own spec. Afterwards (approved): barrier 127 → 128, then `kubectl delete pod order-matcher-cluster-0` (a follower) at 8:54:51 pm: **exit 143**, finished 8:54:52 (grace deadline 8:55:02), last lines `Cluster node stopping: shutdown signal received` / `Cluster node down`; rejoined and agreed 1328 / 16 with the same book hashes at 8:55:27 |

One difference from the admitted candidate, deliberate: the console carries batch custody (as in roll 2).
Recon now runs at the candidate's 10 s; roll 2's daily override is gone (its cause was fixed in `6ef26b36`).

Control feed: reference-data's 533 outbox rows had all been published at 2:20 pm to the store roll 2 replaced, and nothing
republishes a published row, so after the roll `TRADERX_CONTROL_SECURITY` did not exist and the gateway replica was
empty (watermark 0, count 0) while the gateway retried. With yaakov's approval, `POST /stocks` Z8812 at 8:53:33 pm
(version 534): reference-data created the stream, the gateway logged `CONTROL-FEED subscribed` and `applied
ticker=Z8812 version=534`, its replica had Z8812 about 2 s later (watermark 534, count 1), 0 restarts. The replica
holds only deltas published since 8:53 pm; the 533 earlier securities are not in it.

Readiness: the replacement member was Ready at 8:55:09 pm with applied 0, and reached `FOLLOWER_READY` and 1328 at
8:55:24–27. `/ready` is `mine >= maxPeer - CLUSTER_READY_MAX_LAG` with the default 5000, and this tier's whole log is
1328, so it does not hold a rejoining member until it has caught up. Every roll here still ended with all three
members agreeing; the end-of-step agreement check is what gated them.

Control replica completed and Z8812 removed (10:54–10:56 pm ET, approved by yaakov; the code fix that makes this
automatic, `3b10e7e2`, is not deployed). Rollback record first: `stocks`, `stocks_control_outbox`, `stocks_source_epoch`,
the stream and the gateway replica (shared notes `ctlrepub-20261027/`). Loki showed no security halt or restriction this
epoch (557 initialization enables only), so replaying `enabled=true` for the universe changed no engine flag.

| Step | Result |
|---|---|
| Disable Z8812 (10:54:08) | operator `POST /risk/control/security {"ticker":"Z8812","enabled":false}` from the console pod (token from its env): 200, members 1328 → 1329. The engine keeps the symbol id (log-replicated), now disabled |
| Delete `TRADERX_CONTROL_SECURITY` (10:54:26) | held 1 message (Z8812); absent after |
| SQL, one transaction (10:54:38) | `stocks` −1 and outbox −1 (Z8812, version 534); 533 rows flagged unpublished; after: 533 / 533, max version 533 |
| Restart reference-data (10:54:51) | stream recreated on the first publish: 533 messages, seq 1..533; outbox unpublished 0; snapshot watermark 533, count 533, no Z8812 |
| Restart the gateway (10:55:39) | replica count 533, watermark 533, ticker set = reference-data's, 533 enabled / 0 restricted, no Z8812; consumer delivered 533 / ack floor 533 / redelivered 0 |
| Members | agree at applied 2395 (+1066: a registration and an enable per delta), 16 trades, book order hash 0, same position hash |

Spend: build VM 7:59–8:14 pm ET, deleted (≈ $0.15). Rollback record (71 objects + DB dump) and the admission
evidence: shared notes `roll3-20261027/`, `admission-40d93f6b-20261027/`.

## 13. Fourth production roll of `traderx`, to `00b31d33` (2026-10-28, 11:05–11:13 am ET)

Approved by yaakov for `00b31d33`, without ADR-087 §4 step 6 (the destructive disk-loss and minority proofs).
It carries:
- the control-stream republish (ADR-085);
- FIX password redaction (FR-ERH-S3);
- `/ready` gated on election CLOSED (FR-GMR-08), and a SIGTERM during the startup election exiting 143;
- FIX fill reports on the durable order stream (ADR-084);
- retained member claims (ADR-087) and persistent FIX sessions (ADR-086);
- recon prices at 6 dp and SQL-refused sides counted as missing (FR-DRS-26, FR-DRS-02);
- a canceled order keeping its fill (ADR-088, `orderbook.filledquantity`);
- the Desk's pre-send refusal label (FR-CUI-19).

It stops before `b044d98b` (ADR-089, an engine change).

From `40d93f6b` it changes no apply-path code. The order-matcher changes are limited to:
- ClusterNodeMain and ClusterGatewayMain;
- FixGatewayAcceptor, FixSessionStore and OrderStatusSource;
- ClusterRecon (the read-only `/recon` replay) and logback.xml.

So the one-member-at-a-time window ran mixed images safely.

| Step | Result |
|---|---|
| Candidate | Built on a VM from an empty render; 14 images, amd64, labelled `00b31d33`. `test_prepare` 35/35, coverage 47/47. `measure-admission --build-records`: admitted, 12 payloads byte-identical, 19 refusal controls pass. Every phase passed a server dry-run. |
| Rehearsal | Side namespace started at production's `40d93f6b` state, then the same `roll4.sh`: ROLL PASS. 17 affected proofs pass on the candidate, including `yu18-fix-resume` step 5 (CumQty 0/4) and `yu05-recon` with a bond cross (field mismatch 0). |
| Rollback record (11:05) | Every Deployment, the StatefulSet, the `database-init-sql` ConfigMap, pods, claims and image IDs. |
| Members, ADR-087 §4 (11:05:52–11:08:35) | Orphan delete, then the StatefulSet with `OnDelete`. Members 0, 1, 2 one at a time, each behind a snapshot barrier (298 → 299 → 300 → 301). Every old pod exited **143**. Each member returned Ready on `data-order-matcher-cluster-N` (Bound, `order-matcher-archive`) at applied 2395 with the same hashes, and its ephemeral claim was gone. The StatefulSet was re-applied unchanged (3/3 at one revision). |
| Database (11:08:44–11:09:34) | `database-init-sql` applied, `eod-price-db` restarted, `schema-migrate` applied 900-migrations. `orderbook.filledquantity` is present and the 20 existing rows are kept. |
| Rest (11:09:34–11:11:43) | Image-only Deployments, trade-processor after the migration. |
| Gateway (11:11:56) | `FIX_SESSION_STORE=jetstream`: `KV_FIX_SESSIONS` and `KV_FIX_SESSION_LEASE` created. Daily reset at 17:00 New York. Fill feed on `TRADERX_FEED`. No FIX session was logged on, so the one-time reset affected none. |
| Console and Desk (11:11:57–11:12:59) | Console replaced with batch custody and the risk image; Desk on `00b31d33`. |
| After | Members agree; book and position hashes unchanged. Applied 2395 → 3461 (+1066, the new gateway's control replay, NFR-CFR-01). SQL trades, positions and orderbook fingerprints unchanged; durable ack floors unchanged. |
| Running images | 17 private containers on exactly the pushed digests, 0 stale. |
| FIX (11:14–11:17) | Wrong and missing password: Logout `58=invalid credentials`. With the pod's password: Sell 1 MSFT @ 380.66 (22214) and Buy (42422) acked, and both fill reports (`39=2`, `14=1`, `31=380.66`) arrived on the session (ADR-084). A resting Buy @ 370 was canceled (`39=4`). OrderStatusRequest: the filled order `39=2 CumQty=1`; the canceled order by OrderID `39=4 CumQty=0` (ADR-088). Read model: `1-21`/`1-22` FILLED `filledquantity` 1, `1-23` CANCELED 0. Console `/fixorder` signed out: 401. |
| Logs | Loki `{namespace="traderx"}` since 10:50 am: 0 lines containing `554=`; 4 `invalid credentials` lines (Loki ships the gateway). |
| Recon | matched 18, missing 0, mismatch 0. |
| Edge | `demo.yaakovseif.dev` and `desk.yaakovseif.dev` 200; `/grafana` → 301 `/grafana/`. The Desk bundle carries the `Refused:` label. |
| Members after FIX | Agree at 3466, same hashes on all three. |

Found on the way: a FIX OrderCancelRequest or OrderStatusRequest without Account (1) uses the session's default
account. The engine refuses the foreign cancel, and the reject reads `order already terminal`
(`CxlRejReason=TOO_LATE_TO_CANCEL`), not an ownership refusal. The OrderStatusRequest answers `unknown order`.
With Account(1) both work.

Spend: build VMs 8:52–9:00 am and 9:46–10:36 am ET, both deleted. Two side rehearsals (pools r4-engine and
r4-support), all deleted, no disks left; about $4 in total. Evidence: shared notes `roll4-20261028/00b31d33/`.

## 13a. `KV_FIX_SESSIONS` set to discard-old on `traderx` (2026-10-28, 1:21 pm ET)

Approved by yaakov. The interim for ADR-086 §6a.5 until a roll ships the per-session ring (`b0bcd313`, FR-GFS-14/15):
roll 4's bucket was discard-new, so at the 1 GiB cap (about 4M stored messages in one FIX day) every gateway would
drop every FIX session at once. One stream config change, nothing else; no pod restarted.

| Step | Result |
|---|---|
| Before | Full stream config saved (rollback: the same config with `discard` new). Discard new, 1 GiB, max age 7 d, one revision per key, R1, file. 4 messages, 856 B. |
| Edit (1:21:22 pm) | `$JS.API.STREAM.UPDATE.KV_FIX_SESSIONS` with the saved config and `discard` old (no `nats` CLI on the NATS image; the same request `nats stream edit --discard old` sends). No error. |
| After | `/jsz` config differs from before in `discard` only. 4 messages, 856 B. Gateway and NATS pods unchanged (no restart). |
| FIX (1:22–1:23 pm) | Logon (141=Y) accepted. A first Buy 1 MSFT @ 300 was refused `39=8 PRICE_COLLAR` (outside the collar, nothing booked). Buy 1 MSFT @ 370 (42422) acked `39=0` (`ord-25`), then canceled `39=4`. The bucket's sequence range moved on and still holds 4 keys after the session (the Logon's reset purged the previous one). |
| Logs | Loki since 12:21 pm (before the edit): gateway 27 lines, 2 FIX logons, 0 `ERROR`, 0 `lease or store lost`. |

A gateway restart keeps the setting: `ensureBucket` creates only a missing bucket.

