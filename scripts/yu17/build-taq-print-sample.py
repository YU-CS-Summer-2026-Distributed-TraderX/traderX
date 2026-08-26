#!/usr/bin/env python3
"""ADR-072: assemble the sampled-print extract from BigQuery-sampled rows.

Input: CSV rows of (symbol, dt, win, slot, price) — real trade prints picked at evenly spaced
RANKS inside each 195s window by build-taq-print-sample.sh's BigQuery job, so no byte of the
corpus leaves the bucket. This script only assembles and checks.

WHY BINARY, NOT THE JSON THE MEDIAN EXTRACT USES. The median extract is one price per window;
this is `slots` prices per window, so it is `slots` times larger for the same universe, and it
has to fit in a Kubernetes Secret (hard 1 MiB, and the median extract already learned that a
client-side apply blows the 256 KiB annotation cap at a quarter of that). A fixed-width int32
plane of price-in-thousandths gzips to a fraction of the same numbers rendered as JSON text, and
the reader is a DataView loop rather than a parser. 0 means NO PRINT IN THIS SLOT — a window with
fewer prints than `slots` is left short rather than forward-filled, because a forward-filled print
is not a print that happened, and an order is a claim that it was.

Layout (little-endian):
    magic   'TAQP1'                       5 bytes
    slots   uint16                        prints per window
    window  uint16                        window seconds
    session uint32                        session seconds (23400)
    days    uint16
    symbols uint16
    scale   uint32                        price fixed-point divisor (1000 = the venue's 3dp grid)
    dates   days x 10 ascii               'YYYY-MM-DD', in tape order
    names   symbols x (uint8 len + ascii)
    prices  symbols x days x windowsPerDay x slots  int32, price * scale, 0 = no print

Usage: build-taq-print-sample.py --rows <dir> --extract <median-extract.json.gz>
       --out prints.bin.gz [--window 195] [--slots 8]
Stdlib only.
"""
import argparse
import csv
import glob
import gzip
import json
import struct
import sys

