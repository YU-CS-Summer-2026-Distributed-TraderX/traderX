#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
STATE_ID="${1:-}"
PARENT_STATE_ID="${2:-}"
TARGET_PATH_ARG="${3:-${GENERATED_ROOT}/code/target-generated}"

if [[ "${TARGET_PATH_ARG}" = /* ]]; then
  TARGET_PATH="${TARGET_PATH_ARG}"
else
  TARGET_PATH="${ROOT}/${TARGET_PATH_ARG}"
fi

if [[ -z "${STATE_ID}" ]]; then
  echo "usage: bash pipeline/create-state-patchset.sh <state-id> [parent-state-id] [target-path]"
  echo "example: bash pipeline/create-state-patchset.sh 006-messaging-nats-replacement"
  echo "example: bash pipeline/create-state-patchset.sh 002-edge-proxy-uncontainerized 001-baseline-uncontainerized-parity generated/code/components"
  echo "env: TRADERX_GENERATED_ROOT=/abs/path/to/generated"
  exit 1
fi

if [[ -z "${PARENT_STATE_ID}" ]]; then
  if ! command -v jq >/dev/null 2>&1; then
    echo "[fail] jq is required when parent-state-id is omitted"
    exit 1
  fi

  PARENT_STATE_ID="$(
    jq -r --arg state "${STATE_ID}" '.states[] | select(.id == $state) | .previous[0] // empty' \
      "${ROOT}/catalog/state-catalog.json"
  )"
fi

if [[ -z "${PARENT_STATE_ID}" ]]; then
  echo "[fail] unable to resolve parent state for ${STATE_ID}"
  exit 1
fi

PATCH_DIR="${ROOT}/specs/${STATE_ID}/generation/patches"
PATCH_FILE="${PATCH_DIR}/0001-state-overlay.patch"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

PARENT_SNAPSHOT="${TMP_DIR}/parent"
CHILD_SNAPSHOT="${TMP_DIR}/child"
DIFF_REPO="${TMP_DIR}/diff-repo"
RSYNC_EXCLUDES=(
  "--exclude=.git"
  # Harness compat symlinks (generated/code/* inside the target root) are
  # recreated by install-generated-runtime-harness.sh on every generation and
  # are identical in parent and child, so they are never patch content. On
  # Windows they materialize as junctions that snapshot tools cannot copy.
  "--exclude=/generated"
  # Top-level-only (generation depth 1) installer outputs: the API explorer,
  # GHCR/deploy runtime bundles, and UI state metadata are produced solely for
  # the state being generated at the root invocation. A nested parent never has
  # them, so capturing their depth-1 forms creates patch entries whose
  # preimages do not exist at apply time. The state's own post-generation
  # installers always rebuild them, so they are never patch content.
  "--exclude=/api-explorer"
  "--exclude=/ingress/api-explorer"
  # API-explorer installation also rewrites this top-level ingress file after
  # nested parent generation; the child installer deterministically rebuilds it.
  "--exclude=/ingress/nginx.traderx.conf.template"
  "--exclude=/runtime"
  "--exclude=state-ui.json"
  "--exclude=.DS_Store"
  "--exclude=node_modules"
  "--exclude=node_modules/**"
  "--exclude=.gradle"
  "--exclude=.gradle/**"
  "--exclude=build"
  "--exclude=build/**"
  "--exclude=target"
  "--exclude=target/**"
  "--exclude=bin"
  "--exclude=bin/**"
  "--exclude=obj"
  "--exclude=obj/**"
  "--exclude=dist"
  "--exclude=dist/**"
  "--exclude=coverage"
  "--exclude=coverage/**"
  "--exclude=yarn.lock"
  "--exclude=pnpm-lock.yaml"
  "--exclude=gradlew"
  "--exclude=gradlew.bat"
  "--exclude=gradle/wrapper/gradle-wrapper.jar"
  "--exclude=gradle/wrapper/gradle-wrapper.properties"
  "--exclude=gradle/wrapper/**"
  "--exclude=.angular"
  "--exclude=*.log"
)

echo "[info] generating parent state ${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
if [[ ! -d "${TARGET_PATH}" ]]; then
  echo "[fail] target path does not exist after parent generation: ${TARGET_PATH}"
  exit 1
fi
mkdir -p "${PARENT_SNAPSHOT}"
rsync -a --delete "${RSYNC_EXCLUDES[@]}" "${TARGET_PATH}/" "${PARENT_SNAPSHOT}/"

echo "[info] generating child state ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${STATE_ID}"
if [[ ! -d "${TARGET_PATH}" ]]; then
  echo "[fail] target path does not exist after child generation: ${TARGET_PATH}"
  exit 1
fi
mkdir -p "${CHILD_SNAPSHOT}"
rsync -a --delete "${RSYNC_EXCLUDES[@]}" "${TARGET_PATH}/" "${CHILD_SNAPSHOT}/"

rm -rf "${DIFF_REPO}"
mkdir -p "${DIFF_REPO}"
rsync -a --delete "${PARENT_SNAPSHOT}/" "${DIFF_REPO}/"

git -C "${DIFF_REPO}" init -q
# Keep the diff repo byte-faithful regardless of host git defaults (Windows
# global config often sets autocrlf=true, which would mangle the captured patch).
git -C "${DIFF_REPO}" config core.autocrlf false
git -C "${DIFF_REPO}" add -A
git -C "${DIFF_REPO}" commit --allow-empty -qm "parent-${PARENT_STATE_ID}"

rsync -a --delete --exclude='.git' "${CHILD_SNAPSHOT}/" "${DIFF_REPO}/"
git -C "${DIFF_REPO}" add -A

mkdir -p "${PATCH_DIR}"
if git -C "${DIFF_REPO}" diff --cached --quiet; then
  : > "${PATCH_FILE}"
  echo "[warn] no differences detected between ${PARENT_STATE_ID} and ${STATE_ID}"
else
  git -C "${DIFF_REPO}" diff --cached --binary > "${PATCH_FILE}"
fi

if [[ ! -s "${PATCH_FILE}" ]]; then
  echo "[fail] empty patch file produced: ${PATCH_FILE}"
  exit 1
fi

echo "[done] wrote patch set for ${STATE_ID}: ${PATCH_FILE}"
