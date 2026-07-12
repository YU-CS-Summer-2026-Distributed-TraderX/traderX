#!/usr/bin/env python3
"""Normalizes a NYSE Daily TAQ Consolidated Trades (CT) CSV into the unified tick-store Parquet
schema. Confirmed column layout (research.md Decision 4, confirmed against a real GCS-streamed
sample from taq_trades_mar2025_csv.zip):

    DATE,TIME_M,EX,SYM_ROOT,SYM_SUFFIX,TR_SCOND,SIZE,PRICE,TR_STOP_IND,TR_CORR,TR_SEQNUM,TR_ID,TR_SOURCE,TR_RF

Required, not optional: YU08's DuckDbVolumeProfileSource queries `WHERE event_type = 'trade'` for
its historical volume profile — TAQ quotes alone (event_type='quote') never match that query, so
trades ingestion is the actual dependency for real-market-data VWAP, not a nice-to-have.

Reads from stdin, same streaming design as ingest_taq_quotes.py (research.md Decision 5) — the
caller streams a zip entry (from a local file or, once landed in GCS via Stage 1, `gcloud storage
cat | funzip`) straight into this process without ever extracting the decompressed CSV to disk:

    gcloud storage cat gs://traderx-501015-tick-store/_raw-taq/TAQ_March2025/taq_trades_mar2025_csv.zip \
      | funzip | python3 ingest_taq_trades.py --date 2025-03-03 --out gs://traderx-501015-tick-store/ticks
"""
import argparse
import logging
import sys

import duckdb

from gcs import configure_gcs, is_gcs_path

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("tick-store.ingest_taq_trades")


def ingest(con, csv_path, out_dir):
    """csv_path is a real path or '/dev/stdin'. Returns the row count written.

    Same single-pass design as ingest_taq_quotes.py.ingest: a pipe is not seekable, so column-
    layout mismatches surface as a DuckDB binder error from the COPY itself; row-level tolerance is
    a WHERE filter excluding rows with a missing symbol/date/time from the write rather than
    aborting the run.
    """
    try:
        written = con.execute(
            "COPY (SELECT "
            "  SYM_ROOT AS symbol, "
            "  'trade' AS event_type, "
            "  DATE + TIME_M AS ts, "
            "  PRICE AS price, "
            "  SIZE AS size, "
            "  NULL::DOUBLE AS bid_price, NULL::BIGINT AS bid_size, "
            "  NULL::DOUBLE AS ask_price, NULL::BIGINT AS ask_size, "
            "  EX AS venue, "
            "  'taq' AS source, "
            "  TR_SEQNUM AS seq, "
            "  now() AS ingested_at, "
            "  DATE AS dt "
            f"FROM read_csv('{csv_path}', header=true, auto_detect=true) "
            "WHERE SYM_ROOT IS NOT NULL AND DATE IS NOT NULL AND TIME_M IS NOT NULL) "
            f"TO '{out_dir}' (FORMAT PARQUET, COMPRESSION ZSTD, "
            "PARTITION_BY (source, dt, symbol), FILENAME_PATTERN '{uuid}', "
            "OVERWRITE_OR_IGNORE true)"
        ).fetchone()[0]
    except duckdb.BinderException as exc:
        raise ValueError(f"TAQ trades CSV does not match the expected CT column layout: {exc}") from None

    if written == 0:
        raise ValueError("no valid rows parsed from TAQ trades CSV (zero rows with symbol/date/time)")
    return written


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", required=True, help="session date, YYYY-MM-DD (for logging only; the row's own DATE column drives partitioning)")
    parser.add_argument("--out", required=True, help="Parquet store root (e.g. gs://traderx-501015-tick-store/ticks)")
    parser.add_argument("--in", dest="input", default="/dev/stdin", help="CSV path (default: stdin, i.e. piped from unzip -p / funzip)")
    args = parser.parse_args(argv)

    con = duckdb.connect()
    if is_gcs_path(args.out):
        configure_gcs(con)
    try:
        count = ingest(con, args.input, args.out)
    except ValueError as exc:
        log.error("%s", exc)
        return 1
    log.info("ingested %d TAQ trade rows for %s into %s", count, args.date, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
