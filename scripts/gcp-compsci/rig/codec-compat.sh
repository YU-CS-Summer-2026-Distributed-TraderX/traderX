#!/usr/bin/env bash
# ADR-096 §6 (FR-PRG-10): does the tip gateway's ingress decode on the members' commit? Runs CodecCompat.java on
# each side's REAL compiled codec. OLD and TIP are renders (TRADERX_GENERATED_ROOT) of the members' commit and the
# tip, each with order-matcher compiled (`./gradlew compileJava` in code/target-generated/order-matcher).
#   codec-compat.sh OLD_GENERATED TIP_GENERATED      exit 0 = compatible
set -euo pipefail
[[ $# == 2 ]] || { echo "usage: $0 OLD_GENERATED TIP_GENERATED" >&2; exit 2; }
classes() {
  local dir="$1/code/target-generated/order-matcher/build/classes/java/main"
  [[ -f $dir/finos/traderx/ordermatcher/lmax/AeronReplicationCodec.class ]] || { echo "not compiled: $dir" >&2; exit 2; }
  echo "$dir"
}
old=$(classes "$1"); tip=$(classes "$2")
agrona=$(ls ~/.gradle/caches/modules-2/files-2.1/org.agrona/agrona/2.4.1/*/agrona-2.4.1.jar | head -1)
java=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || dirname "$(dirname "$(command -v java)")")}/bin/java
exec "$java" --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED "$(dirname "$0")/CodecCompat.java" "$old" "$tip" "$agrona"
