#!/usr/bin/env bash
# ONE place that answers "which state is this worktree, and which cluster-node image is its tier".
#
# WHY THIS EXISTS. That answer was written down in THREE places, each going stale independently at
# every state cut:
#
#   1. start-cluster-kind.sh's KDIR      - a hardcoded specs/<pack>/... path. YU17's copy points at
#                                          YU16's manifests, so the tip's tier ran its ANCESTOR's
#                                          manifests and nothing said so.
#   2. the tag inside those manifests    - YU16's declared :yu15, copied from YU15 at the cut.
#   3. run-proofs.sh's BASELINE_IMAGE    - defaulted to :yu15 on YU15, YU16 and YU17 alike, and
#                                          ROLLS THE RIG onto it before proving anything.
#
# Each produced a coherent-looking verdict about a build nobody was testing. Deriving all three from
# the state id closes the class; see .claude/skills/establish-a-valid-baseline ("three silent paths
# to testing the wrong build") and .claude/skills/a-prefix-is-not-a-category (derive, don't
# duplicate; and make the failure loud rather than quiet).
#
# THE DERIVATION. The lineage rule says a branch carries spec packs for itself and all its
# ancestors and NEVER its descendants — so the highest-ranked specs/YU* directory present IS this
# worktree's state. That is a property of the repo's own invariant, not a guess, and it survives a
# detached HEAD or a renamed branch in a way `git branch --show-current` does not.
#
# Everything here REFUSES rather than defaulting. A wrong image is worse than no answer, because a
# wrong image still produces a full green run.

# state_pack <repo-root> -> e.g. YU16-cdm-instruments
state_pack() {
  local root="${1:?state_pack needs the repo root}" pack
  pack="$(ls -d "${root}"/specs/YU*/ 2>/dev/null | sed 's|.*/specs/||; s|/$||' | sort -V | tail -1)"
  [[ -n "${pack}" ]] || { echo "[fail] no specs/YU* pack under ${root}: cannot determine the state" >&2; return 1; }
  printf '%s\n' "${pack}"
}

# state_tag <repo-root> -> e.g. yu16   (the image tag this state's tier should run)
state_tag() {
  local pack; pack="$(state_pack "$1")" || return 1
  printf '%s\n' "$(echo "${pack%%-*}" | tr '[:upper:]' '[:lower:]')"
}

# cluster_manifest_dir <repo-root> [--quiet] -> the operative cluster manifest directory.
#
# Walks DOWN the lineage from this state until it finds a layer that actually carries the cluster
# manifests, and is LOUD when that is not the state's own layer — which is the case this whole file
# exists for. Falling back is allowed; falling back silently is not.
cluster_manifest_dir() {
  local root="${1:?cluster_manifest_dir needs the repo root}" quiet="${2:-}" pack own found
  pack="$(state_pack "${root}")" || return 1
  own="${root}/specs/${pack}/generation/kubernetes/cluster"
  if [[ -f "${own}/statefulset.yaml" ]]; then
    printf '%s\n' "${own}"
    return 0
  fi
  # Descending rank order: the nearest ancestor that carries them.
  while read -r candidate; do
    if [[ -f "${root}/specs/${candidate}/generation/kubernetes/cluster/statefulset.yaml" ]]; then
      found="${candidate}"
      break
    fi
  done < <(ls -d "${root}"/specs/YU*/ 2>/dev/null | sed 's|.*/specs/||; s|/$||' | sort -Vr)
  [[ -n "${found:-}" ]] || { echo "[fail] no cluster manifest layer anywhere under ${root}/specs" >&2; return 1; }
  if [[ "${quiet}" != "--quiet" ]]; then
    cat >&2 <<EOF
[WARN] ${pack} has no cluster manifest layer of its own; using ${found}'s.
       The tier will therefore run ${found}'s manifests and the image THEY declare, which is not
       ${pack}'s build. That is the silent-ancestor-image bug this warning exists to make loud.
       Fix: give ${pack} its own specs/${pack}/generation/kubernetes/cluster layer declaring
       traderx/cluster-node:$(echo "${pack%%-*}" | tr '[:upper:]' '[:lower:]').
EOF
  fi
  printf '%s\n' "${root}/specs/${found}/generation/kubernetes/cluster"
}

# operative_layer_file <repo-root> <path-relative-to-a-spec-pack> -> the OPERATIVE copy.
#
# runtime-overrides layers compose last-wins, so the operative copy of a file is the one in the
# highest-ranked layer that carries it. Hardcoding a layer here is the same bug as hardcoding KDIR:
# on YU17, `specs/YU16-.../database-init-configmap.yaml` applies YU16's schema and seeds even
# though YU17 may carry its own. Silent, and it only shows up as missing tables much later.
operative_layer_file() {
  local root="${1:?}" rel="${2:?}" candidate
  while read -r candidate; do
    if [[ -f "${root}/specs/${candidate}/${rel}" ]]; then
      printf '%s\n' "${root}/specs/${candidate}/${rel}"
      return 0
    fi
  done < <(ls -d "${root}"/specs/YU*/ 2>/dev/null | sed 's|.*/specs/||; s|/$||' | sort -Vr)
  echo "[fail] no layer under ${root}/specs carries ${rel}" >&2
  return 1
}

# declared_cluster_image <repo-root> -> the kind cluster-node image the operative manifests declare.
# This is the AUTHORITY for what the tier runs: it is the string kubectl will actually apply.
declared_cluster_image() {
  local dir img
  dir="$(cluster_manifest_dir "$1" --quiet)" || return 1
  img="$(grep -hoE 'traderx/cluster-node:[A-Za-z0-9._-]+' "${dir}/statefulset.yaml" 2>/dev/null | head -1)"
  [[ -n "${img}" ]] || { echo "[fail] ${dir}/statefulset.yaml declares no traderx/cluster-node image" >&2; return 1; }
  printf '%s\n' "${img}"
}
