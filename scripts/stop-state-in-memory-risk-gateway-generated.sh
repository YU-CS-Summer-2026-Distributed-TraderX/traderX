#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${ROOT}/stop-state-009b-lmax-sequencer-architecture-generated.sh" "$@"
