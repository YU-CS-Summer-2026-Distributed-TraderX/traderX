#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
EXPECTED_STATE="YU04-durable-control-feeds"
OM_SRC="${GENERATED_ROOT}/code/target-generated/order-matcher/src/main/java/finos/traderx/ordermatcher"
AS_SRC="${GENERATED_ROOT}/code/target-generated/account-service/src/main/java/finos/traderx/accountservice"
RD_SRC="${GENERATED_ROOT}/code/target-generated/reference-data/src"

source "${REPO_ROOT}/scripts/lib/generated-state-detection.sh"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  TRADERX_SKIP_LOCKFILE_REFRESH=1 bash "${REPO_ROOT}/pipeline/generate-state.sh" "${EXPECTED_STATE}"
fi

echo "[check] generated output state metadata"
traderx_report_generated_state "${EXPECTED_STATE}" "${GENERATED_ROOT}" >/dev/null || {
  echo "[error] generated output does not match expected state ${EXPECTED_STATE}"
  exit 1
}

# NOTE: the checks below become meaningful once tasks.md T-10..T-34 (the real outbox +
# ControlFeedSubscriber implementation) land. Until then this script only proves the scaffold/
# overlay mechanics work (state metadata present, parent YU03 inherited) — expected to fail the
# checks below at the scaffold stage, per plan.md's sequencing (scaffold first, confirmed via
# `bash pipeline/generate-state.sh YU04-durable-control-feeds`, then real implementation).
if [[ "${TRADERX_YU04_EXPECT_IMPLEMENTATION:-0}" == "1" ]]; then
  echo "[check] order-matcher control-feed subscriber overlay present"
  [[ -f "${OM_SRC}/risk/ControlFeedSubscriber.java" ]] || {
    echo "[error] missing generated class: ${OM_SRC}/risk/ControlFeedSubscriber.java"
    exit 1
  }
  rg -q 'sourceVersion' "${OM_SRC}/risk/GatewayReplicaStore.java" || {
    echo "[error] GatewayReplicaStore is missing the per-source sourceVersion field"
    exit 1
  }
  rg -q 'ControlFeedSubscriber' "${OM_SRC}/risk/ReplicaBootstrap.java" || {
    echo "[error] ReplicaBootstrap does not orchestrate ControlFeedSubscriber"
    exit 1
  }

  echo "[check] account-service outbox overlay present"
  [[ -f "${AS_SRC}/outbox/AccountOutboxPublisher.java" ]] || {
    echo "[error] missing generated class: ${AS_SRC}/outbox/AccountOutboxPublisher.java"
    exit 1
  }

  echo "[check] reference-data outbox overlay present"
  [[ -f "${RD_SRC}/stocks/stocks-outbox-publisher.ts" ]] || {
    echo "[error] missing generated file: ${RD_SRC}/stocks/stocks-outbox-publisher.ts"
    exit 1
  }
fi

echo "[done] YU04-durable-control-feeds generated-state smoke checks passed"
