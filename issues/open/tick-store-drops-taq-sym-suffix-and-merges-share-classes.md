# The tick store dropped TAQ's SYM_SUFFIX, merging share classes into one symbol

**Found 2026-08-26** while answering "which of our tickers does the TAQ tape not have?" — the
question that surfaces it, because the missing ones are not missing.

## What is proven

`gs://traderx-501015-tick-store/ticks/source=taq/` partitions by `symbol=<SYM_ROOT>` and carries **no
symbol or suffix column at all** — verified against the parquet schema, which is
`event_type, ts, price, size, bid_price, bid_size, ask_price, ask_size, venue, seq, ingested_at`.
The symbol survives only as the partition key, and it is the **root alone**.

**`symbol=BRK` on `dt=2025-02-03` holds trades from $451.84 to $699,724.51.** That is Berkshire
Hathaway **B and A in a single partition** — 106,407 trades, median $462.70. There is no ambiguity
about this one.

The symbol-length distribution across all 10,081 symbols on that day corroborates the mechanism:

| length | 1 | 2 | 3 | 4 | **5** | 6 |
|---|---|---|---|---|---|---|
| symbols | 20 | 273 | 2,698 | 7,080 | **9** | 1 |

A real US tape carries thousands of five-character tickers. Nine is what remains when the fifth
character is a **suffix** rather than part of the root — which is exactly TAQ's convention, where
NASDAQ class shares put the class in `SYM_SUFFIX` and NYSE dot-classes do the same. Consistently:
`GOOGL`, `CMCSA`, `BRK.B` and `BF.B` are **absent**, while `GOOG`, `CMCS`, `BRK` and `BF` are
present. `FOXA`, `NWSA` and `UAA` survive intact because those are genuine roots with no suffix.

## What follows, and which half is dangerous

**The loud half is harmless.** BRK's merge is visible from a mile away — a $700k print against a $460
median. Any sanity check catches it, and ADR-070's median survives it (the B class dominates by
volume, so the central estimate is still BRK.B).

**The quiet half is the problem.** `symbol=GOOG` should contain Alphabet class C *and* class A
(root `GOOG`, suffix `L`), which trade about 1% apart. A median across them is **a price for no
security that exists**, and it looks entirely plausible. Checked: the trade distribution for that day
is unimodal from $198 to $205, peaking at $203 — consistent with a merge and equally consistent with
one class's ordinary intraday range. **It cannot be seen in the data.** That is the finding, not a
gap in it.

`symbol=CMCS` is the third case and is benign-but-mislabelled: Comcast has only the A class, so the
partition holds correct data under a name no exchange uses.

## Why this matters to ADR-070

[ADR-070](../../specs/YU17-otc-rates/system/adr-070-the-tape-is-the-reference.md) decision 1 chooses
a **median** over each resample window, and states its reason precisely: robustness against isolated
erroneous or out-of-sequence prints, the failure mode left behind by the dropped `TR_CORR`/`TR_SCOND`
columns. **A median does not defend against this.** A merged share class is not an isolated bad
print; it is a second security contributing half the sample, systematically, all day.

This is the **same defect class ADR-070 already documents** — YU07's normalizer dropping columns it
did not think it needed — showing up in a third column. The ADR names two consequences of that drop
(unfilterable prints, no reconstructable NBBO). This is the third, and it was not known when the ADR
was written.

## The settling test, not yet run

`_raw-taq/TAQ_Feb2025/` and `_raw-taq/TAQ_March2025/` hold the originals. TAQ ships `SYM_ROOT` and
`SYM_SUFFIX` as separate columns, so reading one raw file answers two things at once: whether the
suffix was present and dropped at normalize time (recoverable by re-ingest) and whether `GOOG` really
does carry both suffix values. **Do this before designing around it** — the fix differs completely
depending on the answer.

## Scope, honestly

Affects **any root with more than one listed class**. Four are visible from our own 510-ticker
universe (`GOOGL`, `CMCSA`, `BRK.B`, `BF.B`); the true count across all 10,081 roots is unknown and
was not measured. Note it is invisible from the symbol list alone — a contaminated root looks exactly
like a clean one, which is why it surfaced from a set difference rather than from an audit.

**Not a blocker for a demo universe that avoids dual-class names.** It is a blocker for claiming the
replayed series is that security's price. Pick the ~69 accordingly, or settle the raw-file question
and re-ingest.
