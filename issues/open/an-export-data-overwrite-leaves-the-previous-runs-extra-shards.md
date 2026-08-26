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
`build-taq-replay-extract.sh`, and **re-run that builder** once: the 100-symbol widening ran into a
prefix that had held a 23-symbol export, so the extract now in the bucket may be carrying the same
contamination. The cheap check is the assembler's own summary line — symbol count and forward-fill
percentage against what was asked for.

## Related

- `specs/YU17-otc-rates/system/adr-070-the-tape-is-the-reference.md` — the median extract
- `specs/YU17-otc-rates/system/adr-072-replayed-prints-become-order-flow.md` — the print sample
