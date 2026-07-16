#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
EXPECTED_STATE="YU09-ops-hardening"
MANIFESTS="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base"
ORDER_MATCHER_DIR="${GENERATED_ROOT}/code/target-generated/order-matcher"

source "${REPO_ROOT}/scripts/lib/generated-state-detection.sh"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  TRADERX_SKIP_LOCKFILE_REFRESH=1 bash "${REPO_ROOT}/pipeline/generate-state.sh" "${EXPECTED_STATE}"
fi

echo "[check] generated output state metadata"
traderx_report_generated_state "${EXPECTED_STATE}" "${GENERATED_ROOT}" >/dev/null || {
  echo "[error] generated output does not match expected state ${EXPECTED_STATE}"
  exit 1
}

echo "[check] no literal credential remains in any touched manifest (FR-OH04, SC-OH03)"
for f in database-deployment.yaml order-matcher-deployment.yaml trade-processor-deployment.yaml \
         account-service-deployment.yaml position-service-deployment.yaml; do
  path="${MANIFESTS}/${f}"
  [[ -f "${path}" ]] || { echo "[error] missing generated manifest: ${path}"; exit 1; }
  if rg -q 'value: "traderx"' "${path}" && rg -q 'MARIADB_USER|DATABASE_DBUSER' "${path}"; then
    # A literal "traderx" value is fine for MARIADB_DATABASE (the schema name, not a credential);
    # only fail if a credential key itself still uses a literal `value:` instead of secretKeyRef.
    rg -A1 'name: (MARIADB_USER|MARIADB_PASSWORD|MARIADB_ROOT_PASSWORD|DATABASE_DBUSER|DATABASE_DBPASS)' "${path}" \
      | rg -q 'value:' && { echo "[error] literal credential value found in ${path}"; exit 1; }
  fi
  rg -q 'name: (MARIADB_USER|DATABASE_DBUSER)' "${path}" && rg -q 'secretKeyRef' "${path}" \
    || { echo "[error] ${path} missing secretKeyRef for DB credentials"; exit 1; }
done

echo "[check] order-matcher/trade-processor JWT secret via secretKeyRef"
rg -q 'name: AUTH_JWT_SECRET' "${MANIFESTS}/order-matcher-deployment.yaml" || { echo "[error] order-matcher-deployment.yaml missing AUTH_JWT_SECRET"; exit 1; }
rg -A2 'name: AUTH_JWT_SECRET' "${MANIFESTS}/order-matcher-deployment.yaml" | rg -q 'secretKeyRef' || { echo "[error] order-matcher AUTH_JWT_SECRET not sourced from secretKeyRef"; exit 1; }
rg -q 'name: AUTH_DEV_TOKEN_MASTER_SECRET' "${MANIFESTS}/trade-processor-deployment.yaml" || { echo "[error] trade-processor-deployment.yaml missing AUTH_DEV_TOKEN_MASTER_SECRET"; exit 1; }

echo "[check] journal archival wiring present (Journaler/JournalArchiver/LmaxEngine)"
for f in \
  "src/main/java/finos/traderx/ordermatcher/lmax/Journaler.java" \
  "src/main/java/finos/traderx/ordermatcher/lmax/JournalArchiver.java" \
  "src/main/java/finos/traderx/ordermatcher/lmax/LmaxEngine.java"; do
  [[ -f "${ORDER_MATCHER_DIR}/${f}" ]] || { echo "[error] missing generated order-matcher file: ${ORDER_MATCHER_DIR}/${f}"; exit 1; }
done
rg -q 'journalArchiveEnabled' "${ORDER_MATCHER_DIR}/src/main/java/finos/traderx/ordermatcher/lmax/LmaxEngine.java" \
  || { echo "[error] LmaxEngine.java missing journal archival wiring"; exit 1; }
rg -q 'software.amazon.awssdk:s3' "${ORDER_MATCHER_DIR}/build.gradle" \
  || { echo "[error] order-matcher build.gradle missing AWS SDK S3 dependency"; exit 1; }

echo "[check] pipeline stale-jar fix present (FR-OH30)"
rg -q 'gradlew --no-daemon clean bootJar' "${REPO_ROOT}/pipeline/publish-generated-state-branch.sh" \
  || { echo "[error] publish-generated-state-branch.sh missing gradlew bootJar step before docker build"; exit 1; }
bash "${REPO_ROOT}/scripts/test-local-jvm-jar-build.sh"
rg -q 'build-jvm-jar\.sh.*context_abs.*name' "${REPO_ROOT}/scripts/start-state-010-kubernetes-runtime-generated.sh" \
  || { echo "[error] local start wrapper missing JVM jar-build guard"; exit 1; }

echo "[check] DR runbook exists"
[[ -f "${REPO_ROOT}/specs/YU09-ops-hardening/system/dr-runbook.md" ]] || { echo "[error] missing system/dr-runbook.md"; exit 1; }

echo "[check] order-matcher unit tests pass"
if command -v java >/dev/null 2>&1; then
  (cd "${ORDER_MATCHER_DIR}" && ./gradlew test -q) || { echo "[error] order-matcher tests failed"; exit 1; }
else
  echo "[warn] java not available in this environment; skipping in-process test run"
fi

echo "[done] YU09-ops-hardening generated-state smoke checks passed"
