#!/usr/bin/env bash
# Pull the local KDB-X working sample out of the tick store.
#
# Narrow literal paths only. A recursive glob over ticks/ would LIST ~400k objects
# (~10,100 symbol partitions per trading day) before any filter applies.
#
# dt=2025-03-11 is deliberately excluded everywhere: it is the OOM/retry day and its
# file set is unverified.
set -euo pipefail

BUCKET="${BUCKET:-gs://traderx-501015-tick-store}"
DEST="${TICKSTORE_ROOT:-$HOME/dev/lmax/kdb-tickstore/sample}"
DATES="${DATES:-2025-02-03 2025-02-04}"
SYMBOLS="${SYMBOLS:-AAPL MSFT SPY CROX}"

for dt in $DATES; do
  for sym in $SYMBOLS; do
    mkdir -p "$DEST/dt=$dt/symbol=$sym"
    gcloud storage cp \
      "$BUCKET/ticks/source=taq/dt=$dt/symbol=$sym/*.parquet" \
      "$DEST/dt=$dt/symbol=$sym/"
  done
done

echo "sample at $DEST ($(du -sh "$DEST" | cut -f1))"
echo "run:  TICKSTORE_ROOT=$DEST q selfcheck.q"
