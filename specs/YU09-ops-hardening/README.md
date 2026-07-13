# Feature Pack: YU09-ops-hardening

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Implemented
Track: `architecture`
Lineage role: `optional`
Previous state: `YU08-execution-algo-engine`

This pack closes four operational gaps found while hardening every prior state (YU03–YU08) for
E2E health and throughput on top of the `YU08-execution-algo-engine` baseline: plaintext
credentials in committed manifests, unbounded journal growth on the order-matcher PVC, a Docker
build step that could silently deploy a stale jar, and no documented recovery procedure for the
cluster's actual (single-zone) failure modes.

Primary intent:

- move every database and JWT/dev-token credential out of committed manifests and into Kubernetes
  Secrets, created out-of-band and never committed — the same pattern YU07 already established for
  its GCS HMAC credential,
- rotate the order-matcher journal at each snapshot boundary and archive closed segments to GCS,
  off the journaler thread, gated behind a flag that defaults to the original unbounded-file
  behavior,
- make the shared build pipeline always rebuild a JVM service's jar before building its Docker
  image, closing the stale-jar Docker-layer-cache bug found deploying YU08,
- document the cluster's real failure modes (node, zone, database, journal loss) and their
  recovery procedures given its current single-zone topology.

Core artifacts:

- `spec.md`
- `requirements/functional-delta.md`
- `requirements/nonfunctional-delta.md`
- `research.md`
- `data-model.md`
- `quickstart.md`
- `contracts/contract-delta.md`
- `system/architecture.model.json`
- `system/architecture.md`
- `system/runtime-topology.md`
- `system/messaging-subject-map.md`
- `system/dr-runbook.md`
- `system/adr-032-journal-rotation-and-gcs-archival.md`
- `system/adr-033-secrets-via-out-of-band-kubectl-secrets.md`
- `generation/generation-hook.md`
- `generation/implementation-status.md`

Target runtime behavior:

- `database`, `order-matcher`, `trade-processor`, `account-service`, and `position-service` pods
  read their database credentials from the `mariadb-credentials` Secret; `order-matcher` and
  `trade-processor` read JWT/dev-token secrets from `auth-secrets`. Neither Secret is committed.
- `order-matcher`, when `journal.archive.enabled=true`, rotates its journal file at every snapshot
  and uploads the closed segment to GCS via the optional `order-matcher-journal-gcs-hmac` Secret;
  by default this is off and the journal behaves exactly as in every prior state.
- `pipeline/publish-generated-state-branch.sh` rebuilds a fresh jar before every JVM service's
  Docker build.
- Everything else (deploy/runtime harness, observability stack, every existing service) is
  inherited unchanged from `YU08-execution-algo-engine`.
