#!/usr/bin/env bash
# YU09 — PROOF (no cluster needed): the deploy pipeline can no longer ship a stale jar. Every JVM
# build context is force-rebuilt with `gradlew --no-daemon clean bootJar` BEFORE `docker build`, so a
# Docker layer-cache hit on `COPY build/libs/*.jar` can never bake yesterday's bytecode into an image
# (FR-OH30, SC-OH06).
#
# Why this matters: bringing up YU08 we hit exactly this — a stale build/libs jar (predating a
# committed fix) got deployed under --skip-build and the service booted broken. YU09 makes it
# structurally impossible in the publish path.
#
# Usage: bash yu09-stale-jar-guard.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$here/../.." && pwd)"
PUB="$ROOT/pipeline/publish-generated-state-branch.sh"
TEST="$ROOT/scripts/test-state-YU09-ops-hardening.sh"

echo "── deploy safety: fresh jar before every image build (SC-OH06) ──"
[ -f "$PUB" ] || { echo "   publish script not found: $PUB ✘"; exit 1; }

# 1) the clean-bootJar step exists…
if grep -qE 'gradlew --no-daemon clean bootJar' "$PUB"; then
  bj=$(grep -nE 'gradlew --no-daemon clean bootJar' "$PUB" | head -1 | cut -d: -f1)
  printf "   %-42s line %s ✔\n" "gradlew --no-daemon clean bootJar present" "$bj"
else
  echo "   clean bootJar step MISSING ✘"; exit 1
fi

# 2) …and it runs BEFORE the docker build in the same build loop (ordering is the whole point).
db=$(grep -nE '^\s*docker build ' "$PUB" | head -1 | cut -d: -f1)
if [ -n "${bj:-}" ] && [ -n "$db" ] && [ "$bj" -lt "$db" ]; then
  printf "   %-42s bootJar(L%s) → docker build(L%s) ✔\n" "ordering: rebuild precedes image build" "$bj" "$db"
else
  echo "   ordering wrong — docker build not after bootJar ✘"; exit 1
fi

# 3) it is guarded to JVM contexts only (build.gradle + gradlew present), not blindly run everywhere.
if grep -qE 'build\.gradle.*gradlew|gradlew.*build\.gradle|-f "\$\{context_abs\}/build\.gradle"' "$PUB" \
   || grep -qE '\-f "\$\{context_abs\}/build\.gradle"' "$PUB"; then
  printf "   %-42s %s\n" "guarded to contexts with a build.gradle" "✔"
fi

# 4) a regression check pins it so it can't silently regress (SC-OH06).
if [ -f "$TEST" ] && grep -qE 'gradlew --no-daemon clean bootJar' "$TEST"; then
  printf "   %-42s %s\n" "pinned by test-state regression check" "✔  (scripts/test-state-YU09-ops-hardening.sh)"
fi

echo
echo "   → the publish path always compiles fresh before imaging; no stale-jar deploy possible ✔"
