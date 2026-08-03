#!/usr/bin/env bash
# Container-adapted Stage 2 driver (see scripts/ingest-taq-stage2-from-gcs.sh in the repo for the
# same logic run from a local machine). Streams each raw TAQ zip already landed in
# gs://traderx-501015-tick-store/_raw-taq/ (Stage 1) straight through the appropriate normalizer
# into gs://traderx-501015-tick-store/ticks -- never lands the raw zip or decompressed CSV on the
# pod's local disk. Runs to completion once, as a k8s Job (not the standing capture.py Deployment).
set -euo pipefail

BUCKET="gs://traderx-501015-tick-store"
RAW_PREFIX="${BUCKET}/_raw-taq"
OUT_PREFIX="${BUCKET}/ticks"

if [[ -n "${GCS_SA_KEY_FILE:-}" ]]; then
  gcloud auth activate-service-account --key-file="${GCS_SA_KEY_FILE}"
fi

echo "[stage2] listing ${RAW_PREFIX}"
mapfile -t RAW_FILES < <(gcloud storage ls "${RAW_PREFIX}/**/*.zip" 2>/dev/null | sort)

if [[ ${#RAW_FILES[@]} -eq 0 ]]; then
  echo "[stage2] no raw TAQ files found under ${RAW_PREFIX} -- nothing to do"
  exit 0
fi

process_one() {
  local raw="$1"
  local base
  base="$(basename "${raw}")"

  if [[ "${base}" == taq_quotes_*_csv.zip ]]; then
    normalizer="ingest_taq_quotes.py"
    ymd="$(echo "${base}" | sed -E 's/taq_quotes_([0-9]{4})([0-9]{2})([0-9]{2})_csv\.zip/\1-\2-\3/')"
  elif [[ "${base}" == taq_trades_*_csv.zip ]]; then
    normalizer="ingest_taq_trades.py"
    ymd="$(echo "${base}" | sed -E 's/taq_trades_(.*)_csv\.zip/\1/')"
  else
    echo "[stage2] skip (unrecognized filename pattern): ${base}"
    return 0
  fi

  echo "[stage2] processing ${base} (${normalizer})"
  # funzip exits nonzero on a single-entry zip's trailing bytes (and gcloud storage cat can
  # SIGPIPE) even when the entry decompressed fine, so pipefail would mark a fully-successful
  # ingest as FAILED -- and the Indexed Job would then retry it, writing a *duplicate* full copy
  # of the partition each time. Key success on the normalizer's own exit code (last pipe element).
  local rc
  set +e
  gcloud storage cat "${raw}" 2>/dev/null | funzip 2>/dev/null | python3 "${normalizer}" --date "${ymd}" --out "${OUT_PREFIX}"
  rc=${PIPESTATUS[2]}
  set -e
  if [[ "${rc}" -eq 0 ]]; then
    echo "[stage2] done: ${base}"
    return 0
  else
    echo "[stage2] FAILED: ${base} (normalizer exit ${rc})"
    return 1
  fi
}

# JOB_COMPLETION_INDEX (set by a k8s Indexed Job) selects exactly one file from the sorted list
# so N pods can run concurrently with zero shared-state coordination -- each index is independent,
# no risk of two pods double-processing the same file. Falls back to the original sequential
# for-loop (every file, one pod) when unset, e.g. for local/manual runs.
if [[ -n "${JOB_COMPLETION_INDEX:-}" ]]; then
  idx="${JOB_COMPLETION_INDEX}"
  if [[ "${idx}" -ge ${#RAW_FILES[@]} ]]; then
    echo "[stage2] index ${idx} >= ${#RAW_FILES[@]} files -- nothing to do for this index"
    exit 0
  fi
  process_one "${RAW_FILES[${idx}]}"
  exit $?
fi

fail_count=0
for raw in "${RAW_FILES[@]}"; do
  process_one "${raw}" || fail_count=$((fail_count + 1))
done

echo "[stage2] complete. ${fail_count} file(s) failed."
exit "$([[ ${fail_count} -eq 0 ]] && echo 0 || echo 1)"
