# `EXPORT DATA ... overwrite=true` overwrites the FILES it writes, not the PREFIX

**Filed 2026-08-26** while building the ADR-072 print sample. **Fixed in
`scripts/yu17/build-taq-print-sample.sh`; NOT fixed in its sibling
`scripts/yu17/build-taq-replay-extract.sh`**, which was being edited in the working tree by another
lane at the time and is not mine to change mid-flight.

## What happens

Both TAQ builders end with

```sql
EXPORT DATA OPTIONS(uri='gs://.../rows/win-*.csv', format='CSV', overwrite=true) AS SELECT ...
```

and then `gcloud storage cp "${ROWS_URI}/*"` into a local directory that the python assembler globs.

`overwrite=true` overwrites the shards **this** export writes. A previous run that produced MORE
shards leaves the extras in the prefix, `cp` fetches them, and the assembler **unions two different
samples into one artifact**.

## Why it is worth an issue rather than a note

**The result is not an error. It is a plausible artifact.** Measured 2026-08-26: a 23-symbol,
4-slot run assembled as *"100 symbols x 40 days x 120 windows x 4 slots, 33.08% filled"* — the
leftovers of a previous 100-symbol, 1-slot run merged with the new rows. Nothing in the pipeline
objects: the row count is large, every price is real, every symbol is in the extract, and the
sparse plane is exactly what a legitimately quiet window looks like.

It was caught **only** by an unrelated size guard (the artifact has to fit a Kubernetes Secret).
The median extract has no such guard, because one price per window is small whatever happens — so
the same contamination there would ship silently, and a symbol carried at the wrong resample would
be a reference price nobody could account for.

## The fix

One line before the query:

```bash
gcloud storage rm -r "${ROWS_URI}" >/dev/null 2>&1 || true
```

`build-taq-print-sample.sh` carries it with the reasoning inline. Carry it to
`build-taq-replay-extract.sh` — **which has been done** (`f56d737a`), with `|| true` added because a
first-ever run has no prefix and `rm -r` exits 1 under `set -euo pipefail`.

**Do NOT re-run that builder. The live extract was checked and is clean — verified twice, by two
independent methods, 2026-08-26:**

- **Read directly out of the bucket:** 100 symbols x 40 days x 120 windows, **zero nulls**, no short
  series.
- **Proven by its own consumer:** `taq-replay.js` validates the extract **all-or-nothing at load** and
  refuses any symbol whose day is not exactly 120 finite positive prices. A running publisher
  reporting `symbols: 100, days: 40, error: null` is therefore the artifact proving itself, not a
  reader agreeing with a reader.

**It escaped by luck, not by design**, which is why the fix still matters: both runs happened to shard
identically (21 shards), so nothing stale survived to be assembled. **The next build with a different
shard count is the one that would have been contaminated.**

The earlier version of this paragraph told the reader to rebuild on a suspicion that had already been
falsified. Recorded rather than silently edited: **an unverified worry in a filed issue costs someone
a rebuild, and reads exactly like a finding.**

## Related

- `specs/YU17-otc-rates/system/adr-070-the-tape-is-the-reference.md` — the median extract
- `specs/YU17-otc-rates/system/adr-072-replayed-prints-become-order-flow.md` — the print sample
