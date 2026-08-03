#!/usr/bin/env bash
set -euo pipefail

# State 009b scaffold: until the LMAX hot-path patchset lands, generated 009b
# output is 009-parity, so the 009 smoke suite is the correct functional gate
# (FR-09B40 contract preservation). 009b-specific checks (no-GC gate,
# determinism replay, recovery, decoupling — see tests/smoke/README.md in the
# 009b pack) are added with tasks T09B18..T09B21.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${ROOT}/test-state-009-order-management-matcher.sh" "$@"
