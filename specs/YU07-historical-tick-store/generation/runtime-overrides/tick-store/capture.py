#!/usr/bin/env python3
"""tick-store capture: subscribes to pricing.* and /accounts/*/trades on the existing NATS
broker (both already-broadcast subjects, see research.md Decision 2) and batches rows into the
unified Parquet schema (data-model.md)."""
import asyncio
import json
import logging
import os
import signal
import uuid
from datetime import datetime, timezone

import duckdb
import nats

from gcs import configure_gcs, is_gcs_path

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("tick-store.capture")

NATS_URL = os.environ.get("TICKSTORE_NATS_URL", os.environ.get("NATS_URL", "nats://nats-broker:4222"))
OUT_DIR = os.environ.get("TICKSTORE_OUT_DIR", "/data/ticks")
FLUSH_INTERVAL_SECONDS = float(os.environ.get("TICKSTORE_FLUSH_INTERVAL_SECONDS", "30"))
FLUSH_MAX_ROWS = int(os.environ.get("TICKSTORE_FLUSH_MAX_ROWS", "5000"))

COLUMNS = [
    "symbol", "event_type", "ts", "price", "size",
    "bid_price", "bid_size", "ask_price", "ask_size",
    "venue", "source", "seq", "ingested_at",
]


def _now_iso():
    return datetime.now(timezone.utc).isoformat()


class SeqCounter:
    """Monotonic per-process sequence number for live rows (data-model.md: not comparable
    across sources, only used to order rows captured within one process's lifetime)."""

    def __init__(self):
        self._n = 0

    def next(self):
        self._n += 1
        return self._n


def price_tick_to_row(subject, payload, seq):
    """pricing.<TICKER> -> unified row. Payload: {price, openPrice, closePrice, asOf, source}."""
    symbol = subject.split(".", 1)[1]
    return {
        "symbol": symbol,
        "event_type": "price_tick",
        "ts": payload["asOf"],
        "price": float(payload["price"]),
        "size": None,
        "bid_price": None, "bid_size": None,
        "ask_price": None, "ask_size": None,
        "venue": "TRADERX",
        "source": "live",
        "seq": seq,
        "ingested_at": _now_iso(),
    }


def trade_to_row(payload, seq):
    """/accounts/<id>/trades -> unified row. Payload: Trade entity JSON (trade-processor)."""
    ts = payload.get("updated") or payload.get("created")
    price = payload.get("price")
    quantity = payload.get("quantity")
    return {
        "symbol": payload["security"],
        "event_type": "trade",
        "ts": ts,
        "price": float(price) if price is not None else None,
        "size": int(quantity) if quantity is not None else None,
        "bid_price": None, "bid_size": None,
        "ask_price": None, "ask_size": None,
        "venue": "TRADERX",
        "source": "live",
        "seq": seq,
        "ingested_at": _now_iso(),
    }


def write_batch(con, rows, out_dir):
    """Write a batch of unified rows to partitioned Parquet. A unique FILENAME_PATTERN avoids
    collisions across repeated flushes into the same source=/dt=/symbol= partition directory
    (verified empirically — see quickstart.md self-check)."""
    if not rows:
        return
    con.execute(f"""
        CREATE TEMP TABLE batch (
            symbol VARCHAR, event_type VARCHAR, ts VARCHAR, price DOUBLE, size BIGINT,
            bid_price DOUBLE, bid_size BIGINT, ask_price DOUBLE, ask_size BIGINT,
            venue VARCHAR, source VARCHAR, seq BIGINT, ingested_at VARCHAR
        )
    """)
    placeholders = ", ".join(["?"] * len(COLUMNS))
    con.executemany(
        f"INSERT INTO batch VALUES ({placeholders})",
        [[row[c] for c in COLUMNS] for row in rows],
    )
    con.execute(
        "COPY (SELECT symbol, event_type, CAST(ts AS TIMESTAMP) AS ts, price, size, "
        "bid_price, bid_size, ask_price, ask_size, venue, source, seq, "
        "CAST(ingested_at AS TIMESTAMP) AS ingested_at, "
        "CAST(CAST(ts AS TIMESTAMP) AS DATE) AS dt FROM batch) "
        f"TO '{out_dir}' (FORMAT PARQUET, COMPRESSION ZSTD, "
        "PARTITION_BY (source, dt, symbol), FILENAME_PATTERN '{uuid}', "
        "OVERWRITE_OR_IGNORE true)"
    )
    con.execute("DROP TABLE batch")


async def run(nats_url=NATS_URL, out_dir=OUT_DIR,
               flush_interval=FLUSH_INTERVAL_SECONDS, flush_max_rows=FLUSH_MAX_ROWS):
    con = duckdb.connect()
    if is_gcs_path(out_dir):
        configure_gcs(con)
    else:
        os.makedirs(out_dir, exist_ok=True)
    seq = SeqCounter()
    batch = []
    lock = asyncio.Lock()

    async def flush():
        async with lock:
            if not batch:
                return
            to_write, batch[:] = batch[:], []
        write_batch(con, to_write, out_dir)
        log.info("flushed %d rows to %s", len(to_write), out_dir)

    async def on_price_tick(msg):
        try:
            row = price_tick_to_row(msg.subject, json.loads(msg.data), seq.next())
        except (KeyError, ValueError, json.JSONDecodeError) as exc:
            log.warning("skipping malformed pricing message on %s: %s", msg.subject, exc)
            return
        async with lock:
            batch.append(row)
            should_flush = len(batch) >= flush_max_rows
        if should_flush:
            await flush()

    async def on_trade(msg):
        try:
            row = trade_to_row(json.loads(msg.data), seq.next())
        except (KeyError, ValueError, json.JSONDecodeError) as exc:
            log.warning("skipping malformed trade message on %s: %s", msg.subject, exc)
            return
        async with lock:
            batch.append(row)
            should_flush = len(batch) >= flush_max_rows
        if should_flush:
            await flush()

    nc = await nats.connect(nats_url, reconnect_time_wait=2, max_reconnect_attempts=-1)
    await nc.subscribe("pricing.*", cb=on_price_tick)
    await nc.subscribe("/accounts/*/trades", cb=on_trade)
    log.info("subscribed to pricing.* and /accounts/*/trades on %s", nats_url)

    stop = asyncio.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        asyncio.get_event_loop().add_signal_handler(sig, stop.set)

    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), timeout=flush_interval)
        except asyncio.TimeoutError:
            pass
        await flush()

    await flush()
    await nc.drain()


if __name__ == "__main__":
    asyncio.run(run())
