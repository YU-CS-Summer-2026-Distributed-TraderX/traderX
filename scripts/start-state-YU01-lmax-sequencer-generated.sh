#!/usr/bin/env bash
set -euo pipefail

# State 009b lifecycle wrapper: the LMAX hot-path overlay (patchset + order-matcher
# overrides) is installed by generation. The runtime harness and compose project are
# shared with the 009 lineage, so this delegates to the 009 lifecycle script (which
# prefers the generated tree's local runtime scripts); state-native scripts replace
# these delegates with T09B22.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${ROOT}/start-state-009-order-management-matcher-generated.sh" "$@"
