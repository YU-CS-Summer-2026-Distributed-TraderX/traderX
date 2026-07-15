import os
import sys

import duckdb

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import capture  # noqa: E402
import ingest_taq_quotes  # noqa: E402
import ingest_taq_trades  # noqa: E402
from test_ingest_taq_quotes import SAMPLE_CQ_CSV  # noqa: E402
from test_ingest_taq_trades import SAMPLE_CT_CSV  # noqa: E402


def test_live_capture_cq_and_ct_share_one_hive_partitioned_store(tmp_path):
    out_dir = tmp_path / "ticks"
    con = duckdb.connect()
    live = capture.trade_to_row(
        {"security": "A", "quantity": 25, "price": 145.40,
         "updated": "2025-02-11T09:31:00Z"},
        seq=1,
    )
    capture.write_batch(con, [live], str(out_dir))

    cq = tmp_path / "cq.csv"
    cq.write_text(SAMPLE_CQ_CSV)
    ingest_taq_quotes.ingest(con, str(cq), str(out_dir))
    ct = tmp_path / "ct.csv"
    ct.write_text(SAMPLE_CT_CSV)
    ingest_taq_trades.ingest(con, str(ct), str(out_dir))

    rows = con.execute(
        "SELECT source, event_type, count(*) "
        f"FROM read_parquet('{out_dir}/**/*.parquet', hive_partitioning=true) "
        "GROUP BY source, event_type ORDER BY source, event_type"
    ).fetchall()
    assert rows == [("live", "trade", 1), ("taq", "quote", 4), ("taq", "trade", 3)]
