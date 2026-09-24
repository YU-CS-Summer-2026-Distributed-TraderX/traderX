#!/usr/bin/env bash
# RI-07: the trade-to-risk flow the CLOSED RI-03 profile actually supports, end to end, locally.
#
#   sequenced TraderX engine (in-process, SharedEodExamplesTest) -> production CSV/cut/receipt
#   exporters -> bundle -> durable coordinator -> accepted engine container (/eod/price) ->
#   validated intake -> read-only Risk status
#
# plus the negative case a demo must show: a bundle that is NOT the one reviewed synthetic bill
# export is refused before staging, so arbitrary trades (including every trade booked on a live
# rig) receive NO risk result under this profile. Nothing is re-entered or substituted.
#
# Scope labels: the inputs are SYNTHETIC; the curve is the explicitly ASSUMED flat-3pct-v1; prices
# come from the accepted engine image, compared independently only in ri03-acceptance.py.
# usableForRisk and portfolioRiskAvailable stay false. This is not financial validation.
#
# Needs: Java 21 + cached Gradle deps (generated YU18 tree), Docker, the accepted engine image
# (sha256:88ad80a6...), the external RI-02 runner, and a Python with jsonschema 4.26.0 first on PATH
# (pip install -r specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles/requirements-container.txt).
#
# Usage: bash scripts/ri07/risk-flow-local.sh <new-private-dir> [port]
# Creates ONE container named traderx-ri07-ri03 and removes it on exit (only that name).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN="${1:?usage: risk-flow-local.sh <new-private-dir> [port]}"
PORT="${2:-28303}"
IMAGE="${RI03_IMAGE:-sha256:88ad80a618e51ecafaf0a0e6bd8e00f55bf661f2c3d3941ed39feae64cb93877}"
RUNNER="${RI02_RUNNER:-/Users/yaakov/dev/jax_risk_engine/JAX_Risk_Engine-containerization/container/run.py}"
NAME=traderx-ri07-ri03
COMPONENT="${ROOT}/specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles"

[[ ! -e "${RUN}" ]] || { echo "[refused] ${RUN} exists; use a new directory"; exit 2; }
python3 -c 'import jsonschema' 2>/dev/null || { echo "[refused] python3 on PATH lacks jsonschema (see header)"; exit 2; }
docker image inspect "${IMAGE}" >/dev/null 2>&1 || { echo "[refused] engine image ${IMAGE} not in the local daemon"; exit 2; }
[[ -f "${RUNNER}" ]] || { echo "[refused] RI-02 runner not found at ${RUNNER}"; exit 2; }
if docker inspect "${NAME}" >/dev/null 2>&1; then echo "[refused] a container named ${NAME} already exists; not touching it"; exit 2; fi

umask 077
mkdir -p "${RUN}"
echo "[1/5] fresh exporter run (in-process sequenced engine, production exporters)"
out="$(bash "${ROOT}/scripts/demo-state-YU18-shared-examples.sh")"
printf '%s\n' "${out}" > "${RUN}/shared-examples.txt"
EXPORTS="$(printf '%s\n' "${out}" | sed -n 's|.*\(/private/tmp/traderx-shared-examples\.[A-Za-z0-9]*\).*|\1|p' | head -1)/shared"
[[ -d "${EXPORTS}" ]] || { echo "[FAIL] could not find the exporter's shared directory in: ${out}"; exit 1; }
echo "      exports: ${EXPORTS}"

echo "[2/5] start the accepted engine container ${NAME} on 127.0.0.1:${PORT}"
mkdir -m 0755 "${RUN}/staging" "${RUN}/engine-store"
docker run --rm --network none --user 0 --entrypoint chown -v "${RUN}/engine-store:/store" "${IMAGE}" 10001:10001 /store
cleanup() { docker logs "${NAME}" > "${RUN}/container.log" 2>&1 || true; docker rm -f "${NAME}" >/dev/null 2>&1 || true; }
trap cleanup EXIT
python3 "${RUNNER}" --image "${IMAGE}" --name "${NAME}" --inputs "${RUN}/staging" --store "${RUN}/engine-store" --port "${PORT}" >/dev/null
for _ in $(seq 1 120); do
  [[ "$(docker inspect -f '{{.State.Health.Status}}' "${NAME}" 2>/dev/null)" == healthy ]] && break; sleep 1
done
[[ "$(docker inspect -f '{{.State.Health.Status}}' "${NAME}")" == healthy ]] || { echo "[FAIL] container not healthy"; exit 1; }

echo "[3/5] supported path: the reviewed synthetic bill export through coordinator -> container -> intake"
python3 "${ROOT}/scripts/ri03-acceptance.py" --root "${RUN}" --exports "${EXPORTS}" \
  --container "${NAME}" --endpoint "http://127.0.0.1:${PORT}" | tee "${RUN}/acceptance.txt"

echo "[4/5] profile restriction: any other bundle is refused before staging (no engine request)"
mkdir "${RUN}/other-inbox"
python3 "${COMPONENT}/bundle.py" build --positions "${COMPONENT}/tests/fixtures/exchange/positions.csv" \
  --contracts "${COMPONENT}/tests/fixtures/exchange/contracts.csv" --epoch ri07-not-the-profile \
  --valuation-time 2025-06-02T16:00:00-04:00 --origin synthetic --output "${RUN}/other-inbox/cut" >/dev/null
staged_before="$(ls "${RUN}/staging" | wc -l | tr -d ' ')"
set +e
python3 "${ROOT}/scripts/ri03-pipeline.py" --state "${RUN}/other-state" --staging "${RUN}/staging" \
  --endpoint "http://127.0.0.1:${PORT}" --inbox "${RUN}/other-inbox" > "${RUN}/other-bundle.json" 2> "${RUN}/other-bundle.err"
rc=$?
set -e
staged_after="$(ls "${RUN}/staging" | wc -l | tr -d ' ')"
python3 - "${RUN}/other-bundle.json" "${rc}" "${staged_before}" "${staged_after}" <<'PY'
import json, sys
path, rc, before, after = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
jobs = json.load(open(path))["jobs"]
assert rc == 1, f"expected the non-profile job to fail (exit 1), got {rc}"
assert len(jobs) == 1 and jobs[0]["status"] == "FAILED", jobs
assert before == after, f"staging changed ({before} -> {after}): something was sent toward the engine"
print(f"      refused: job {jobs[0]['status']} ({jobs[0]['attempts'][0].get('errorCode')}), staging entries unchanged ({after})")
PY
# The job status only says "The worker attempt failed." -- so name the mechanism directly rather
# than inferring it from FAILED: the adapter's admission check must refuse this exact bundle.
python3 - "${COMPONENT}" "${RUN}/other-inbox/cut" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
import container_adapter
try:
    container_adapter.inputs(sys.argv[2])
except ValueError as e:
    assert "unsupported input scope" in str(e), e
    print(f"      mechanism: {e}")
else:
    raise SystemExit("[FAIL] the adapter ADMITTED a non-profile bundle")
PY

echo "[5/5] read-only status (what the console Risk page reads)"
python3 "${ROOT}/scripts/ri03-pipeline.py" --state "${RUN}/state" --staging "${RUN}/staging" --status-only > "${RUN}/status.json"
python3 - "${RUN}/status.json" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))
for j in s["jobs"]:
    print(f"      job {j['status']} integrity={j['resultIntegrity']} usableForRisk={j.get('usableForRisk')}")
PY
echo "[ok] supported flow and profile refusal shown; evidence in ${RUN}. Synthetic inputs, assumed curve; not financial validation."
