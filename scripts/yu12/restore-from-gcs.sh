#!/usr/bin/env bash
# YU12 disaster recovery: rebuild the cluster from the latest GCS snapshot backup after a
# whole-cluster loss. Member-0's restore initContainer seeds /data from gs://.../yu12-cluster-
# snapshots/latest.tgz; members 1 & 2 come up empty and rejoin member-0 via catch-up.
#
# Usage: bash scripts/yu12/restore-from-gcs.sh
set -uo pipefail
CTX="${KUBE_CTX:-gke_traderx-501015_us-east1-b_traderx-lmax}"; NS="${NAMESPACE:-traderx}"
STS=order-matcher-cluster
k(){ kubectl --context "$CTX" -n "$NS" "$@"; }

echo "1/5  scale members to 0 (wipes emptyDir — do this only for DR, state is gone until restored)"
k scale statefulset/$STS --replicas=0
k wait --for=delete pod/$STS-0 pod/$STS-1 pod/$STS-2 --timeout=180s 2>/dev/null || true

echo "2/5  arm restore: RESTORE_FROM_GCS=1 on the StatefulSet"
k set env statefulset/$STS RESTORE_FROM_GCS=1 --containers=restore-from-gcs

echo "3/5  scale to 3 — member-0 pulls latest.tgz + extracts; 1 & 2 rejoin fresh"
k scale statefulset/$STS --replicas=3
k rollout status statefulset/$STS --timeout=420s || true

echo "4/5  wait for a leader + convergence"
for t in $(seq 1 60); do
  roles=$(for i in 0 1 2; do k get --raw "/api/v1/namespaces/$NS/pods/$STS-$i:8080/proxy/health" 2>/dev/null | sed -n 's/.*"role":"\([A-Z]*\)".*/\1 /p'; done | tr -d '\n')
  echo "   roles: $roles"
  [[ "$roles" == *LEADER* ]] && break; sleep 5
done
for i in 0 1 2; do echo "   m$i: $(k get --raw "/api/v1/namespaces/$NS/pods/$STS-$i:8080/proxy/health" 2>/dev/null)"; done

echo "5/5  DISARM restore so future restarts don't re-seed from GCS"
k set env statefulset/$STS RESTORE_FROM_GCS=0 --containers=restore-from-gcs
echo "done. Verify trades/applied above match the last backup. Re-pin consensus timeouts if needed:"
echo "  k set env statefulset/$STS CLUSTER_HEARTBEAT_INTERVAL_MS=50 CLUSTER_HEARTBEAT_TIMEOUT_MS=200 CLUSTER_ELECTION_TIMEOUT_MS=100 CLUSTER_ELECTION_STATUS_INTERVAL_MS=25"
