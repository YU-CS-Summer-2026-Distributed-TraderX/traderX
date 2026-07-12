import os
import sys

import duckdb
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import ingest_taq_trades  # noqa: E402

# Real sample rows streamed (via `gcloud storage cat | funzip`) from
# gs://traderx-501015-tick-store/_raw-taq/TAQ_March2025/taq_trades_mar2025_csv.zip during this
# state's research — not synthetic fixture data.
SAMPLE_CT_CSV = """DATE,TIME_M,EX,SYM_ROOT,SYM_SUFFIX,TR_SCOND,SIZE,PRICE,TR_STOP_IND,TR_CORR,TR_SEQNUM,TR_ID,TR_SOURCE,TR_RF
2025-03-03,4:06:02.246831104,T,A,,TI,1,128.69,N,00,3680,62879130347078,C,
2025-03-03,4:06:13.327472896,T,A,,T,100,128.6,N,00,3692,62879130399818,C,
2025-03-03,4:06:13.327505920,T,A,,FTI,1,128.59,N,00,3693,62879130399819,C,
2025-03-03,4:21:32.420604416,P,A,,TI,1,127.93,N,00,4365,52983525035603,C,
"""


def test_ingest_real_taq_trades_sample_produces_expected_rows(tmp_path):
    csv_path = tmp_path / "sample.csv"
    csv_path.write_text(SAMPLE_CT_CSV)
    out_dir = tmp_path / "out"

    con = duckdb.connect()
    written = ingest_taq_trades.ingest(con, str(csv_path), str(out_dir))
    assert written == 4

    rows = con.execute(
        f"SELECT symbol, event_type, ts, price, size, venue, source, seq "
        f"FROM read_parquet('{out_dir}/**/*.parquet', hive_partitioning=true) ORDER BY seq"
    ).fetchall()
    assert len(rows) == 4
    symbol, event_type, ts, price, size, venue, source, seq = rows[0]
    assert symbol == "A"
    assert event_type == "trade"
    assert source == "taq"
    assert venue == "T"
    assert seq == 3680
    assert price == 128.69
    assert size == 1
    # nanosecond TIME_M truncated to DuckDB's microsecond TIMESTAMP (same as quotes):
    # source "4:06:02.246831104" -> stored ts keeps only the first 6 fractional digits.
    assert ts.microsecond == 246831

    last_row = rows[3]
    assert last_row[3] == 127.93  # PRICE from the 4:21:32 row
    assert last_row[4] == 1  # SIZE from the same row


def test_ingest_skips_rows_missing_symbol_date_or_time(tmp_path):
    csv_path = tmp_path / "partial.csv"
    csv_path.write_text(
        "DATE,TIME_M,EX,SYM_ROOT,SYM_SUFFIX,TR_SCOND,SIZE,PRICE,TR_STOP_IND,TR_CORR,TR_SEQNUM,TR_ID,TR_SOURCE,TR_RF\n"
        "2025-03-03,4:06:02.246831104,T,A,,TI,1,128.69,N,00,3680,62879130347078,C,\n"
        ",4:06:13.327472896,T,A,,T,100,128.6,N,00,3692,62879130399818,C,\n"  # missing DATE -- must be skipped
        "2025-03-03,4:06:13.327505920,T,A,,FTI,1,128.59,N,00,3693,62879130399819,C,\n"
    )
    out_dir = tmp_path / "out"
    con = duckdb.connect()
    written = ingest_taq_trades.ingest(con, str(csv_path), str(out_dir))
    assert written == 2  # the malformed row does not abort the run, and is excluded


def test_ingest_raises_on_zero_valid_rows(tmp_path):
    csv_path = tmp_path / "empty.csv"
    csv_path.write_text(
        "DATE,TIME_M,EX,SYM_ROOT,SYM_SUFFIX,TR_SCOND,SIZE,PRICE,TR_STOP_IND,TR_CORR,TR_SEQNUM,TR_ID,TR_SOURCE,TR_RF\n"
        "2025-03-03,4:06:02.246831104,T,,,TI,1,128.69,N,00,3680,62879130347078,C,\n"  # every row missing SYM_ROOT
    )
    con = duckdb.connect()
    with pytest.raises(ValueError, match="no valid rows"):
        ingest_taq_trades.ingest(con, str(csv_path), str(tmp_path / "out"))


def test_ingest_raises_clear_error_on_wrong_column_layout(tmp_path):
    csv_path = tmp_path / "wrong.csv"
    csv_path.write_text("A,B,C\n1,2,3\n")
    con = duckdb.connect()
    with pytest.raises(ValueError, match="does not match the expected CT column layout"):
        ingest_taq_trades.ingest(con, str(csv_path), str(tmp_path / "out"))


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
