#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE="${ROOT}/generated/code/target-generated/YU11-aeron-replication/runtime/docker-compose.aeron-ha.yml"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  bash "${ROOT}/pipeline/generate-state.sh" YU11-aeron-replication
fi
[[ -f "${COMPOSE}" ]] || { echo "[fail] missing generated YU11 compose: ${COMPOSE}"; exit 1; }

docker compose -f "${COMPOSE}" up -d --build
echo "[done] YU11 Aeron HA compose started"
echo "[next] bash scripts/yu11/test-aeron-ha-compose.sh"
