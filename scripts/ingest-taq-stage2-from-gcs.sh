#!/usr/bin/env bash
# Stage 2 of TAQ ingestion (research.md Decision 6 / Decision 5 extended to GCS-native sourcing):
# streams each raw TAQ zip already landed in gs://traderx-501015-tick-store/_raw-taq/ (Stage 1,
# OneDrive -> GCS) straight through the appropriate normalizer into the final Parquet layout at
# gs://traderx-501015-tick-store/ticks -- never lands the raw zip or the decompressed CSV on local
# disk. Idempotent across reruns via a local completed-list file (each ingest run writes uniquely
# named Parquet files, so a naive rerun would duplicate rows without this guard).
set -euo pipefail

BUCKET="gs://traderx-501015-tick-store"
RAW_PREFIX="${BUCKET}/_raw-taq"
OUT_PREFIX="${BUCKET}/ticks"
TICK_STORE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../specs/YU07-historical-tick-store/generation/runtime-overrides/tick-store" && pwd)"
STATE_FILE="${STATE_FILE:-${TICK_STORE_DIR}/.stage2-completed.txt}"
touch "${STATE_FILE}"

echo "[stage2] listing ${RAW_PREFIX}"
mapfile -t RAW_FILES < <(gcloud storage ls "${RAW_PREFIX}/**/*.zip" 2>/dev/null)

if [[ ${#RAW_FILES[@]} -eq 0 ]]; then
  echo "[stage2] no raw TAQ files found under ${RAW_PREFIX} -- nothing to do"
  exit 0
fi

for raw in "${RAW_FILES[@]}"; do
  base="$(basename "${raw}")"

  if grep -qxF "${base}" "${STATE_FILE}"; then
    echo "[stage2] skip (already ingested): ${base}"
    continue
  fi

  if [[ "${base}" == taq_quotes_*_csv.zip ]]; then
    kind="quotes"
    normalizer="ingest_taq_quotes.py"
    # taq_quotes_YYYYMMDD_csv.zip -> YYYY-MM-DD (label only, ingest.py's own DATE column drives partitioning)
    ymd="$(echo "${base}" | sed -E 's/taq_quotes_([0-9]{4})([0-9]{2})([0-9]{2})_csv\.zip/\1-\2-\3/')"
  elif [[ "${base}" == taq_trades_*_csv.zip ]]; then
    kind="trades"
    normalizer="ingest_taq_trades.py"
    # monthly combined file, e.g. taq_trades_mar2025_csv.zip -- date label is cosmetic only
    ymd="$(echo "${base}" | sed -E 's/taq_trades_(.*)_csv\.zip/\1/')"
  else
    echo "[stage2] skip (unrecognized filename pattern): ${base}"
    continue
  fi

  echo "[stage2] ${kind} <- ${raw}"

  # Determine the single CSV entry name inside the zip (small metadata-only op via gsutil's
  # cat over the archive's local file header; a real central-directory listing would need the
  # full file, which we deliberately never fetch -- so instead pipe straight through funzip,
  # which reads the local header sequentially and needs no entry name at all for a single-entry
  # archive).
  if gcloud storage cat "${raw}" 2>/dev/null \
      | funzip 2>/dev/null \
      | (cd "${TICK_STORE_DIR}" && python3 "${normalizer}" --date "${ymd}" --out "${OUT_PREFIX}"); then
    echo "${base}" >> "${STATE_FILE}"
    echo "[stage2] done: ${base}"
  else
    echo "[stage2] FAILED: ${base} -- not marked complete, will retry next run"
  fi
done

echo "[stage2] all raw TAQ files processed (see ${STATE_FILE} for completed list)"
