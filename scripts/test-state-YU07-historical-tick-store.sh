#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
EXPECTED_STATE="YU07-historical-tick-store"
TICK_STORE_DIR="${GENERATED_ROOT}/code/target-generated/tick-store"
KUST="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/kustomization.yaml"

source "${REPO_ROOT}/scripts/lib/generated-state-detection.sh"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  TRADERX_SKIP_LOCKFILE_REFRESH=1 bash "${REPO_ROOT}/pipeline/generate-state.sh" "${EXPECTED_STATE}"
fi

echo "[check] generated output state metadata"
traderx_report_generated_state "${EXPECTED_STATE}" "${GENERATED_ROOT}" >/dev/null || {
  echo "[error] generated output does not match expected state ${EXPECTED_STATE}"
  exit 1
}

echo "[check] tick-store component present"
for f in \
  "capture.py" \
  "ingest_taq_quotes.py" \
  "gcs.py" \
  "duckdb_query_examples.sql" \
  "requirements.txt" \
  "Dockerfile" \
  "tests/test_capture.py" \
  "tests/test_ingest_taq_quotes.py" \
  "tests/test_gcs.py"; do
  [[ -f "${TICK_STORE_DIR}/${f}" ]] || { echo "[error] missing generated tick-store file: ${TICK_STORE_DIR}/${f}"; exit 1; }
done

# Shared-file no-clobber verification (research.md / generation-hook.md): kustomization.yaml is
# the one file overridden by every ancestor through YU06 AND by YU07 -- every ancestor's resource
# entry must survive alongside YU07's two additions, or generation silently clobbered them.
echo "[check] kustomization.yaml: ancestor entries AND YU07 entries both survive"
for marker in \
  'eod-session-close-cronjob.yaml' \
  'order-matcher-lmax-data-pvc.yaml' \
  'price-publisher-deployment.yaml' \
  'tick-store-deployment.yaml'; do
  rg -q "${marker}" "${KUST}" || { echo "[error] kustomization.yaml missing (clobber?): ${marker}"; exit 1; }
done

echo "[check] tick-store deployment wired for GCS (no local PVC, research.md Decision 6)"
DEPLOY="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/tick-store-deployment.yaml"
rg -q 'gs://traderx-501015-tick-store' "${DEPLOY}" || { echo "[error] tick-store-deployment.yaml missing gs:// out dir"; exit 1; }
rg -q 'tick-store-gcs-hmac' "${DEPLOY}" || { echo "[error] tick-store-deployment.yaml missing GCS HMAC secretKeyRef"; exit 1; }
[[ ! -f "${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/tick-store-data-pvc.yaml" ]] || {
  echo "[error] tick-store-data-pvc.yaml still generated -- should have been removed (GCS supersedes local PVC)"; exit 1;
}

echo "[check] tick-store unit tests pass"
if command -v python3 >/dev/null 2>&1 && python3 -c "import duckdb" >/dev/null 2>&1; then
  (cd "${TICK_STORE_DIR}" && python3 -m pytest tests/ -q) || { echo "[error] tick-store tests failed"; exit 1; }
elif [[ -n "${CI:-}" ]]; then
  # A skip is a reasonable courtesy on a laptop without duckdb. In CI it is a vacuous pass: the
  # check reports success having executed nothing, which is indistinguishable from the tests
  # actually passing. The composed-extras workflow job installs requirements.txt and runs pytest
  # directly, so there is no excuse for the interpreter to be unequipped here.
  echo "[error] duckdb not importable under CI; the tick-store suite must run, not be skipped"
  exit 1
else
  echo "[warn] duckdb not importable in this environment; skipping in-process test run (see quickstart.md self-check)"
fi

echo "[done] YU07-historical-tick-store generated-state smoke checks passed"
