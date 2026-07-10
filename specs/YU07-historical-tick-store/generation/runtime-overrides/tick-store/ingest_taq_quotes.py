#!/usr/bin/env python3
"""Normalizes a NYSE Daily TAQ Consolidated Quotes (CQ) CSV into the unified tick-store Parquet
schema. Confirmed column layout (research.md Decision 4):

    DATE,TIME_M,EX,BID,BIDSIZ,ASK,ASKSIZ,QU_COND,QU_SEQNUM,NATBBO_IND,QU_CANCEL,QU_SOURCE,SYM_ROOT,SYM_SUFFIX

Reads from stdin so the caller can stream straight out of the source zip archive without ever
extracting the decompressed CSV to disk (research.md Decision 5):

    unzip -p taq_quotes_20250211_csv.zip <entry>.csv | python3 ingest_taq_quotes.py --date 2025-02-11 --out /data/ticks

Only TAQ quotes are implemented in this state — the trades file's column layout was not confirmed
at writing time (see research.md Decision 4); no normalizer is written against an unconfirmed
format.
"""
import argparse
import logging
import sys

import duckdb

from gcs import configure_gcs, is_gcs_path

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("tick-store.ingest_taq_quotes")


def ingest(con, csv_path, out_dir):
    """csv_path is a real path or '/dev/stdin'. Returns the row count written.

    Reads the source exactly once: a pipe (stdin, fed by `unzip -p`) is not seekable, so this
    cannot DESCRIBE/count/COPY in separate passes the way a real file would allow. Column-layout
    mismatches surface as a DuckDB binder error from the COPY itself (referencing a column that
    doesn't exist); row-level tolerance is a WHERE filter that excludes rows with a missing
    symbol/date/time from the write rather than aborting the run.
    """
    try:
        written = con.execute(
            "COPY (SELECT "
            "  SYM_ROOT AS symbol, "
            "  'quote' AS event_type, "
            "  DATE + TIME_M AS ts, "
            "  NULL::DOUBLE AS price, "
            "  NULL::BIGINT AS size, "
            "  BID AS bid_price, BIDSIZ AS bid_size, ASK AS ask_price, ASKSIZ AS ask_size, "
            "  EX AS venue, "
            "  'taq' AS source, "
            "  QU_SEQNUM AS seq, "
            "  now() AS ingested_at, "
            "  DATE AS dt "
            f"FROM read_csv('{csv_path}', header=true, auto_detect=true) "
            "WHERE SYM_ROOT IS NOT NULL AND DATE IS NOT NULL AND TIME_M IS NOT NULL) "
            f"TO '{out_dir}' (FORMAT PARQUET, COMPRESSION ZSTD, "
            "PARTITION_BY (source, dt, symbol), FILENAME_PATTERN '{uuid}', "
            "OVERWRITE_OR_IGNORE true)"
        ).fetchone()[0]
    except duckdb.BinderException as exc:
        raise ValueError(f"TAQ quotes CSV does not match the expected CQ column layout: {exc}") from None

    if written == 0:
        raise ValueError("no valid rows parsed from TAQ quotes CSV (zero rows with symbol/date/time)")
    return written


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", required=True, help="session date, YYYY-MM-DD (for logging only; the row's own DATE column drives partitioning)")
    parser.add_argument("--out", required=True, help="Parquet store root (e.g. /data/ticks)")
    parser.add_argument("--in", dest="input", default="/dev/stdin", help="CSV path (default: stdin, i.e. piped from unzip -p)")
    args = parser.parse_args(argv)

    con = duckdb.connect()
    if is_gcs_path(args.out):
        configure_gcs(con)
    try:
        count = ingest(con, args.input, args.out)
    except ValueError as exc:
        log.error("%s", exc)
        return 1
    log.info("ingested %d TAQ quote rows for %s into %s", count, args.date, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
