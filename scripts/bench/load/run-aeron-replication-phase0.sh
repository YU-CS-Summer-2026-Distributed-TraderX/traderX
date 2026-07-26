#!/usr/bin/env bash
# Measure the real YU11 Aeron replication transport with same-day controls.
#
# Mirrors run-nats-replication-phase0.sh so the events/second numbers are directly comparable:
# same ring, journal batch, publish batch, warm-up, event fill, and timed window. No broker is
# needed — the harness launches an embedded Aeron Media Driver per run.
#
# This runner deliberately does not regenerate the state: generation uses a shared root and must
# follow the repository lock protocol. Run it after YU11 has been generated into TARGET_DIR.
# Results are transport events/second, not end-to-end booked orders/second.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TARGET_DIR="${TARGET_DIR:-${REPO_ROOT}/generated/code/target-generated/order-matcher}"
TEST_CLASS="finos.traderx.ordermatcher.lmax.AeronReplicationPhase0Test"
TEST_SOURCE="${TARGET_DIR}/src/test/java/finos/traderx/ordermatcher/lmax/AeronReplicationPhase0Test.java"

RUNS="${RUNS:-3}"
RUN_SECONDS="${RUN_SECONDS:-30}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS_DIR="${RESULTS_DIR:-${SCRIPT_DIR}/results/aeron-replication-phase0-${TIMESTAMP}}"
RESULT_LINES="${RESULTS_DIR}/phase0-results.txt"

if [[ ! -f "${TEST_SOURCE}" ]]; then
  echo "Missing generated Phase-0 test: ${TEST_SOURCE}" >&2
  echo "Generate YU11 under the shared generation lock, then retry." >&2
  exit 2
fi

mkdir -p "${RESULTS_DIR}"

{
  echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "branch=$(git -C "${REPO_ROOT}" branch --show-current)"
  echo "head=$(git -C "${REPO_ROOT}" rev-parse HEAD)"
  echo "target_dir=${TARGET_DIR}"
  echo "runs=${RUNS}"
  echo "run_seconds=${RUN_SECONDS}"
  echo "journal_batch_records=1024"
  echo "publish_batch=256"
  echo "record_bytes=64"
  echo "warmup_events=65536"
  echo "tiers=single-control,aeron-ipc,aeron-mdc-udp"
  uname -a
  java -version 2>&1
} > "${RESULTS_DIR}/config.txt"

LOG_FILE="${RESULTS_DIR}/aeron-phase0.gradle.log"
XML_FILE="${TARGET_DIR}/build/test-results/test/TEST-${TEST_CLASS}.xml"

echo "Running Aeron Phase-0: ${RUNS} x ${RUN_SECONDS}s per tier"
(
  cd "${TARGET_DIR}"
  env AERON_REPLICATION_PHASE0=true \
    AERON_REPLICATION_BENCH_RUNS="${RUNS}" \
    AERON_REPLICATION_BENCH_SECONDS="${RUN_SECONDS}" \
    ./gradlew cleanTest test \
      --tests "${TEST_CLASS}.compareJournaledControlWithAeronReplication" \
      --console=plain
) 2>&1 | tee "${LOG_FILE}"

grep -o 'PHASE0_RESULT[^<]*' "${XML_FILE}" | tee "${RESULT_LINES}"

echo "Results written to ${RESULTS_DIR}"
