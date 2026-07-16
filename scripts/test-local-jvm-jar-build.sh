#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_JVM_JAR="${ROOT}/scripts/lib/build-jvm-jar.sh"
TMP_DIR="$(mktemp -d /tmp/traderx-local-jvm-jar.XXXXXX)"
trap 'rm -rf "${TMP_DIR}"' EXIT

JVM_CONTEXT="${TMP_DIR}/jvm-service"
NON_JVM_CONTEXT="${TMP_DIR}/node-service"
mkdir -p "${JVM_CONTEXT}/build/libs" "${NON_JVM_CONTEXT}"
touch "${JVM_CONTEXT}/build.gradle" "${JVM_CONTEXT}/build/libs/stale.jar"

cat > "${JVM_CONTEXT}/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" > gradle-args.txt
rm -rf build/libs
mkdir -p build/libs
touch build/libs/fresh.jar
EOF
chmod +x "${JVM_CONTEXT}/gradlew"

echo "[check] a JVM context is rebuilt from clean sources"
bash "${BUILD_JVM_JAR}" "${JVM_CONTEXT}" test-jvm-service
[[ "$(<"${JVM_CONTEXT}/gradle-args.txt")" == "--no-daemon clean bootJar" ]] || {
  echo "[fail] expected clean bootJar invocation" >&2
  exit 1
}
[[ -f "${JVM_CONTEXT}/build/libs/fresh.jar" ]] || {
  echo "[fail] fresh boot jar was not produced" >&2
  exit 1
}
[[ ! -e "${JVM_CONTEXT}/build/libs/stale.jar" ]] || {
  echo "[fail] stale jar survived the clean build" >&2
  exit 1
}

echo "[check] a non-JVM context is ignored"
bash "${BUILD_JVM_JAR}" "${NON_JVM_CONTEXT}" test-node-service

echo "[check] a JVM context cannot fall through without gradlew"
MISSING_WRAPPER_CONTEXT="${TMP_DIR}/missing-wrapper"
mkdir -p "${MISSING_WRAPPER_CONTEXT}"
touch "${MISSING_WRAPPER_CONTEXT}/build.gradle"
if bash "${BUILD_JVM_JAR}" "${MISSING_WRAPPER_CONTEXT}" broken-jvm-service >"${TMP_DIR}/missing.out" 2>&1; then
  echo "[fail] JVM context without gradlew unexpectedly passed" >&2
  exit 1
fi
rg -q 'has build.gradle but no executable gradlew' "${TMP_DIR}/missing.out" || {
  echo "[fail] missing-gradlew failure was not explicit" >&2
  exit 1
}

echo "[done] local JVM jar-build guard checks passed"
