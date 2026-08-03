import os
import sys
import threading
import zipfile

import duckdb
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import ingest_taq_quotes  # noqa: E402

# Real sample rows streamed (via `unzip -p`) from the Feb2025 OneDrive drop's
# taq_quotes_20250211_csv.zip during this state's research — not synthetic fixture data.
SAMPLE_CQ_CSV = """DATE,TIME_M,EX,BID,BIDSIZ,ASK,ASKSIZ,QU_COND,QU_SEQNUM,NATBBO_IND,QU_CANCEL,QU_SOURCE,SYM_ROOT,SYM_SUFFIX
2025-02-11,3:59:00.041571072,K,0,0,0,0,R,86,O,,C,A,
2025-02-11,4:00:00.050697472,K,57.6,1,147.5,2,R,745,G,,C,A,
2025-02-11,4:05:00.093534464,P,134.86,1,0,0,R,4732,A,,C,A,
2025-02-11,4:09:40.254700288,T,123.76,1,229.9,2,R,7852,A,,C,A,
"""


def test_ingest_real_taq_sample_produces_expected_rows(tmp_path):
    csv_path = tmp_path / "sample.csv"
    csv_path.write_text(SAMPLE_CQ_CSV)
    out_dir = tmp_path / "out"

    con = duckdb.connect()
    written = ingest_taq_quotes.ingest(con, str(csv_path), str(out_dir))
    assert written == 4

    rows = con.execute(
        f"SELECT symbol, event_type, ts, bid_price, bid_size, ask_price, ask_size, venue, source, seq "
        f"FROM read_parquet('{out_dir}/**/*.parquet', hive_partitioning=true) ORDER BY seq"
    ).fetchall()
    assert len(rows) == 4
    symbol, event_type, ts, bid_price, bid_size, ask_price, ask_size, venue, source, seq = rows[0]
    assert symbol == "A"
    assert event_type == "quote"
    assert source == "taq"
    assert venue == "K"
    assert seq == 86
    assert bid_price == 0.0
    # nanosecond TIME_M truncated to DuckDB's microsecond TIMESTAMP (research.md Decision 4):
    # source "3:59:00.041571072" -> stored ts keeps only the first 6 fractional digits.
    assert ts.microsecond == 41571

    last_row_ask = rows[3]
    assert last_row_ask[5] == 229.9  # ASK from the 4:09:40 row


def test_ingest_skips_rows_missing_symbol_date_or_time(tmp_path):
    csv_path = tmp_path / "partial.csv"
    csv_path.write_text(
        "DATE,TIME_M,EX,BID,BIDSIZ,ASK,ASKSIZ,QU_COND,QU_SEQNUM,NATBBO_IND,QU_CANCEL,QU_SOURCE,SYM_ROOT,SYM_SUFFIX\n"
        "2025-02-11,3:59:00.041571072,K,0,0,0,0,R,86,O,,C,A,\n"
        ",4:00:00.050697472,K,57.6,1,147.5,2,R,745,G,,C,A,\n"  # missing DATE -- must be skipped
        "2025-02-11,4:05:00.093534464,P,134.86,1,0,0,R,4732,A,,C,A,\n"
    )
    out_dir = tmp_path / "out"
    con = duckdb.connect()
    written = ingest_taq_quotes.ingest(con, str(csv_path), str(out_dir))
    assert written == 2  # the malformed row does not abort the run, and is excluded


def test_ingest_raises_on_zero_valid_rows(tmp_path):
    csv_path = tmp_path / "empty.csv"
    csv_path.write_text(
        "DATE,TIME_M,EX,BID,BIDSIZ,ASK,ASKSIZ,QU_COND,QU_SEQNUM,NATBBO_IND,QU_CANCEL,QU_SOURCE,SYM_ROOT,SYM_SUFFIX\n"
        "2025-02-11,3:59:00.041571072,K,0,0,0,0,R,86,O,,C,,\n"  # every row missing SYM_ROOT
    )
    con = duckdb.connect()
    with pytest.raises(ValueError, match="no valid rows"):
        ingest_taq_quotes.ingest(con, str(csv_path), str(tmp_path / "out"))


def test_ingest_raises_clear_error_on_wrong_column_layout(tmp_path):
    csv_path = tmp_path / "wrong.csv"
    csv_path.write_text("A,B,C\n1,2,3\n")
    con = duckdb.connect()
    with pytest.raises(ValueError, match="does not match the expected CQ column layout"):
        ingest_taq_quotes.ingest(con, str(csv_path), str(tmp_path / "out"))


def test_ingest_multiple_cq_files_preserves_both_batches(tmp_path):
    out_dir = tmp_path / "out"
    con = duckdb.connect()
    for index, symbol in enumerate(("A", "IBM"), start=1):
        csv_path = tmp_path / f"batch-{index}.csv"
        csv_path.write_text(SAMPLE_CQ_CSV.replace(",A,\n", f",{symbol},\n"))
        assert ingest_taq_quotes.ingest(con, str(csv_path), str(out_dir)) == 4

    rows = con.execute(
        f"SELECT symbol, count(*) FROM read_parquet('{out_dir}/**/*.parquet', hive_partitioning=true) "
        "GROUP BY symbol ORDER BY symbol"
    ).fetchall()
    assert rows == [("A", 4), ("IBM", 4)]


def test_ingest_streams_csv_directly_from_stdlib_zipfile(tmp_path):
    archive = tmp_path / "quotes.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("quotes.csv", SAMPLE_CQ_CSV)

    read_fd, write_fd = os.pipe()

    def stream_member():
        with zipfile.ZipFile(archive) as zf, zf.open("quotes.csv") as source, \
                os.fdopen(write_fd, "wb") as sink:
            while chunk := source.read(8192):
                sink.write(chunk)

    writer = threading.Thread(target=stream_member, daemon=True)
    writer.start()
    try:
        con = duckdb.connect()
        written = ingest_taq_quotes.ingest(con, f"/dev/fd/{read_fd}", str(tmp_path / "out"))
        assert written == 4
    finally:
        os.close(read_fd)
        writer.join(timeout=5)
    assert not writer.is_alive()


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
