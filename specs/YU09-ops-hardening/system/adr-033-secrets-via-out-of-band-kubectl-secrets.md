# ADR-033: Database and JWT Credentials via Out-of-Band Kubernetes Secrets

**Status:** Accepted
**Date:** 2026-07-12
**State:** `YU09-ops-hardening` (parent `YU08-execution-algo-engine`)

## Context

`database-deployment.yaml` hardcodes `MARIADB_USER`/`MARIADB_PASSWORD`/`MARIADB_ROOT_PASSWORD` as
literal values, identical across every environment and committed to git. Every DB-consuming
service's Deployment hardcodes the matching `DATABASE_DBUSER`/`DATABASE_DBPASS`. Separately,
`order-matcher` and `trade-processor` already read `AUTH_JWT_SECRET`/
`AUTH_DEV_TOKEN_MASTER_SECRET` from the environment with a dev-default fallback
(`application.properties`), but no manifest ever sets them — every deployment silently runs on the
code's dev-default. YU07 already solved the equivalent problem for `tick-store`'s GCS credential:
a `kubectl create secret generic` step run once, out-of-band, referenced via `secretKeyRef`, never
committed.

## Decision

Two new Secrets, both in the `traderx` namespace, both created out-of-band exactly like YU07's
`tick-store-gcs-hmac` and documented the same way in `quickstart.md`:

- `mariadb-credentials` (`username`, `password`, `root-password`) — referenced via `secretKeyRef`
  from `database-deployment.yaml` and every DB-consuming service's Deployment, including the
  production `cluster-addons/order-matcher-statefulset.yaml`.
- `auth-secrets` (`jwt-secret`, `dev-token-master-secret`) — referenced from `order-matcher` and
  `trade-processor`'s Deployments (and the production StatefulSet) for `AUTH_JWT_SECRET`; only
  `trade-processor` also needs `dev-token-master-secret`.

Neither Secret has `optional: true` — a pod that needs database or auth credentials to function
correctly should not silently start without them; if the Secret is missing, `CreateContainerConfigError`
is the correct, visible failure, the same as any other required config. This is a deliberate
change in local-kind behavior: previously every pod could start with the literal dev-default
regardless of any Secret; now `run-state-kind` and the generated start-state script must create
both Secrets before pods can reach Ready (see `quickstart.md`).

## Consequences

- No plaintext credential remains in any committed manifest — `git log`/`git blame` on any
  manifest can never expose a working credential.
- Local kind bring-up now has a one-time manual (or scripted) prerequisite step, same shape as
  YU07's GCS credential — accepted as the cost of removing the plaintext, consistent with the
  existing precedent rather than a new tradeoff.
- Rotating a credential (DB password, JWT secret) is now a `kubectl create secret ... --dry-run=client
  -o yaml | kubectl apply -f -` plus a pod restart, not a git commit — an improvement, since a
  credential rotation no longer needs to touch source control at all.
