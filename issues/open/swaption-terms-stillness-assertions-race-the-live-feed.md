# yu17-swaption-terms' sequence-stillness assertions race the live feed adapter

**Filed 2026-08-25** (format-8 proof-set chip, from the pre-mint baseline run). It matters now
because "suite fully green including the five format-8 proofs" is the MINT chip's completion
criterion, and this failure does NOT dissolve at the mint's fresh epoch — it recurs on any rig
where the feed adapter is sequencing.

## What was observed

`yu17-swaption-terms` failed step 1 in the baseline subset:
`the sequence moved 3913841 -> 3913909: an unrepresentable term reached consensus` — a delta of
**68**. The feed adapter sequences the whole publisher universe per flush (`symbols=69`), so the
delta is one ambient flush, not a leaked swaption. The proof asserts the applied sequence is
STILL around a refused booking (and "moves by exactly two" around accepted ones) — assertions
written when ticks entered the epoch only through `/seed`, a fact superseded on 2026-08-24 when
the adapter went live.

## The class

Any proof asserting an exact applied-sequence delta now races ~69 sequenced ticks per publisher
flush. `yu17-swaption-terms` is the instance the baseline caught; `yu17-swap-netting` documents
the same "moves by exactly two" reading and should be checked when this is fixed.

## The fix direction (owner: whoever holds the swaption proofs — not the format-8 chips)

Assert on a signal ticks cannot move: scale the adapter to 0 around the measurement (and back —
capture-and-restore), or replace "sequence moved by exactly N" with a tick-insensitive reading
(e.g. the contracts artifact row count / booking counters, which the same proof already reads in
its later steps).

## Baseline context (for the record)

Pre-mint baseline 2026-08-25, stable-block subset on `:yu17-markwait2`: 18 pass / 0 skip /
7 fail = 4 format-8 proofs EXPECTED RED by design + 2 epoch-scale refusals that DO dissolve at
the mint's fresh epoch (`yu05-recon`: RECON_FULL_HISTORY_MAX 200 000 < 3.63M flood trades;
`yu05-regulatory-reproducible`: the authorized journal-sourced report runs >60s over the flood
epoch against a 10s preflight — whose error text then blames "the transport" for what is a slow
endpoint, a small misattribution worth fixing in passing) + this.
