#!/usr/bin/env bash
# Single definition of "which numbered rung of the lineage is this state on?".
#
# Every numeric threshold in the pipeline asks one question -- "is this state at or past N?" -- so
# there must be exactly one answer per state. Resolve it by walking previous[0] back to the nearest
# numbered ancestor, because that is what the question means: a state sits on the rung its lineage
# reached. YU02..YU15 chain back to 014 -> 14. YU01 forks off 009 -> 9.
#
# There was a second encoding in the tree that ranked YU01..YU15 as 101..115. It agrees with this
# one at every threshold currently in use (2, 3, 4, 6, 8, 9), which is why nothing was visibly
# broken, but it is not the same function: it places YU01 -- a fork off 009 -- above 010..014,
# claiming the kubernetes/tilt/c3/fdc3 capabilities YU01 does not have. The first threshold anyone
# adds in the 10..14 range would diverge silently. The walk is the truthful one; prefer it.
#
# Not every prefix use is a rank. Building a NAME (traderx-state-<prefix>) or a filename glob
# (start-state-<prefix>-*-generated.sh) wants the id's own literal prefix, not the rung -- those
# call sites are correct as they are and must not be routed through here.
#
# Letter-suffixed siblings (009b-*) rank as their numeric base.
# Requires jq, which the pipeline already requires up front (generate-state.sh).

# traderx_state_rank <catalog-path> <state-id>
# Prints the rank on stdout and returns 0, or returns 1 if the id resolves to no numbered ancestor.
traderx_state_rank() {
  local catalog="$1"
  local cursor="$2"
  local visited=""
  local direct

  while [[ -n "${cursor}" ]]; do
    case ",${visited}," in
      *",${cursor},"*) break ;; # cycle guard: a malformed catalog must not spin forever
    esac
    visited="${visited},${cursor}"

    direct="${cursor%%-*}"
    if [[ "${direct}" =~ ^[0-9]+[a-z]?$ ]]; then
      printf '%s\n' "$((10#${direct%%[a-z]*}))"
      return 0
    fi

    cursor="$(jq -r --arg id "${cursor}" '.states[] | select(.id == $id) | (.previous[0] // "")' "${catalog}")"
  done

  return 1
}
