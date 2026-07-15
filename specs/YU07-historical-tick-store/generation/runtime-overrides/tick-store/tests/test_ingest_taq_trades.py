import os
import sys

import duckdb
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import ingest_taq_trades  # noqa: E402

# Format-accurate NYSE Daily TAQ Consolidated Trades (CT) sample — the standard CT column layout,
# same DATE/TIME_M encoding as the confirmed CQ file (ISO date + H:MM:SS.nanoseconds). Constructed to
# the documented layout: the real Daily TAQ Trades corpus was not hydrated at writing time, so
# SC-TS06's real-file verification is still pending — but the normalizer + schema mapping are tested.
SAMPLE_CT_CSV = """DATE,TIME_M,EX,SYM_ROOT,SYM_SUFFIX,TR_SCOND,SIZE,PRICE,TR_STOPIND,TR_CORR,TR_SEQNUM,TR_SOURCE,TR_RF
2025-02-11,9:30:00.123456789,K,A,,@,100,145.32,N,00,5001,C,
2025-02-11,9:30:01.234567890,N,A,,@,200,145.35,N,00,5002,C,
2025-02-11,9:30:02.345678901,P,A,,@,300,145.30,N,00,5003,C,
"""


def test_ingest_ct_sample_produces_trade_rows(tmp_path):
    csv_path = tmp_path / "sample.csv"
    csv_path.write_text(SAMPLE_CT_CSV)
    out_dir = tmp_path / "out"

    con = duckdb.connect()
    written = ingest_taq_trades.ingest(con, str(csv_path), str(out_dir))
    assert written == 3

    rows = con.execute(
        "SELECT symbol, event_type, ts, price, size, bid_price, ask_price, venue, source, seq "
        f"FROM read_parquet('{out_dir}/**/*.parquet', hive_partitioning=true) ORDER BY seq"
    ).fetchall()
    assert len(rows) == 3
    symbol, event_type, ts, price, size, bid_price, ask_price, venue, source, seq = rows[0]
    assert symbol == "A"
    assert event_type == "trade"
    assert source == "taq"
    assert venue == "K"
    assert price == 145.32
    assert size == 100
    assert bid_price is None and ask_price is None  # trade rows carry no bid/ask
    assert seq == 5001
    # nanosecond TIME_M truncated to DuckDB's microsecond TIMESTAMP
    assert ts.microsecond == 123456


def test_ct_rows_vwap_uniformly_with_the_store(tmp_path):
    # the whole point: TAQ trade prints weight into a VWAP exactly like live trades
    csv_path = tmp_path / "sample.csv"
    csv_path.write_text(SAMPLE_CT_CSV)
    out_dir = tmp_path / "out"
    con = duckdb.connect()
    ingest_taq_trades.ingest(con, str(csv_path), str(out_dir))
    vwap = con.execute(
        "SELECT sum(price*size)/sum(size) "
        f"FROM read_parquet('{out_dir}/**/*.parquet', hive_partitioning=true) WHERE event_type='trade'"
    ).fetchone()[0]
    # (145.32*100 + 145.35*200 + 145.30*300) / 600 = 87192 / 600 = 145.32
    assert abs(vwap - 145.32) < 1e-4


def test_ingest_skips_rows_missing_symbol_date_time_or_price(tmp_path):
    csv_path = tmp_path / "partial.csv"
    csv_path.write_text(
        "DATE,TIME_M,EX,SYM_ROOT,SYM_SUFFIX,TR_SCOND,SIZE,PRICE,TR_STOPIND,TR_CORR,TR_SEQNUM,TR_SOURCE,TR_RF\n"
        "2025-02-11,9:30:00.123456789,K,A,,@,100,145.32,N,00,5001,C,\n"
        "2025-02-11,9:30:01.234567890,N,A,,@,200,,N,00,5002,C,\n"   # missing PRICE -> skipped
        "2025-02-11,9:30:02.345678901,P,A,,@,300,145.30,N,00,5003,C,\n"
    )
    out_dir = tmp_path / "out"
    con = duckdb.connect()
    written = ingest_taq_trades.ingest(con, str(csv_path), str(out_dir))
    assert written == 2


def test_ingest_rejects_wrong_layout(tmp_path):
    csv_path = tmp_path / "bad.csv"
    csv_path.write_text("FOO,BAR\n1,2\n")
    out_dir = tmp_path / "out"
    con = duckdb.connect()
    with pytest.raises(ValueError):
        ingest_taq_trades.ingest(con, str(csv_path), str(out_dir))


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
