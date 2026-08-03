#!/usr/bin/env bash
set -euo pipefail

context_abs="${1:-}"
service_name="${2:-JVM service}"

if [[ -z "${context_abs}" ]]; then
  echo "usage: build-jvm-jar.sh <build-context> [service-name]" >&2
  exit 2
fi

# Non-JVM image contexts have no Gradle build and need no preparation.
if [[ ! -f "${context_abs}/build.gradle" ]]; then
  exit 0
fi

# A JVM context must never fall through to Docker with an old build/libs jar.
if [[ ! -x "${context_abs}/gradlew" ]]; then
  echo "[error] JVM build context for ${service_name} has build.gradle but no executable gradlew: ${context_abs}" >&2
  exit 1
fi

echo "[build] ${service_name}: ./gradlew clean bootJar (fresh jar before docker build)"
(
  cd "${context_abs}"
  ./gradlew --no-daemon clean bootJar
)
