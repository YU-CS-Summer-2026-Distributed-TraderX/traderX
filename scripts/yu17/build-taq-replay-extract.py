#!/usr/bin/env python3
"""ADR-070: assemble the TAQ replay extract from BigQuery-resampled rows.

Input: CSV rows of (symbol, dt, win, median_price) — one exact per-window median of regular-hours
trade prints, computed IN-REGION by build-taq-replay-extract.sh's BigQuery job so no byte of the
corpus ever leaves the bucket. This script only assembles: validates completeness, forward-fills
any window with no prints (none existed on the 2026-08-26 build — 110,400 of 110,400 windows
traded), stamps each day's 09:30 ET session open as UTC epoch ms (zoneinfo, so the Feb EST -> Mar
EDT shift lives in the data and the publisher carries no timezone code), and writes the gzipped
JSON the publisher's taq-replay.js validates all-or-nothing.

The sizing was MEASURED, not picked (recorded in ADR-070):
  window 195s   -> 120 windows/day; worst measured window still held >=22 prints.
  compression 13 = window / FEED_FLUSH_MS(15s): every sequenced flush lands in a fresh window,
                   so the sequenced tick rate stays IDENTICAL to today's (decision 1's bound).
                   One trading day = 30 wall-clock minutes; the 40-day tape spans 20h.

Usage: build-taq-replay-extract.py --rows <dir-of-csv> --out extract.json.gz
       [--window 195] [--compression 13]
Stdlib only.
"""
import argparse
import csv
import glob
import gzip
import json
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

SESSION_SECONDS = 23400  # 09:30 -> 16:00 ET
ET = ZoneInfo('America/New_York')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rows', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--window', type=int, default=195)
    ap.add_argument('--compression', type=float, default=13)
    args = ap.parse_args()

    if SESSION_SECONDS % args.window:
        sys.exit(f'window {args.window}s does not divide the {SESSION_SECONDS}s session')
    windows_per_day = SESSION_SECONDS // args.window

    rows = {}
    for path in sorted(glob.glob(f'{args.rows}/*.csv')):
        with open(path) as f:
            for r in csv.DictReader(f):
                win = int(r['win'])
                if not 0 <= win < windows_per_day:
                    sys.exit(f'window index {win} out of range — rows were resampled at a '
                             f'different window than --window {args.window}')
                rows[(r['symbol'], r['dt'], win)] = float(r['median_price'])
    if not rows:
        sys.exit(f'no rows under {args.rows}')

    symbols = sorted({k[0] for k in rows})
    dates = sorted({k[1] for k in rows})
    days = [{'date': d,
             'openMs': int(datetime.fromisoformat(f'{d}T09:30:00').replace(tzinfo=ET).timestamp() * 1000)}
            for d in dates]

    prices, filled = {}, 0
    for sym in symbols:
        series = []
        last = None
        for d in dates:
            day = []
            for w in range(windows_per_day):
                px = rows.get((sym, d, w))
                if px is None:
                    filled += 1  # the median is still the latest known price as of this window
                else:
                    last = px
                if last is None:
                    sys.exit(f'{sym} has no prints at the very start of day 1 ({d}) — a leading '
                             'hole must be resolved by hand, not silently backfilled')
                day.append(last)
            if all(rows.get((sym, d, w)) is None for w in range(windows_per_day)):
                print(f'  WARNING: {sym} {d} had zero traded windows (carried the prior close)')
            series.append(day)
        prices[sym] = series
        print(f'  {sym:5s} first open {series[0][0]:.2f}, last close {series[-1][-1]:.2f}')

    extract = {
        'version': 1,
        'source': 'taq-replay-2025-02',
        'corpus': 'gs://traderx-501015-tick-store/ticks/source=taq',
        'windowSeconds': args.window,
        'sessionSeconds': SESSION_SECONDS,
        'compression': args.compression,
        'days': days,
        'prices': prices
    }
    blob = gzip.compress(json.dumps(extract, separators=(',', ':')).encode())
    with open(args.out, 'wb') as f:
        f.write(blob)
    total = len(symbols) * len(dates) * windows_per_day
    print(f'{args.out}: {len(blob)} bytes gzipped, {len(symbols)} symbols x {len(dates)} days x '
          f'{windows_per_day} windows, {filled} forward-filled of {total} '
          f'({filled / total * 100:.2f}%)')


if __name__ == '__main__':
    main()
