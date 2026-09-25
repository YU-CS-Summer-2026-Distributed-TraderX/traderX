#!/usr/bin/env bash
# Explicit local-only disposable proof; reserve documented names and ports before running.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../../../.." && pwd)"
export GEN="${GEN:-$ROOT/generated/code/target-generated}"
export OUT="${OUT:-$(mktemp -d "${TMPDIR:-/tmp}/traderx-managed-acceptance.XXXXXX")}"
: "${JAVA_HOME:?select Java 21}"
export JAVA="$JAVA_HOME/bin/java" PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$OUT"
[[ ! -e "$OUT/owned-container-ids.txt" && ! -e "$OUT/log.json" ]] || { echo 'Refuse reused proof output'; exit 2; }
cleanup() {
  local rc=$?
  if [[ -e "$OUT/owned-container-ids.txt" ]]; then
    docker exec traderx-ma-20260925-sql mariadb-dump -utraderx -ptraderx traderx > "$OUT/database.sql" 2> "$OUT/database-dump.stderr" || true
    while IFS= read -r id; do docker inspect "$id" >> "$OUT/container-identities.json"; docker rm -f "$id"; done < "$OUT/owned-container-ids.txt"
  fi
  printf '%s\n' "$rc" > "$OUT/exit-code.txt"
}
trap cleanup EXIT
cat > "$OUT/classpath.init.gradle" <<'GRADLE'
allprojects { afterEvaluate { tasks.register('ri06RuntimeClasspath') { dependsOn tasks.named('classes'); doLast { new File(System.getProperty('ri06.classpath')).text = sourceSets.main.runtimeClasspath.asPath } } } }
GRADLE
export MATCHER_CLASSPATH_FILE="$OUT/matcher-classpath.txt"
(cd "$GEN/order-matcher" && ./gradlew -I "$OUT/classpath.init.gradle" -Dri06.classpath="$MATCHER_CLASSPATH_FILE" ri06RuntimeClasspath --no-daemon --max-workers=2) > "$OUT/build-matcher.log" 2>&1
for service in trade-processor position-service account-service; do
 (cd "$GEN/$service" && ./gradlew bootJar --no-daemon --max-workers=2) > "$OUT/build-$service.log" 2>&1
done
(cd "$ROOT/web-front-end-console" && npm ci && npm run build) > "$OUT/build-console.log" 2>&1
python3 "$HERE/setup-managed-acceptance.py"
node "$HERE/managed-acceptance-proof.mjs" > "$OUT/driver.log" 2>&1
python3 "$HERE/verify-managed-notifications.py" "$OUT/nats.raw" > "$OUT/notification-verification.json"
