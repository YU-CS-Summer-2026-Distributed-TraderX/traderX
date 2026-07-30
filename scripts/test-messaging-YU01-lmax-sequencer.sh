#!/usr/bin/env bash
set -euo pipefail

# State 009b scaffold: messaging subjects/payloads are contract-frozen to 009
# (FR-09B21), so the 009 messaging smoke is the correct gate until the LMAX
# output-bridge implementation lands (task T09B15).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${ROOT}/test-messaging-009-order-management-matcher.sh" "$@"
