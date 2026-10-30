#!/bin/bash
# fetch-deps.sh: the bake-off's two dependencies into build/, then compile. jnats 2.20.5 (the version trade-processor
# uses) from Maven Central; KX's Apache-2.0 Java IPC client (c.java + ByteArrayAccess.java) from KxSystems/javakdb.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/lib build/src/com/kx
curl -sfLo build/lib/jnats-2.20.5.jar https://repo1.maven.org/maven2/io/nats/jnats/2.20.5/jnats-2.20.5.jar
for f in c.java ByteArrayAccess.java; do
  curl -sfLo build/src/com/kx/$f https://raw.githubusercontent.com/KxSystems/javakdb/master/javakdb/src/main/java/com/kx/$f
done
javac -d build/classes -cp build/lib/jnats-2.20.5.jar build/src/com/kx/*.java src/*.java
echo "built: java -cp build/classes:build/lib/jnats-2.20.5.jar HistoryFeed|HsbLoad|HsbQuery ..."
