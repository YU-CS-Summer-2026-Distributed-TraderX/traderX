# Quickstart: YU03-in-memory-risk-gateway

TraderX with a pre-trade risk gateway layered onto the YU02 LMAX BLP: an in-memory, two-tier
admission gate (edge replica + BLP-authoritative state) enforcing account/security status, kill
switch, position/concentration/credit limits, and idempotent order admission — all before an order
reaches the matching engine.

## Local (kind)

### First run

```bash
bash pipeline/generate-state.sh YU03-in-memory-risk-gateway
bash generated/code/target-generated/scripts/start-state-YU03-in-memory-risk-gateway-generated.sh \
  --provider kind --without-sail
```

UI at **http://127.0.0.1:8080**. Same kind-based harness as YU02 (`traderx-state-014` cluster) —
generates state, builds all images, loads them into the cluster, applies manifests, waits for
readiness.

> **If you already have the cluster running from a previous deploy**, use `--recreate-cluster` to
> wipe and rebuild cleanly (same caveat as YU02 — leftover StatefulSet/Deployment conflicts):
> ```bash
> bash generated/code/target-generated/scripts/start-state-YU03-in-memory-risk-gateway-generated.sh \
>   --provider kind --without-sail --recreate-cluster
> ```

### Subsequent runs (skip rebuild if code unchanged)

```bash
bash generated/code/target-generated/scripts/start-state-YU03-in-memory-risk-gateway-generated.sh \
  --provider kind --without-sail --skip-build
```

### Check status

```bash
bash generated/code/target-generated/scripts/status-state-YU03-in-memory-risk-gateway-generated.sh
```

### Validate

```bash
bash scripts/test-state-YU03-in-memory-risk-gateway.sh
```

### Stop

```bash
bash generated/code/target-generated/scripts/stop-state-YU03-in-memory-risk-gateway-generated.sh
# or delete the cluster entirely:
kind delete cluster --name traderx-state-014
```

---

## Exercising the risk gateway

### Control-plane API (`/risk/control/*`)

Every mutation needs `X-Risk-Control-Token` + `X-Risk-Operator` headers. Local default token is
`dev-risk-control` (`risk.control.token`, overridable via `RISK_CONTROL_TOKEN` env var) — **do not
reuse this default outside local/dev use.**

```bash
# Current gateway state
curl -s http://127.0.0.1:8080/order-matcher/risk/control/snapshot \
  -H "X-Risk-Control-Token: dev-risk-control" -H "X-Risk-Operator: local-dev"

# Flip the kill switch
curl -s -X POST http://127.0.0.1:8080/order-matcher/risk/control/policy \
  -H "X-Risk-Control-Token: dev-risk-control" -H "X-Risk-Operator: local-dev" \
  -H "Content-Type: application/json" \
  -d '{"policyVersion": 2, "killSwitch": true}'

# Disable an account
curl -s -X POST http://127.0.0.1:8080/order-matcher/risk/control/account \
  -H "X-Risk-Control-Token: dev-risk-control" -H "X-Risk-Operator: local-dev" \
  -H "Content-Type: application/json" \
  -d '{"accountId": 1, "enabled": false}'
```

Full contract (all 4 mutation endpoints, response shapes, status codes):
`contracts/contract-delta.md` §4. Data/state shapes: `data-model.md`.

### Rejection behavior

A risk-rejected order returns **422** with a stable `RiskRejectionBody`
(`{clientOrderId, decision: "REJECTED", reason, policyVersion, commandSequence}`); a stale/not-ready
control state returns **503** (retryable). `reason` is one of the stable `RiskReason` codes listed
in `data-model.md` (`CREDIT_LIMIT`, `POSITION_LIMIT`, `KILL_SWITCH`, `RESTRICTED`, etc.) — useful for
scripting a quick smoke test that an account-disable or kill-switch flip actually blocks orders.

---

## GKE (staging only — not production)

YU03 has its own isolated staging deploy, separate from the `YU02` production cluster, so this
never touches the live site:

```bash
# One-time: provision the risk-control token as a k8s secret in the staging namespace
bash scripts/provision-yu03-staging-secret.sh
```

Staging manifests: `cluster-addons/yu03-staging/`. Requires explicit go-ahead before touching any
live CI/CD resource — see repo conventions (`CLAUDE.md`) before setting up a trigger.

---

## Runtime notes

- **Two-tier design**: `GatewayReplicaStore` (edge, concurrent-read, does preliminary `screen()`
  validation) + `BlpRiskState` (BLP-thread-only, authoritative — preallocated, no heap churn on the
  hot path). The replica can lag; the BLP state is what actually admits/rejects.
- **Limits today are global scalars, not per-account** (`maxPositionQuantity`,
  `maxConcentrationNotionalTicks`, `creditLimitTicks`, `maxOrderQuantity`/`maxOrderNotionalTicks`).
  The only per-account lever is the `enabled` flag on `/risk/control/account`. See `onboarding.md`
  (repo root, untracked) for the implications if you're integrating an external risk engine that
  computes per-account limits.
- **Idempotency**: optional `clientOrderId` on `POST /orders`/`POST /trades`, hashed at the edge
  (FNV-1a). A duplicate key replays the original decision rather than double-booking.
- **Snapshot v3**: risk state (policy, accounts, securities, idempotency ring) persists across BLP
  restarts; older v1/v2 snapshots still load (risk sections start from seeds + journal tail).
- Everything from YU02's runtime notes (MariaDB read-model, journal/snapshot recovery, kind image
  reload) still applies unchanged — see `specs/YU02-lmax-kubernetes/quickstart.md`.
