#!/usr/bin/env bash
set -euo pipefail

# Creates/rotates the risk-control admin token for the YU03 staging namespace. Run once before
# the first deploy (or any time you want to rotate it) — the token itself is never committed to
# git; Cloud Deploy's rendered order-matcher.yaml references it via secretKeyRef.
NAMESPACE="traderx-yu03-staging"
SECRET_NAME="yu03-staging-risk-control"
CLUSTER="traderx-lmax"
LOCATION="us-east1-b"
PROJECT="traderx-501015"

TOKEN="${1:-$(openssl rand -hex 24)}"

gcloud container clusters get-credentials "${CLUSTER}" --location="${LOCATION}" --project="${PROJECT}"

kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic "${SECRET_NAME}" \
  --namespace="${NAMESPACE}" \
  --from-literal=token="${TOKEN}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "[ok] provisioned ${SECRET_NAME} in ${NAMESPACE}"
echo "[info] token (save this if you need it for /risk/control/* admin calls): ${TOKEN}"
