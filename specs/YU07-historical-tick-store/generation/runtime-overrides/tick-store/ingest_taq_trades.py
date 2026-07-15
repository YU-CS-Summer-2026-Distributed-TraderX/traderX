#!/usr/bin/env python3
"""Normalizes a NYSE Daily TAQ Consolidated Trades (CT) CSV into the unified tick-store Parquet
schema — the trades sibling of ingest_taq_quotes.py (FR-TS08). Standard Daily TAQ Trades layout:

    DATE,TIME_M,EX,SYM_ROOT,SYM_SUFFIX,TR_SCOND,SIZE,PRICE,TR_STOPIND,TR_CORR,TR_SEQNUM,TR_SOURCE,TR_RF

Like the quotes normalizer, reads from stdin so the caller can stream straight out of the source zip
without extracting the decompressed CSV to disk (research.md Decision 5):

    unzip -p taq_trades_20250211_csv.zip <entry>.csv | python3 ingest_taq_trades.py --date 2025-02-11 --out /data/ticks

Produces `event_type='trade'`, `source='taq'` rows with price/size populated (bid/ask null) — so a
single VWAP query over the store weights real TAQ prints the same way it weights live trades.

ponytail: TR_CORR-based exclusion of corrected/cancelled prints is a refinement left for when the
real Daily TAQ Trades corpus is hydrated (SC-TS06); the filter mirrors the quotes normalizer's
symbol/date/time WHERE until the dataset's TR_CORR encoding is confirmed against a real file.
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

    Single-pass read (a piped stdin is not seekable); a column-layout mismatch surfaces as a DuckDB
    binder error, and row-level tolerance is a WHERE filter that drops rows missing
    symbol/date/time/price rather than aborting the run.
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
            "WHERE SYM_ROOT IS NOT NULL AND DATE IS NOT NULL AND TIME_M IS NOT NULL AND PRICE IS NOT NULL) "
            f"TO '{out_dir}' (FORMAT PARQUET, COMPRESSION ZSTD, "
            "PARTITION_BY (source, dt, symbol), FILENAME_PATTERN '{uuid}', "
            "OVERWRITE_OR_IGNORE true)"
        ).fetchone()[0]
    except duckdb.BinderException as exc:
        raise ValueError(f"TAQ trades CSV does not match the expected CT column layout: {exc}") from None

    if written == 0:
        raise ValueError("no valid rows parsed from TAQ trades CSV (zero rows with symbol/date/time/price)")
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
    log.info("ingested %d TAQ trade rows for %s into %s", count, args.date, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
