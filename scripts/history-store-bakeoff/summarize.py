#!/usr/bin/env python3
"""summarize.py RESULTS_DIR [SINK...]: one row per store from bench.sh's results/<sink>/ files.

ingest: rows written per wall second while the feeder ran (feed1 + feed2, the 30 s outage excluded) and the best
5-second interval; peak CPU (cores) and memory from the store host's cgroup samples (anon = process heap/arena,
current includes the page cache the store's mapped files hold); disk = data dir bytes at the end; plus the check,
query and export lines as printed.
"""
import csv, re, sys
from pathlib import Path


def feed_rates(path):
    rows, rates, first, last = 0, [], None, None
    for line in path.read_text().splitlines():
        m = re.match(r"(\d\d):(\d\d):(\d\d) rows=(\d+) \((\d+)/s\)", line)
        if not m:
            continue
        t = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3])
        r = int(m[4])
        if r > rows:                       # count only intervals that moved (not the idle tail)
            first = t - 5 if first is None else first
            last = t
            rates.append(int(m[5]))
        rows = r
    return rows, (last - first) if first is not None else 0, rates


def samples(path):
    peak_cores, peak_anon, peak_cur, prev = 0.0, 0, 0, {}
    with path.open() as f:
        for r in csv.DictReader(f):
            c, t, cpu = r["container"], int(r["epoch"]), int(r["cpu_usec"] or 0)
            if c in prev and t > prev[c][0]:
                peak_cores = max(peak_cores, (cpu - prev[c][1]) / 1e6 / (t - prev[c][0]))
            prev[c] = (t, cpu)
            peak_anon = max(peak_anon, int(r["anon"] or 0))
            peak_cur = max(peak_cur, int(r["mem_current"] or 0), int(r["mem_peak"] or 0))
    return peak_cores, peak_anon, peak_cur


def main():
    root = Path(sys.argv[1])
    for sink in sys.argv[2:] or ["clickhouse", "questdb", "kdb"]:
        d = root / sink
        if not d.exists():
            continue
        r1, s1, rates1 = feed_rates(d / "feed1.log")
        r2, s2, rates2 = feed_rates(d / "feed2.log")
        rows, secs = r1 + r2, s1 + s2
        print(f"## {sink}")
        print(f"ingest: {rows:,} rows in {secs} s moving = {rows / max(secs, 1):,.0f} rows/s;"
              f" best 5 s = {max(rates1 + rates2 or [0]):,}/s")
        if (d / "samples.csv").exists():
            cores, anon, cur = samples(d / "samples.csv")
            print(f"peak: {cores:.1f} cores, anon {anon / 2**30:.1f} GiB, cgroup current/peak {cur / 2**30:.1f} GiB")
        bench = (d / "bench.log").read_text()
        disk = re.search(r"disk bytes: (\d+)", bench)
        if disk:
            print(f"disk: {int(disk[1]) / 2**30:.1f} GiB")
        for f in ("check.log", "query.log", "export.log"):
            if (d / f).exists():
                for line in (d / f).read_text().splitlines():
                    if line.startswith(("CHECK", "QUERY", "EXPORT", "STREAM total", "bounds")):
                        print(line)
        print()


if __name__ == "__main__":
    main()