SESSION_SECONDS = 23400  # 09:30 -> 16:00 ET, the same session the median extract is cut on
MAGIC = b'TAQP1'
SCALE = 1000  # the equity book's tickPx is 1000 (0.001): a 4dp replayed price is off-grid


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rows', required=True)
    ap.add_argument('--extract', required=True, help='the ADR-070 median extract, for cross-check')
    ap.add_argument('--out', required=True)
    ap.add_argument('--window', type=int, default=195)
    ap.add_argument('--slots', type=int, default=8)
    args = ap.parse_args()

    if SESSION_SECONDS % args.window:
        sys.exit(f'window {args.window}s does not divide the {SESSION_SECONDS}s session')
    windows_per_day = SESSION_SECONDS // args.window

    extract = json.loads(gzip.open(args.extract).read())
    ref_symbols = sorted(extract['prices'])
    ref_dates = [d['date'] for d in extract['days']]
    if extract['windowSeconds'] != args.window or extract['sessionSeconds'] != SESSION_SECONDS:
        sys.exit(f'the median extract is cut at window {extract["windowSeconds"]}s / session '
                 f'{extract["sessionSeconds"]}s; this sample is being cut at {args.window}s / '
                 f'{SESSION_SECONDS}s. The replay clock is ONE clock — they cannot differ.')

    rows = {}
    for path in sorted(glob.glob(f'{args.rows}/*.csv')):
        with open(path) as f:
            for r in csv.DictReader(f):
                win, slot = int(r['win']), int(r['slot'])
                if not 0 <= win < windows_per_day:
                    sys.exit(f'window index {win} out of range — the rows were sampled at a '
                             f'different window than --window {args.window}')
                if not 0 <= slot < args.slots:
                    sys.exit(f'slot index {slot} out of range — the rows were sampled at a '
                             f'different slot count than --slots {args.slots}')
                rows[(r['symbol'], r['dt'], win, slot)] = float(r['price'])
    if not rows:
        sys.exit(f'no rows under {args.rows}')

    symbols = sorted({k[0] for k in rows})
    dates = sorted({k[1] for k in rows})
    # The universe and the calendar are the median extract's, not this sample's opinion of them.
    # A symbol here that the reference series does not carry would trade real prices against an
    # invented collar anchor; a date mismatch would put the two series on different clocks.
    if not set(symbols) <= set(ref_symbols):
        sys.exit(f'sampled symbols the median extract does not carry: '
                 f'{sorted(set(symbols) - set(ref_symbols))}. A replayed order needs a replayed '
                 f'reference; SUBSET is the invariant, and this is not one.')
    if dates != ref_dates:
        sys.exit(f'sampled dates do not match the median extract ({len(dates)} vs {len(ref_dates)})')

    out = bytearray()
    out += MAGIC
    out += struct.pack('<HHIHHI', args.slots, args.window, SESSION_SECONDS,
                       len(dates), len(symbols), SCALE)
    for d in dates:
        out += d.encode('ascii')
    for s in symbols:
        name = s.encode('ascii')
        out += struct.pack('<B', len(name)) + name

    filled = total = 0
    per_symbol_empty = {}
    plane = []
    for sym in symbols:
        empty_windows = 0
        for d in dates:
            for w in range(windows_per_day):
                got = 0
                for k in range(args.slots):
                    px = rows.get((sym, d, w, k))
                    total += 1
                    if px is None:
                        plane.append(0)
                    else:
                        ticks = int(round(px * SCALE))
                        if ticks <= 0:
                            sys.exit(f'{sym} {d} w{w} s{k}: price {px} is not positive on the grid')
                        plane.append(ticks)
                        filled += 1
                        got += 1
                if got == 0:
                    empty_windows += 1
        per_symbol_empty[sym] = empty_windows
    out += struct.pack(f'<{len(plane)}i', *plane)

    blob = gzip.compress(bytes(out), 9)
    with open(args.out, 'wb') as f:
        f.write(blob)

    starved = {s: n for s, n in per_symbol_empty.items() if n}
    if starved:
        # Not fatal: a window with no prints replays no orders, which is the honest reading of a
        # window in which nothing traded. Loud, because it is also what a truncated export looks
        # like, and those two must never be confused for one another.
        print(f'  NOTE: {len(starved)} symbol(s) have windows with NO prints at all: '
              + ', '.join(f'{s}={n}' for s, n in sorted(starved.items())[:8]))
    rate = len(symbols) * args.slots / (args.window / float(extract['compression']))
    print(f'{args.out}: {len(blob)} bytes gzipped ({len(out)} raw), {len(symbols)} symbols x '
          f'{len(dates)} days x {windows_per_day} windows x {args.slots} slots')
    print(f'  {filled} of {total} slots carry a print ({filled / total * 100:.2f}%)')
    print(f'  replayed order rate at {extract["compression"]}x compression: {rate:.1f}/s '
          f'(ADR-072 asks for order 5-20/s; the runtime samples DOWN from this)')
    if len(blob) > 900_000:
        # THE CEILING IS A RATE, NOT A UNIVERSE SIZE, and that is not obvious: prints =
        # symbols x days x windows x slots, and slots is chosen as rate x window / (symbols x
        # compression), so the symbol count CANCELS and the artifact is
        #     prints = rate x days x session_seconds / compression = rate x 72,000 at 40 days.
        # Widening the universe costs nothing here; raising the order RATE is what costs. Measured
        # 2026-08-26 at 1.62 bytes/print gzipped, so ~8 orders/s is the ceiling of this transport.
        # Above that, delta+varint encoding measured 1.11 bytes/print (~11/s) and after that the
        # Secret has to stop being one object.
        sys.exit(f'{len(blob)} bytes will not fit a Kubernetes Secret with margin (hard cap 1 MiB, '
                 f'and the median extract already measured a client-side apply failing at 256 KiB).\n'
                 f'  {total} prints at {len(blob) / total:.2f} bytes each. The knob is the RATE, not '
                 f'the universe: halve --slots to halve the rate and the size. See the note here '
                 f'before reaching for a cleverer encoding.')


if __name__ == '__main__':
    main()
