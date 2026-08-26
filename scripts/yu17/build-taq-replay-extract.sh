#!/usr/bin/env bash
# ADR-070: fetch the demo universe's tick partitions, build the resampled extract, upload it.
#
# Runs anywhere with gcloud auth + python3(pyarrow, pandas). COST DEPENDS ON WHERE:
#   in us-east1 (the bucket's region)  GCS reads are free;
#   to a laptop                        ~29 GiB for the default 23 symbols x 40 days (~$3.50 at
#                                      $0.12/GB, ~2h at the ~4 MiB/s measured 2026-08-26).
# Address partitions by symbol= prefix, NEVER by listing whole dt= days — the tree holds ~816k
# objects and the 23-symbol slice is ~2k of them.
#
# The extract lands at ${EXTRACT_URI}; bring-up (start-cluster-kind.sh) fetches it into the
# taq-replay-extract Secret. Nothing durable enters the repo (ADR-068's durability rule).
set -euo pipefail

BUCKET="${BUCKET:-gs://traderx-501015-tick-store}"
EXTRACT_URI="${EXTRACT_URI:-${BUCKET}/replay/taq-replay-2025-02/extract-v1.json.gz}"
WORK="${WORK:-${TMPDIR:-/tmp}/taq-replay-build}"
SYMBOLS="${SYMBOLS:-AAPL,MSFT,AMZN,META,NVDA,TSLA,IBM,BAC,C,JPM,GS,MS,UBS,DB,COF,DFS,FIS,FNF,SPY,QQQ,IWM,VTI,GLD}"
HERE="$(cd "$(dirname "$0")" && pwd)"

mkdir -p "${WORK}/ticks"
echo "[fetch] ${SYMBOLS//,/ } -> ${WORK}/ticks"
for d in $(gcloud storage ls "${BUCKET}/ticks/source=taq/" | sed -n 's|.*/dt=\([0-9-]*\)/$|\1|p'); do
  for s in ${SYMBOLS//,/ }; do
    dest="${WORK}/ticks/dt=${d}/symbol=${s}"
    if [ -z "$(ls "${dest}" 2>/dev/null)" ]; then   # resumable: skip what a prior run fetched
      mkdir -p "${dest}"
      gcloud storage cp "${BUCKET}/ticks/source=taq/dt=${d}/symbol=${s}/*.parquet" "${dest}/" >/dev/null
    fi
  done
  echo "[fetch] dt=${d} done"
done

python3 "${HERE}/build-taq-replay-extract.py" \
  --src "${WORK}/ticks" --out "${WORK}/extract-v1.json.gz" --symbols "${SYMBOLS}" "$@"

echo "[upload] ${EXTRACT_URI}"
gcloud storage cp "${WORK}/extract-v1.json.gz" "${EXTRACT_URI}"
echo "[ok] fetch the working tree away with: rm -rf ${WORK}"
