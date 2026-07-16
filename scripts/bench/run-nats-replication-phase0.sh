#!/usr/bin/env bash
# Measure the real YU02 File-backed NATS replication path with same-day controls.
#
# This runner deliberately does not regenerate the state: generation uses a shared root and must
# follow the repository lock protocol. Run it after YU02 has been generated into TARGET_DIR.
# Results are transport events/second, not end-to-end booked orders/second.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TARGET_DIR="${TARGET_DIR:-${REPO_ROOT}/generated/code/target-generated/order-matcher}"
TEST_CLASS="finos.traderx.ordermatcher.lmax.NatsReplicationPhase0Test"
TEST_SOURCE="${TARGET_DIR}/src/test/java/finos/traderx/ordermatcher/lmax/NatsReplicationPhase0Test.java"

RUNS="${RUNS:-3}"
RUN_SECONDS="${RUN_SECONDS:-30}"
NATS_IMAGE="${NATS_IMAGE:-nats:2.10-alpine}"
NATS_PORT="${NATS_PORT:-14223}"
NATS_MONITOR_PORT="${NATS_MONITOR_PORT:-18223}"
NATS_URL="${NATS_URL:-nats://127.0.0.1:${NATS_PORT}}"
START_NATS="${START_NATS:-1}"
RUN_VERIFICATION="${RUN_VERIFICATION:-1}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS_DIR="${RESULTS_DIR:-${SCRIPT_DIR}/results/nats-replication-phase0-${TIMESTAMP}}"
NATS_CONTAINER="traderx-yu10-phase0-$$"
NATS_DATA_DIR="${RESULTS_DIR}/nats-data"
RESULT_LINES="${RESULTS_DIR}/phase0-results.txt"

if [[ ! -f "${TEST_SOURCE}" ]]; then
  echo "Missing generated Phase-0 test: ${TEST_SOURCE}" >&2
  echo "Generate YU02 under the shared generation lock, then retry." >&2
  exit 2
fi

mkdir -p "${NATS_DATA_DIR}"

cleanup() {
  if [[ "${START_NATS}" == "1" ]]; then
    docker stop "${NATS_CONTAINER}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "${START_NATS}" == "1" ]]; then
  docker run --detach --rm \
    --name "${NATS_CONTAINER}" \
    --publish "${NATS_PORT}:4222" \
    --publish "${NATS_MONITOR_PORT}:8222" \
    --volume "${NATS_DATA_DIR}:/data" \
    "${NATS_IMAGE}" -js -sd /data -m 8222 >/dev/null

  for _ in $(seq 1 80); do
    if curl --fail --silent "http://127.0.0.1:${NATS_MONITOR_PORT}/healthz" >/dev/null; then
      break
    fi
    sleep 0.25
  done
  curl --fail --silent "http://127.0.0.1:${NATS_MONITOR_PORT}/healthz" >/dev/null
fi

{
  echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "branch=$(git -C "${REPO_ROOT}" branch --show-current)"
  echo "head=$(git -C "${REPO_ROOT}" rev-parse HEAD)"
  echo "target_dir=${TARGET_DIR}"
  echo "nats_url=${NATS_URL}"
  echo "nats_image=${NATS_IMAGE}"
  echo "nats_image_id=$(docker image inspect --format '{{.Id}}' "${NATS_IMAGE}" 2>/dev/null || echo external)"
  echo "runs=${RUNS}"
  echo "run_seconds=${RUN_SECONDS}"
  echo "journal_batch_records=1024"
  echo "publish_batch=256"
  echo "record_bytes=64"
  echo "warmup_events=65536"
  uname -a
  java -version 2>&1
  docker version --format 'docker_client={{.Client.Version}} docker_server={{.Server.Version}}' 2>/dev/null || true
} > "${RESULTS_DIR}/config.txt"

run_mode() {
  local mode="$1"
  local log_file="${RESULTS_DIR}/${mode}.gradle.log"
  local xml_file="${TARGET_DIR}/build/test-results/test/TEST-${TEST_CLASS}.xml"

  echo "Running ${mode}: ${RUNS} x ${RUN_SECONDS}s"
  if [[ "${mode}" == "onring" ]]; then
    (
      cd "${TARGET_DIR}"
      env -u BLP_REPLICATION_ACK_MODE \
        NATS_REPLICATION_PHASE0=true \
        NATS_REPLICATION_BENCH_URL="${NATS_URL}" \
        NATS_REPLICATION_BENCH_RUNS="${RUNS}" \
        NATS_REPLICATION_BENCH_SECONDS="${RUN_SECONDS}" \
        ./gradlew cleanTest test \
          --tests "${TEST_CLASS}.compareJournaledControlWithFileBackedReplication" \
          --console=plain
    ) 2>&1 | tee "${log_file}"
  else
    (
      cd "${TARGET_DIR}"
      env BLP_REPLICATION_ACK_MODE=durable \
        NATS_REPLICATION_PHASE0=true \
        NATS_REPLICATION_BENCH_URL="${NATS_URL}" \
        NATS_REPLICATION_BENCH_RUNS="${RUNS}" \
        NATS_REPLICATION_BENCH_SECONDS="${RUN_SECONDS}" \
        ./gradlew cleanTest test \
          --tests "${TEST_CLASS}.compareJournaledControlWithFileBackedReplication" \
          --console=plain
    ) 2>&1 | tee "${log_file}"
  fi

  grep -o 'PHASE0_RESULT[^<]*' "${xml_file}" | tee -a "${RESULT_LINES}"
}

run_mode onring
run_mode durable

if [[ "${RUN_VERIFICATION}" == "1" ]]; then
  (
    cd "${TARGET_DIR}"
    env NATS_REPLICATION_ALLOCATION_GATE=true \
      NATS_REPLICATION_ACK_CORRECTNESS=true \
      NATS_REPLICATION_BENCH_URL="${NATS_URL}" \
      ./gradlew cleanTest test \
        --tests "${TEST_CLASS}.recordsRealReplicatorAllocationBudget" \
        --tests "${TEST_CLASS}.durableAckWaitsForTheFollowerGatingSequenceWhileOnringDoesNot" \
        --console=plain
  ) 2>&1 | tee "${RESULTS_DIR}/verification.gradle.log"
  grep -o 'REAL_REPLICATOR_ALLOCATION[^<]*' \
    "${TARGET_DIR}/build/test-results/test/TEST-${TEST_CLASS}.xml" \
    | tee "${RESULTS_DIR}/allocation-result.txt"
fi

echo "Results written to ${RESULTS_DIR}"
