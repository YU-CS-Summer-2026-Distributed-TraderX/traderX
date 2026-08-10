#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
EXPECTED_STATE="YU16-cdm-instruments"
STATE_DIR="${GENERATED_ROOT}/code/target-generated/${EXPECTED_STATE}"

source "${REPO_ROOT}/scripts/lib/generated-state-detection.sh"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  TRADERX_SKIP_LOCKFILE_REFRESH=1 bash "${REPO_ROOT}/pipeline/generate-state.sh" "${EXPECTED_STATE}"
fi

echo "[check] generated output state metadata"
traderx_report_generated_state "${EXPECTED_STATE}" "${GENERATED_ROOT}" >/dev/null || {
  echo "[error] generated output does not match ${EXPECTED_STATE}"
  exit 1
}

echo "[check] complete state spec source"
for f in \
  README.md spec.md plan.md research.md data-model.md quickstart.md tasks.md \
  contracts__contract-delta.md requirements__functional-delta.md \
  requirements__nonfunctional-delta.md system__architecture.md \
  system__architecture.model.json system__runtime-topology.md \
  system__messaging-subject-map.md generation__generation-hook.md \
  generation__implementation-status.md; do
  [[ -f "${STATE_DIR}/spec-source/${f}" ]] || {
    echo "[error] missing generated spec source: ${f}"
    exit 1
  }
done

echo "[check] YU12 catalog and wrapper wiring"
rg -q '"id": "YU16-cdm-instruments"' "${REPO_ROOT}/catalog/state-catalog.json"
[[ -x "${GENERATED_ROOT}/code/target-generated/scripts/start-state-YU16-cdm-instruments-generated.sh" ]]
[[ -x "${GENERATED_ROOT}/code/target-generated/scripts/stop-state-YU16-cdm-instruments-generated.sh" ]]
[[ -x "${GENERATED_ROOT}/code/target-generated/scripts/status-state-YU16-cdm-instruments-generated.sh" ]]

echo "[done] YU16-cdm-instruments generated-state scaffold checks passed"
