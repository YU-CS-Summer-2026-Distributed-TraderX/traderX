#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export GEN="$ROOT/generated/code/target-generated"
export OUT="${OUT:?Set a new evidence directory}"
export JAVA_HOME="${JAVA_HOME:?Select Java 21}" JAVA="$JAVA_HOME/bin/java" PATH="$JAVA_HOME/bin:$PATH"
export DESK_PROOF_NAME="${DESK_PROOF_NAME:?Set a unique disposable rig name}"
[[ "$DESK_PROOF_NAME" == traderx-desk-* ]] || exit 2
mkdir -p "$OUT"
[[ ! -e "$OUT/owned-container-ids.txt" ]] || exit 2
# No Kubernetes client is used; an isolated empty config prevents accidental default-context use.
export KUBECONFIG="$OUT/kubeconfig"
printf 'apiVersion: v1\nkind: Config\nclusters: []\ncontexts: []\nusers: []\n' > "$KUBECONFIG"
cleanup() {
 rc=$?
 if [[ -f "$OUT/owned-container-ids.txt" ]]; then
  docker exec "$DESK_PROOF_NAME-sql" mariadb-dump -utraderx -ptraderx traderx > "$OUT/database.sql" 2> "$OUT/dump.stderr" || true
  while IFS= read -r id; do docker inspect "$id" >> "$OUT/container-identities.json"; docker stop "$id"; done < "$OUT/owned-container-ids.txt"
 fi
 printf '%s\n' "$rc" > "$OUT/exit-code.txt"
}
trap cleanup EXIT
cat > "$OUT/classpath.init.gradle" <<'GRADLE'
allprojects { afterEvaluate { tasks.register('deskRuntimeClasspath') { dependsOn tasks.named('classes'); doLast { new File(System.getProperty('desk.classpath')).text = sourceSets.main.runtimeClasspath.asPath } } } }
GRADLE
export MATCHER_CLASSPATH_FILE="$OUT/matcher-classpath.txt"
(cd "$GEN/order-matcher" && ./gradlew -I "$OUT/classpath.init.gradle" -Ddesk.classpath="$MATCHER_CLASSPATH_FILE" deskRuntimeClasspath test --tests '*RunIdentityTest' --no-daemon --max-workers=2) > "$OUT/build-matcher.log" 2>&1
for service in trade-processor position-service account-service; do
 (cd "$GEN/$service" && ./gradlew bootJar --no-daemon --max-workers=2) > "$OUT/build-$service.log" 2>&1
done
python3 "$ROOT/scripts/desk-managed-recovery/setup.py"
node "$ROOT/scripts/desk-managed-recovery/proof.mjs" > "$OUT/driver.log" 2>&1
