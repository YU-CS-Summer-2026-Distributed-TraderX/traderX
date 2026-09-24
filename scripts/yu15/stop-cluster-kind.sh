#!/usr/bin/env bash
set -euo pipefail
# Deletes the WHOLE kind cluster and every volume on it. Name it explicitly when more than one
# tier exists: the default is the historical shared rig, which may be a retained one.
CLUSTER="${KIND_CLUSTER_NAME:-traderx-yu12-cluster}"
echo "[delete] kind cluster ${CLUSTER}"
kind delete cluster --name "${CLUSTER}"
