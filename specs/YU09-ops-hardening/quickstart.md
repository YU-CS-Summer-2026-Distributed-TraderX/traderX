# Quickstart: YU09-ops-hardening

## Local (kind)

### One-time credential setup (before any pod using them can start)

`database`, `order-matcher`, `trade-processor`, `account-service`, and `position-service` all
require `mariadb-credentials` and (`order-matcher`/`trade-processor` only) `auth-secrets` to exist
before they reach Ready — neither Secret is `optional`, so a missing one is a visible
`CreateContainerConfigError`, not a silent fallback. Create both once per kind cluster (a
`--recreate-cluster` run wipes the cluster's Secrets along with everything else, so re-run this
after any recreate):

```bash
kubectl create namespace traderx --dry-run=client -o yaml | kubectl apply -f -

# Local kind values only — these are the same dev-defaults the code already shipped with, just no
# longer literal in a committed manifest. Use real, non-default values for a GKE deploy (below).
kubectl create secret generic mariadb-credentials -n traderx \
  --from-literal=username=traderx \
  --from-literal=password=traderx \
  --from-literal=root-password=traderx

kubectl create secret generic auth-secrets -n traderx \
  --from-literal=jwt-secret=dev-jwt-shared-secret \
  --from-literal=dev-token-master-secret=dev-token-master-secret
```

`order-matcher-journal-gcs-hmac` is optional — skip it locally unless you want to exercise journal
archival end-to-end (see below).

### First run

```bash
bash pipeline/generate-state.sh YU09-ops-hardening
bash generated/code/target-generated/scripts/start-state-YU09-ops-hardening-generated.sh \
  --provider kind --without-sail
```

UI at **http://127.0.0.1:8080**. This inherits the `YU08-execution-algo-engine` kind runtime
unchanged; the only new component in this state is the two Secrets above and the code changes to
existing services (no new Deployment).

### Journal archival end-to-end (optional)

Journal archival is off by default (`journal.archive.enabled=false`). To exercise rotation
locally without real GCS creds, set `ORDER_MATCHER_JOURNAL_ARCHIVE_ENABLED=true` and a short
`SNAPSHOT_INTERVAL_MS` on the `order-matcher` Deployment, then watch for a new
`input-events-<epoch-millis>.journal` file appear in the pod's journal volume:

```bash
kubectl exec -n traderx deploy/order-matcher -- ls -la /var/lib/traderx-lmax/journal
```

Without `order-matcher-journal-gcs-hmac`, rotation still happens (bounding local disk) but the
GCS upload leg logs a warning and leaves each segment on disk — check
`kubectl logs deploy/order-matcher -n traderx` for that warning if segments aren't disappearing.

To exercise the actual GCS upload, create the key once via Cloud Storage → Settings →
Interoperability → Service account HMAC in the console, then create the Secret **in your own
terminal** (the secret value should never be pasted into a chat/tool log):

```bash
kubectl create secret generic order-matcher-journal-gcs-hmac -n traderx \
  --from-literal=access-key-id=<ACCESS_ID> \
  --from-literal=secret-access-key=<SECRET>
```

### Subsequent runs (skip rebuild if code unchanged)

```bash
bash generated/code/target-generated/scripts/start-state-YU09-ops-hardening-generated.sh \
  --provider kind --without-sail --skip-build
```

### Validate

```bash
bash scripts/test-state-YU09-ops-hardening.sh
```

## GKE

Create `mariadb-credentials` and `auth-secrets` with real, non-default values before deploying —
the production StatefulSet (`cluster-addons/order-matcher-statefulset.yaml`) and every other
production Deployment now require them the same way the kind manifests do:

```bash
kubectl create secret generic mariadb-credentials -n traderx \
  --from-literal=username=<REAL_DB_USER> \
  --from-literal=password=<REAL_DB_PASSWORD> \
  --from-literal=root-password=<REAL_DB_ROOT_PASSWORD>

kubectl create secret generic auth-secrets -n traderx \
  --from-literal=jwt-secret=<REAL_JWT_SECRET> \
  --from-literal=dev-token-master-secret=<REAL_DEV_TOKEN_SECRET>
```

Deploy as before (`bash scripts/deploy-state-YU02-lmax-kubernetes-gke.sh ...` +
`kubectl apply -f cluster-addons/`) — see root `CLAUDE.md`.
