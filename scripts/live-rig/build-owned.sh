#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TAG=lr-20261008-01a11baa
export JAVA_HOME=/Users/yaakov/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home
for svc in trade-processor position-service execution-algo-engine; do
  dir="$ROOT/generated/code/target-generated/$svc"
  (cd "$dir" && ./gradlew --no-daemon --max-workers=2 -q clean bootJar)
  docker build -f "$dir/Dockerfile" -t "traderx/$svc:$TAG" "$dir"
done
for svc in price-publisher reference-data; do
  dir="$ROOT/generated/code/target-generated/$svc"
  docker build -f "$dir/Dockerfile.compose" -t "traderx/$svc:$TAG" "$dir"
done
docker build -t "traderx/console:$TAG" "$ROOT/web-front-end-console"
