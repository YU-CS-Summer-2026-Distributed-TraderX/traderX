#!/usr/bin/env bash
set -euo pipefail

# State 009b scaffold: the LMAX hot-path overlay patchset has not landed yet,
# so the generated runtime is 009-parity (with 009b state identity). Delegate
# to the 009 lifecycle script until 009b-specific runtime wiring exists
# (tasks T09B11..T09B17 in specs/009b-lmax-sequencer-architecture/tasks.md).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${ROOT}/start-state-009-order-management-matcher-generated.sh" "$@"
