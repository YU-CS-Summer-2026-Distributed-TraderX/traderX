# Disaster Recovery Runbook: YU09-ops-hardening

Failure modes and manual recovery procedures for the `traderx-lmax` GKE cluster, as the cluster
actually exists today (single-zone, single-cluster, single-region).

## Topology as deployed

- One GKE cluster, one zone (`us-east1-b`), two node pools (`default-pool` 3× e2-standard-2,
  `blp-pool` 1× c2-standard-4).
- `order-matcher` runs as a StatefulSet with a per-pod `ReadWriteOnce` PVC
  (`lmax-runtime-data-order-matcher-N`) holding the journal and periodic snapshot.
- MariaDB runs as a single Deployment with an `emptyDir` (kind) or PVC (GKE) volume — one replica,
  no read replica, no cross-zone standby.
- Static IP + DNS (`yaakovseif.dev`) point at the one ingress in the one cluster.

## Failure mode: order-matcher pod loss (node reschedule, container crash)

**Blast radius**: one BLP pod. In single-BLP mode (current production default,
`BLP_REPLICATION_ENABLED=false`), this is the only order-matcher pod — order acceptance halts
until it recovers. In HA mode (`replicas: 2`, `BLP_REPLICATION_ENABLED=true`), the FOLLOWER
promotes via Kubernetes Lease within one lease duration (≤15s, see `LMAX-BLP-FAILOVER.md`).

**Recovery**: automatic. The StatefulSet reschedules the pod onto the same PVC; `RECOVERY_SOURCE`
(`journal` in production, `db` in kind) replays snapshot + journal tail before the pod reports
ready. No operator action required — confirm with
`kubectl get pods -n traderx -l app=order-matcher` and check `/actuator/health/readiness`.

## Failure mode: node loss (`blp-pool` or `default-pool`)

**Blast radius**: every pod scheduled on that node re-schedules onto a surviving node in the same
pool (or `default-pool` if `blp-pool`'s single node is down and no taint-tolerant capacity exists
— in that case the BLP has nowhere to reschedule to until the node returns or the pool is resized).

**Recovery**: `kubectl drain <node> --ignore-daemonsets --delete-emptydir-data` for a planned
replacement; for an unplanned node loss, Kubernetes reschedules automatically once the node is
marked `NotReady` and its pods evicted (default ~5 minute grace period). `blp-pool` currently has
one node, so a BLP node loss has no same-pool fallback capacity —
`gcloud container node-pools resize traderx-lmax --node-pool blp-pool --num-nodes 2 --zone
us-east1-b` provisions a second node the pod can land on, and re-adds HA capacity if
`BLP_REPLICATION_ENABLED=true`.

## Failure mode: zone loss

**Blast radius**: total. `traderx-lmax` has one zone (`us-east1-b`); GKE cannot reschedule
anything within the cluster if that zone is unavailable — both node pools, every pod, and both
PVCs are gone until the zone recovers.

**Recovery**: none within the current cluster. The only path back is provisioning a new cluster in
a different zone/region and redeploying from git (`bash
scripts/deploy-state-YU02-lmax-kubernetes-gke.sh` + `kubectl apply -f cluster-addons/` against the
new cluster context), then re-pointing the static IP / DNS A record at the new ingress. State
recovery on the new cluster starts from whatever was last archived off-box — the MariaDB read
model is not backed up anywhere off-cluster, and the order-matcher journal/snapshot on the lost
PVC is unrecoverable unless journal archival (see `spec.md` FR-OH20) had already uploaded a
segment to GCS before the zone went down; anything since the last uploaded segment is lost.

## Failure mode: MariaDB pod/data loss

**Blast radius**: the async read model (account/position/trade history views). The BLP itself
(order matching, the source of truth for order state) is unaffected — MariaDB is a downstream
projection, not authoritative (see root `CLAUDE.md`, "order-matcher IS the BLP").

**Recovery**: no automated backup exists. Redeploying the `database` Deployment starts from an
empty schema (`database-init-sql` ConfigMap re-runs). Re-seeding the read model from the BLP's own
state requires a full replay: set `RECOVERY_SOURCE=journal` (already the production default) so
the BLP reconstructs from snapshot+journal on its own PVC, independent of MariaDB, then re-enable
`OUTPUT_PROJECTOR_DB_ENABLED` so the projector starts writing to the fresh database — the BLP's
in-memory read model is not automatically backfilled into MariaDB by this, only new events from
that point forward.

## Recovery Point / Recovery Time, as actually observed

These are measured from the failover and node-replacement tests already run against this cluster
(root `CLAUDE.md`, "Remaining" / HA sections), not targets:

- BLP failover (HA mode, PRIMARY pod killed): ~25s, state intact, no split-brain.
- Node replacement (same zone): pod reschedule + journal replay time, dominated by journal replay
  duration since the last snapshot (bounded by `SNAPSHOT_INTERVAL_MS`, 5 minutes in production).
- Zone loss: unrecoverable without manual multi-cluster provisioning — no measured RTO, since no
  automated path exists.
