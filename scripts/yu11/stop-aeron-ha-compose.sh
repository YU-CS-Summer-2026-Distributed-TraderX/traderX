#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE="${ROOT}/generated/code/target-generated/YU11-aeron-replication/runtime/docker-compose.aeron-ha.yml"
[[ -f "${COMPOSE}" ]] || exit 0
docker compose -f "${COMPOSE}" down
