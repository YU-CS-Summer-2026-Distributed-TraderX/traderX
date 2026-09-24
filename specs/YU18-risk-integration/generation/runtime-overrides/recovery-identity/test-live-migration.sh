#!/usr/bin/env bash
# Explicit opt-in: two disposable single-member Aeron runs + gateways, real NATS and MariaDB.
# Never targets an existing cluster or database. Requires Docker and Java 21.
set -euo pipefail
COMPONENT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$(dirname "$COMPONENT")"
[[ -x "$TARGET/order-matcher/gradlew" && -x "$TARGET/trade-processor/gradlew" ]] || {
  echo 'Run this script from a fully generated YU18 tree.' >&2; exit 2;
}
PROOF_DIR="$(mktemp -d "${TMPDIR:-/tmp}/traderx-ri06-live.XXXXXX")"
cat > "$PROOF_DIR/classpath.init.gradle" <<'GRADLE'
allprojects {
  afterEvaluate {
    tasks.register('ri06RuntimeClasspath') {
      dependsOn tasks.named('classes')
      doLast { new File(System.getProperty('ri06.classpath')).text = sourceSets.main.runtimeClasspath.asPath }
    }
  }
}
GRADLE
(cd "$TARGET/order-matcher" && ./gradlew -I "$PROOF_DIR/classpath.init.gradle" \
  -Dri06.classpath="$PROOF_DIR/matcher-classpath.txt" ri06RuntimeClasspath --no-daemon --max-workers=2)
export RI06_MATCHER_CLASSPATH_FILE="$PROOF_DIR/matcher-classpath.txt"
export RI06_LIVE_PROOF_DIR="$PROOF_DIR/runtime"
# The test owns and closes its named containers; avoids an unrelated Ryuk listener on macOS.
export TESTCONTAINERS_RYUK_DISABLED=true
printf 'RI-06 disposable proof output: %s\n' "$PROOF_DIR"
(cd "$TARGET/trade-processor" && ./gradlew integrationTest --tests '*RunMigrationLiveIT' --rerun-tasks --no-daemon --max-workers=2)
