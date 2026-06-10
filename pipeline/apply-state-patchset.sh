#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
STATE_ID="${1:-}"
TARGET_ROOT="${2:-${GENERATED_ROOT}/code/target-generated}"
PATCH_DIR="${ROOT}/specs/${STATE_ID}/generation/patches"
APPLY_SCOPE_ROOT="${TARGET_ROOT}"
APPLY_DIR_PREFIX=""
CAN_USE_3WAY=0

if [[ -z "${STATE_ID}" ]]; then
  echo "usage: bash pipeline/apply-state-patchset.sh <state-id> [target-root]"
  exit 1
fi

if [[ ! -d "${TARGET_ROOT}" ]]; then
  echo "[fail] target root does not exist: ${TARGET_ROOT}"
  exit 1
fi

if [[ ! -d "${PATCH_DIR}" ]]; then
  echo "[fail] missing patch directory: ${PATCH_DIR}"
  exit 1
fi

if git -C "${TARGET_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  CAN_USE_3WAY=1
  APPLY_SCOPE_ROOT="$(git -C "${TARGET_ROOT}" rev-parse --show-toplevel)"
  if command -v cygpath >/dev/null 2>&1; then
    # Git for Windows reports a Windows-style toplevel (C:/...); normalize it to
    # the POSIX form used by TARGET_ROOT so the prefix comparison below works.
    APPLY_SCOPE_ROOT="$(cygpath -u "${APPLY_SCOPE_ROOT}")"
  fi
  if [[ "${TARGET_ROOT}" != "${APPLY_SCOPE_ROOT}" ]]; then
    case "${TARGET_ROOT}" in
      "${APPLY_SCOPE_ROOT}"/*)
        APPLY_DIR_PREFIX="${TARGET_ROOT#${APPLY_SCOPE_ROOT}/}"
        ;;
      *)
        echo "[fail] target root is not under git top-level"
        echo "[hint] target=${TARGET_ROOT}"
        echo "[hint] toplevel=${APPLY_SCOPE_ROOT}"
        exit 1
        ;;
    esac
  fi
fi

if [[ -n "${APPLY_DIR_PREFIX}" ]]; then
  echo "[info] applying patchset via ${APPLY_SCOPE_ROOT} with directory prefix ${APPLY_DIR_PREFIX}"
else
  echo "[info] applying patchset via ${APPLY_SCOPE_ROOT}"
fi

patch_files=()
while IFS= read -r patch_file; do
  patch_files+=("${patch_file}")
done < <(find "${PATCH_DIR}" -maxdepth 1 -type f -name '*.patch' | sort)

if [[ "${#patch_files[@]}" -eq 0 ]]; then
  echo "[fail] no patch files found in ${PATCH_DIR}"
  exit 1
fi

apply_patch_with_fallback() {
  local patch_file="$1"
  local apply_args=(--recount --whitespace=nowarn)
  if [[ -n "${APPLY_DIR_PREFIX}" ]]; then
    apply_args+=(--directory="${APPLY_DIR_PREFIX}")
  fi
  if command -v cygpath >/dev/null 2>&1; then
    # Windows worktrees materialize the harness compat symlinks as junctions,
    # which git apply cannot rewrite. Skipping them is safe:
    # install-generated-runtime-harness.sh recreates these links for the
    # current state immediately after patch application.
    local link_prefix=""
    if [[ -n "${APPLY_DIR_PREFIX}" ]]; then
      link_prefix="${APPLY_DIR_PREFIX}/"
    fi
    apply_args+=(
      --exclude="${link_prefix}generated/code/target-generated"
      --exclude="${link_prefix}generated/code/components/*"
    )
  fi

  if git -C "${APPLY_SCOPE_ROOT}" apply --check "${apply_args[@]}" "${patch_file}"; then
    git -C "${APPLY_SCOPE_ROOT}" apply "${apply_args[@]}" "${patch_file}"
    echo "[apply] ${patch_file}"
    return 0
  fi

  echo "[warn] direct apply failed, retrying with 3-way merge: ${patch_file}"
  if (( CAN_USE_3WAY == 1 )); then
    if git -C "${APPLY_SCOPE_ROOT}" apply --3way --check "${apply_args[@]}" "${patch_file}"; then
      git -C "${APPLY_SCOPE_ROOT}" apply --3way "${apply_args[@]}" "${patch_file}"
      echo "[apply-3way] ${patch_file}"
      return 0
    fi
  else
    echo "[info] skipping 3-way merge fallback (target root is not a git worktree): ${APPLY_SCOPE_ROOT}"
  fi

  echo "[fail] unable to apply patch even with 3-way merge: ${patch_file}"
  return 1
}

for patch_file in "${patch_files[@]}"; do
  apply_patch_with_fallback "${patch_file}"
done

echo "[done] applied ${#patch_files[@]} patch(es) for ${STATE_ID} into ${TARGET_ROOT}"
