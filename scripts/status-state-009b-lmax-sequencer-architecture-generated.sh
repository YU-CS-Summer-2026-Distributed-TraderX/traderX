#!/usr/bin/env bash
set -euo pipefail

# State 009b lifecycle wrapper: the runtime (LMAX hot path installed by generation)
# shares the 009 compose project and harness, so this delegates to the 009 lifecycle
# script; state-native scripts replace these delegates with T09B22.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${ROOT}/status-state-009-order-management-matcher-generated.sh" "$@"
