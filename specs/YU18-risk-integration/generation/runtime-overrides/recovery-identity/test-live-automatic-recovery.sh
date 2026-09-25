#!/usr/bin/env bash
# Disposable RI06 automatic-projection-recovery proof. Never touches an existing rig or SQL URL.
# Leases: containers traderx-apr-live-sql, traderx-apr-live-nats, traderx-apr-controls-sql;
# host ports 28200-28399. Run sequentially from a generated YU18 runtime with Java 21 + Docker.
set -euo pipefail
[[ "${RI06_AUTO_RECOVERY_PROOF:-}" == "1" ]] || { echo 'Set RI06_AUTO_RECOVERY_PROOF=1 after reserving the documented disposable resources.' >&2; exit 2; }
COMPONENT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$(dirname "$COMPONENT")"
[[ -x "$TARGET/order-matcher/gradlew" && -x "$TARGET/trade-processor/gradlew" ]] || { echo 'Run from a generated YU18 runtime.' >&2; exit 2; }
for name in traderx-apr-live-sql traderx-apr-live-nats traderx-apr-controls-sql; do
 if docker inspect "$name" >/dev/null 2>&1; then echo "Refusing: container $name already exists" >&2; exit 2; fi
done
PROOF_DIR="${APR_PROOF_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/traderx-apr-proof.XXXXXX")}"
mkdir -p "$PROOF_DIR"
export TESTCONTAINERS_RYUK_DISABLED=true
export RI06_MATCHER_CLASSPATH_FILE="$PROOF_DIR/classpath.txt"
cat > "$PROOF_DIR/classpath.init.gradle" <<'GRADLE'
allprojects { afterEvaluate { tasks.register('aprRuntimeClasspath') {
 dependsOn tasks.named('classes')
 doLast { new File(System.getenv('RI06_MATCHER_CLASSPATH_FILE')).text = sourceSets.main.runtimeClasspath.asPath }
} } }
GRADLE
printf 'APR_PROOF_DIR=%s\n' "$PROOF_DIR"
(cd "$TARGET/order-matcher" && ./gradlew -I "$PROOF_DIR/classpath.init.gradle" aprRuntimeClasspath --no-daemon --max-workers=2) > "$PROOF_DIR/matcher-build.log" 2>&1
run() { # $1 test filter, $2 result class, $3 evidence name
 set +e; (cd "$TARGET/trade-processor" && ./gradlew integrationTest --tests "$1" --rerun-tasks --no-daemon --max-workers=2) > "$PROOF_DIR/$3.log" 2>&1; local rc=$?; set -e
 cp "$TARGET/trade-processor/build/test-results/integrationTest/TEST-finos.traderx.tradeprocessor.service.$2.xml" "$PROOF_DIR/$3.xml" 2>/dev/null || true
 grep -q 'skipped="0" failures="0" errors="0"' "$PROOF_DIR/$3.xml" && grep -q 'tests="[1-9]' "$PROOF_DIR/$3.xml" && [[ $rc == 0 ]] \
  || { echo "FAIL $3 (exit $rc); see $PROOF_DIR/$3.log" >&2; exit 1; }
 printf 'PASS %s\n' "$3"
}
export RI06_LIVE_PROOF_DIR="$PROOF_DIR/live"
run '*AutomaticRecoveryLiveIT' AutomaticRecoveryLiveIT live
run '*AutomaticRecoveryPersistenceIT' AutomaticRecoveryPersistenceIT fixture-sql
run '*EventRecoveryPersistenceIT' EventRecoveryPersistenceIT explicit-recovery-sql
printf 'PASS all; evidence %s\n' "$PROOF_DIR"
