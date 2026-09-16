#!/usr/bin/env bash
# Add matching Python readers to a reviewed console image. Never includes coordinator state.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${CONSOLE_IMAGE:?Set the reviewed console image tag or digest}"
: "${RISK_RUNTIME_IMAGE:?Set a unique output image tag}"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/traderx-risk-runtime.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
mkdir "$STAGE/eod-risk-bundles"
cp "$ROOT"/specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles/*.py "$STAGE/eod-risk-bundles/"
cp "$ROOT/web-front-end-console/Dockerfile.risk-runtime" "$STAGE/Dockerfile"
docker build --platform linux/amd64 --build-arg "CONSOLE_IMAGE=$CONSOLE_IMAGE" -t "$RISK_RUNTIME_IMAGE" "$STAGE"
