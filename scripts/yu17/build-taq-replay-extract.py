#!/usr/bin/env python3
"""ADR-070: build the resampled TAQ replay extract.

One median trade price per symbol per window, regular trading hours only (09:30-16:00 ET),
across every dt= day present in the source tree. The output is the few-hundred-KB artifact the
publisher replays; the corpus itself is never read at runtime.

The sizing was MEASURED, not picked (recorded in ADR-070):
  window 195s   -> 120 windows/day; worst sampled name (FNF) still holds >=22 prints per window,
                   zero empty RTH windows across liquid/illiquid x calm/volatile samples.
  compression 13 = window / FEED_FLUSH_MS(15s): every sequenced flush lands in a fresh window,
                   so the sequenced tick rate stays IDENTICAL to today's (decision 1's bound).
                   One trading day = 30 wall-clock minutes; the 40-day tape spans 20h of wall
                   clock, which outlasts the longest epoch measured on the rig (12.6h).

Usage:
  build-taq-replay-extract.py --src <dir> --out extract.json.gz [--window 195] [--compression 13]
                              [--symbols AAPL,SPY,...]
  <dir> mirrors the bucket layout: <dir>/dt=YYYY-MM-DD/symbol=<S>/*.parquet
  (build-taq-replay-extract.sh fetches exactly that mirror and calls this.)

Needs pyarrow + pandas. Timezone handling lives HERE and nowhere else: each day's 09:30 ET is
stamped as UTC epoch ms (zoneinfo), so the Feb EST -> Mar EDT shift is data the publisher never
has to know about.
"""
import argparse
import glob
import gzip
import json
import os
import statistics
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

import pandas as pd
import pyarrow.parquet as pq

SESSION_SECONDS = 23400  # 09:30 -> 16:00
ET = ZoneInfo('America/New_York')

# The rig's equity/ETF universe minus GOOGL (suffix-merged root — replaying it would publish a
# price for no security that exists; issues/open/tick-store-drops-taq-sym-suffix-and-merges-
# share-classes.md) and FNMA (OTC, not in TAQ). Both keep the synthetic walk, provenance intact.
DEFAULT_SYMBOLS = ('AAPL,MSFT,AMZN,META,NVDA,TSLA,IBM,BAC,C,JPM,GS,MS,UBS,DB,COF,DFS,FIS,FNF,'
                   'SPY,QQQ,IWM,VTI,GLD')


def day_medians(files, date, window, windows_per_day):
    frames = [pq.read_table(f, columns=['event_type', 'ts', 'price']).to_pandas() for f in files]
    df = pd.concat(frames)
    t = df[df.event_type == 'trade']
    t = t[(t.ts >= f'{date} 09:30') & (t.ts < f'{date} 16:00')]
    if t.empty:
        return None, 0
    secs = (t.ts - pd.Timestamp(f'{date} 09:30')).dt.total_seconds()
    grouped = t.groupby((secs // window).astype(int)).price.median()
    out, filled = [], 0
    last = None
    for w in range(windows_per_day):
        if w in grouped.index:
            last = round(float(grouped[w]), 4)
        else:
            filled += 1  # forward-fill: the median is still the latest known price as of this window
        if last is None:
            # leading hole: backfill from the first traded window (only plausible for a halted open)
            first = round(float(grouped.iloc[0]), 4)
            out.append(first)
            continue
        out.append(last)
    return out, filled


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--window', type=int, default=195)
    ap.add_argument('--compression', type=float, default=13)
    ap.add_argument('--symbols', default=DEFAULT_SYMBOLS)
    args = ap.parse_args()

    if SESSION_SECONDS % args.window:
        sys.exit(f'window {args.window}s does not divide the {SESSION_SECONDS}s session')
    windows_per_day = SESSION_SECONDS // args.window
    symbols = [s.strip().upper() for s in args.symbols.split(',') if s.strip()]

    dates = sorted(d.split('dt=')[1] for d in glob.glob(os.path.join(args.src, 'dt=*')))
    if not dates:
        sys.exit(f'no dt= partitions under {args.src}')
    days = [{'date': d,
             'openMs': int(datetime.fromisoformat(f'{d}T09:30:00').replace(tzinfo=ET).timestamp() * 1000)}
            for d in dates]

    prices, total_filled = {}, 0
    for sym in symbols:
        series = []
        prev_close = None
        for d in dates:
            files = glob.glob(os.path.join(args.src, f'dt={d}', f'symbol={sym}', '*.parquet'))
            if not files:
                sys.exit(f'{sym} has no files for dt={d} — a symbol missing a whole day must be '
                         'resolved by hand, not silently filled')
            day, filled = day_medians(files, d, args.window, windows_per_day)
            if day is None:
                if prev_close is None:
                    sys.exit(f'{sym} has zero RTH trades on its first day {d}')
                day, filled = [prev_close] * windows_per_day, windows_per_day
            series.append(day)
            prev_close = day[-1]
            total_filled += filled
        prices[sym] = series
        med = statistics.median(x for day in series for x in day)
        print(f'  {sym:5s} {len(series)} days, median {med:.2f}, '
              f'first open {series[0][0]:.2f}, last close {series[-1][-1]:.2f}')

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
    print(f'{args.out}: {len(blob)} bytes gzipped, {len(symbols)} symbols x {len(days)} days x '
          f'{windows_per_day} windows, {total_filled} forward-filled windows '
          f'({total_filled / (len(symbols) * len(days) * windows_per_day) * 100:.2f}%)')


if __name__ == '__main__':
    main()
