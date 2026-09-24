#!/usr/bin/env bash
# Disposable O1 proof only: no existing runtime endpoints or SQL URLs are accepted.
set -euo pipefail
[[ "${RI06_EVENT_RECOVERY_PROOF:-}" == "1" ]] || { echo 'Set RI06_EVENT_RECOVERY_PROOF=1 after reserving the documented disposable resources.' >&2; exit 2; }
COMPONENT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$(dirname "$COMPONENT")"
[[ -x "$TARGET/order-matcher/gradlew" && -x "$TARGET/trade-processor/gradlew" ]] || { echo 'Run from a generated YU18 runtime.' >&2; exit 2; }
PROOF_DIR="$(mktemp -d "${TMPDIR:-/tmp}/traderx-o1-proof.XXXXXX")"
export TESTCONTAINERS_RYUK_DISABLED=true
export RI06_MATCHER_CLASSPATH_FILE="$PROOF_DIR/classpath.txt"
cat > "$PROOF_DIR/classpath.init.gradle" <<'GRADLE'
allprojects { afterEvaluate { tasks.register('o1RuntimeClasspath') {
 dependsOn tasks.named('classes')
 doLast { new File(System.getenv('RI06_MATCHER_CLASSPATH_FILE')).text = sourceSets.main.runtimeClasspath.asPath }
} } }
GRADLE
printf 'O1_PROOF_DIR=%s\n' "$PROOF_DIR"
(cd "$TARGET/order-matcher" && ./gradlew -I "$PROOF_DIR/classpath.init.gradle" o1RuntimeClasspath --no-daemon --max-workers=2) > "$PROOF_DIR/matcher-build.log" 2>&1
for method in consumerOutageLosesEventsAndRestartDoesNotCatchUp managedConsumerAndPublisherOutagesRecoverFromArchive; do
 export RI06_LIVE_PROOF_DIR="$PROOF_DIR/$method"
 (cd "$TARGET/trade-processor" && ./gradlew integrationTest --tests "*EventRecoveryLiveIT.$method" --rerun-tasks --no-daemon --max-workers=2) > "$PROOF_DIR/$method.log" 2>&1
 cp "$TARGET/trade-processor/build/test-results/integrationTest/TEST-finos.traderx.tradeprocessor.service.EventRecoveryLiveIT.xml" "$PROOF_DIR/$method.xml"
 printf 'PASS %s\n' "$method"
done
(cd "$TARGET/trade-processor" && ./gradlew integrationTest --tests '*EventRecoveryPersistenceIT' --no-daemon --max-workers=2) > "$PROOF_DIR/sql-controls.log" 2>&1
cp "$TARGET/trade-processor/build/test-results/integrationTest/TEST-finos.traderx.tradeprocessor.service.EventRecoveryPersistenceIT.xml" "$PROOF_DIR/sql-controls.xml"
printf 'PASS SQL recovery controls; evidence %s\n' "$PROOF_DIR"
