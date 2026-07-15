import os
import sys

import duckdb
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import capture  # noqa: E402


def test_price_tick_to_row_maps_pricing_subject():
    row = capture.price_tick_to_row(
        "pricing.IBM",
        {"price": 136.5, "openPrice": 135, "closePrice": 136, "asOf": "2026-07-09T14:00:00.000Z", "source": "sim"},
        seq=1,
    )
    assert row["symbol"] == "IBM"
    assert row["event_type"] == "price_tick"
    assert row["price"] == 136.5
    assert row["ts"] == "2026-07-09T14:00:00.000Z"
    assert row["size"] is None
    assert row["venue"] == "TRADERX"
    assert row["source"] == "live"


def test_envelope_payload_unwraps_traderx_topic_envelope():
    # Live pricing.* / trades messages arrive wrapped as {"topic":..., "payload":{...}} — capture
    # must unwrap to the inner payload (regression: previously KeyError'd on every live tick).
    wrapped = {"topic": "pricing.IBM", "payload": {"ticker": "IBM", "price": 190.2, "asOf": "2025-02-11T14:00:00.000Z"}}
    inner = capture.envelope_payload(wrapped)
    assert inner["price"] == 190.2 and inner["asOf"] == "2025-02-11T14:00:00.000Z"
    row = capture.price_tick_to_row("pricing.IBM", inner, seq=1)
    assert row["symbol"] == "IBM" and row["price"] == 190.2 and row["source"] == "live"
    # a flat (already-unwrapped) payload passes through unchanged
    flat = {"price": 1.0, "asOf": "t"}
    assert capture.envelope_payload(flat) is flat


def test_trade_to_row_maps_trade_entity_payload():
    row = capture.trade_to_row(
        {
            "id": "t1", "accountId": 1, "security": "IBM", "side": "Buy", "state": "Filled",
            "quantity": 100, "price": 136.6,
            "updated": "2026-07-09T14:01:00.000Z", "created": "2026-07-09T14:00:55.000Z",
        },
        seq=2,
    )
    assert row["symbol"] == "IBM"
    assert row["event_type"] == "trade"
    assert row["price"] == 136.6
    assert row["size"] == 100
    assert row["ts"] == "2026-07-09T14:01:00.000Z"  # prefers 'updated' over 'created'


def test_trade_to_row_falls_back_to_created_when_no_updated():
    row = capture.trade_to_row(
        {"security": "IBM", "quantity": 10, "price": 100.0, "created": "2026-07-09T14:00:55.000Z"},
        seq=1,
    )
    assert row["ts"] == "2026-07-09T14:00:55.000Z"


def test_seq_counter_is_monotonic():
    seq = capture.SeqCounter()
    assert [seq.next() for _ in range(3)] == [1, 2, 3]


def test_write_batch_partitions_and_avoids_collision_across_flushes(tmp_path):
    con = duckdb.connect()
    seq = capture.SeqCounter()
    row1 = capture.price_tick_to_row("pricing.IBM", {"price": 136.5, "asOf": "2026-07-09T14:00:00.000Z"}, seq.next())
    row2 = capture.trade_to_row({"security": "IBM", "quantity": 100, "price": 136.6, "updated": "2026-07-09T14:01:00.000Z"}, seq.next())

    out_dir = str(tmp_path)
    capture.write_batch(con, [row1, row2], out_dir)
    capture.write_batch(con, [row1, row2], out_dir)  # second flush must not overwrite the first

    files = sorted(
        os.path.join(root, f)
        for root, _, fnames in os.walk(out_dir)
        for f in fnames
    )
    assert len(files) == 2, f"expected 2 distinct partition files from 2 flushes, got {files}"

    rows = con.execute(
        f"SELECT symbol, event_type, price, size, source, dt "
        f"FROM read_parquet('{out_dir}/**/*.parquet', hive_partitioning=true) ORDER BY seq"
    ).fetchall()
    assert len(rows) == 4  # 2 rows x 2 flushes
    assert rows[0][0] == "IBM" and rows[0][1] == "price_tick"
    assert rows[2][1] == "trade" and rows[2][3] == 100


def test_write_batch_noop_on_empty_batch(tmp_path):
    con = duckdb.connect()
    capture.write_batch(con, [], str(tmp_path))
    assert os.listdir(tmp_path) == []


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
