#!/usr/bin/env bash
set -euo pipefail

# Creates/rotates the secrets the YU03 staging namespace needs before its first deploy — the
# risk-control admin token AND the mariadb-credentials the database/order-matcher manifests now
# source via secretKeyRef (no literal DB credential lives in a committed staging manifest, same as
# the production deploy path — FR-OH04). No secret value is committed to git.
NAMESPACE="traderx-yu03-staging"
SECRET_NAME="yu03-staging-risk-control"
CLUSTER="traderx-lmax"
LOCATION="us-east1-b"
PROJECT="traderx-501015"

TOKEN="${1:-$(openssl rand -hex 24)}"
# Staging is an ephemeral, isolated sandbox — dev-default DB creds are fine; override via env for
# a locked-down staging run.
DB_USER="${DB_USER:-traderx}"
DB_PASSWORD="${DB_PASSWORD:-traderx}"
DB_ROOT_PASSWORD="${DB_ROOT_PASSWORD:-traderx}"

gcloud container clusters get-credentials "${CLUSTER}" --location="${LOCATION}" --project="${PROJECT}"

kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic "${SECRET_NAME}" \
  --namespace="${NAMESPACE}" \
  --from-literal=token="${TOKEN}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic mariadb-credentials \
  --namespace="${NAMESPACE}" \
  --from-literal=username="${DB_USER}" \
  --from-literal=password="${DB_PASSWORD}" \
  --from-literal=root-password="${DB_ROOT_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "[ok] provisioned ${SECRET_NAME} + mariadb-credentials in ${NAMESPACE}"
echo "[info] token (save this if you need it for /risk/control/* admin calls): ${TOKEN}"
