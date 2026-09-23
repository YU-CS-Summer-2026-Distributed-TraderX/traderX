#!/usr/bin/env bash
# Compatibility wrapper; canonical state is YU18-risk-integration.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec bash "${ROOT}/pipeline/generate-state-YU18-risk-integration.sh" "$@"
