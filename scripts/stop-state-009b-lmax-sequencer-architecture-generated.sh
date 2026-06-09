#!/usr/bin/env bash
set -euo pipefail

# State 009b scaffold: delegates to the 009 lifecycle script until the LMAX
# hot-path patchset lands (see specs/009b-lmax-sequencer-architecture/tasks.md).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${ROOT}/stop-state-009-order-management-matcher-generated.sh" "$@"
