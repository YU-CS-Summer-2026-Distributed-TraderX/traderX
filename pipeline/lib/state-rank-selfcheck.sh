#!/usr/bin/env bash
# Self-check for pipeline/lib/state-rank.sh.
#
# Two jobs. First, pin the ranks so a future edit to the walk cannot quietly re-rank the lineage.
# Second, assert the property the consolidation rests on: every state in the catalog resolves to a
# rank, so no state can slip past a threshold check by being unrankable. That is the failure mode
# the old parsers had -- an unrecognised id shape meant the guarded block was skipped and the
# script still exited 0.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CATALOG="${ROOT}/catalog/state-catalog.json"

# shellcheck source=state-rank.sh
source "${ROOT}/pipeline/lib/state-rank.sh"

failures=0

expect() {
  local id="$1" want="$2" got
  got="$(traderx_state_rank "${CATALOG}" "${id}")" || got="<unranked>"
  if [[ "${got}" != "${want}" ]]; then
    echo "[fail] rank(${id}): want ${want}, got ${got}"
    failures=$((failures + 1))
  fi
}

# Numbered states rank as themselves; letter-suffixed siblings as their numeric base.
expect "001-baseline-uncontainerized-parity" "1"
expect "009-order-management-matcher" "9"
expect "014-fdc3-intent-interoperability" "14"

# YU01 forks off 009, so it ranks 9 -- NOT above the numbered lineage. This is the case that
# distinguishes the walk from the retired 101..115 encoding, which called it 101 and would have
# claimed the 010..014 capabilities YU01 does not have.
expect "YU01-lmax-sequencer" "9"

# YU02..YU15 chain back to 014.
expect "YU02-lmax-kubernetes" "14"
expect "YU15-eod-risk-extract" "14"

# An id belonging to no state resolves to nothing rather than to 0. A 0 would have compared as
# "below every threshold" and silently disabled the checks it gates.
if traderx_state_rank "${CATALOG}" "not-a-state" >/dev/null 2>&1; then
  echo "[fail] rank(not-a-state): expected no rank, got one"
  failures=$((failures + 1))
fi

# Every catalog state must be rankable.
while read -r id; do
  [[ -n "${id}" ]] || continue
  if ! traderx_state_rank "${CATALOG}" "${id}" >/dev/null 2>&1; then
    echo "[fail] catalog state has no rank: ${id}"
    failures=$((failures + 1))
  fi
done < <(jq -r '.states[].id' "${CATALOG}")

if (( failures > 0 )); then
  echo "[fail] state-rank self-check: ${failures} failure(s)"
  exit 1
fi

echo "[ok] state-rank self-check passed ($(jq -r '.states | length' "${CATALOG}") catalog states rankable)"
