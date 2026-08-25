# ADR-070: The tape is the reference, replayed on an epoch clock

## Status

**Proposed** (2026-08-24). **Not implemented.** Raised by yaakov: we hold a licensed TAQ corpus
covering February and March 2025 — can it be the reference price, treating day 1 of any fresh epoch
as the first day of the tape?

Sits inside [ADR-068](adr-068-external-price-sources.md), which decided *where an external reference
comes from* and left one row of its own table unanswered: **equities and ETFs, still on the random
walk.** This ADR answers that row, and introduces one mechanism ADR-068 did not contemplate — a
**clock**. That is why it is a separate ADR rather than an amendment: sourcing and time have
different failure modes, and the clock is the part with the interesting ones.

Written to be argued with on paper. It touches the publisher, the collar's reference, the session
phases and the bring-up path, and it is far cheaper to disagree about here.

## Context

### The permission, recorded because ADR-068 records permissions

**Use is authorised — stated by yaakov 2026-08-24, sourced from the head of the program.** This is an
institutional grant over data the university already holds, not a vendor click-through, which makes it
a different *kind* of permission from the Pyth / Massive / FRED terms ADR-068 had to read. What was
asked and answered is **use**. Nothing here should be read as a finding about redistribution or
public display; ADR-068's open question 1 (display rights) is not closed by this entry.

One point argues in favour on the licensing axis regardless: ADR-068 open question 2 observes that
*delayed* data is nearly always more permissive than real-time. A replay of February 2025 is
**eighteen months delayed** — the most permissive posture this system could adopt while still being
real.

### What the corpus actually is (verified 2026-07-27, not recalled)

`gs://traderx-501015-tick-store`, `ticks/source=taq/`:

| | |
|---|---|
| Range | `dt=2025-02-03` → `2025-03-31`, **40 trading days**, every one present (Feb 17 Presidents' Day correctly absent) |
| Breadth | ~10,100 `symbol=` partitions per day |
| Schema | `event_type` discriminates `quote` / `trade`; `ts` is **ET wall-clock, tz-naive, microsecond** |
| Quotes | **only 26 of the 40 days.** Feb 10 and Mar 13–31 are **trade-only** |
| Suspect | `dt=2025-03-11` — the OOM outlier that completed only on a reduced-thread retry |

**Day 1 is February 3, not February 1** — Feb 1 2025 was a Saturday. The anchor in the question needs
that correction and nothing else.

The quote gap is **upstream and fixed**: confirmed at the origin, the professor's share contains
exactly the same 26 quote zips. Re-asking does not recover those days; re-ordering from the source
would.

### The two things the corpus cannot do, and why they are not fixable here

**1. The prints are unfiltered, and cannot be filtered.** YU07's normalizer dropped `TR_CORR`
(corrections) and `TR_SCOND` (sale conditions) — precisely the fields used to exclude corrected,
cancelled, out-of-sequence and derivatively-priced trades. They are gone from the store; recovering
them is a **transform code change plus a re-ingest of 650 GiB**, not a re-run.

**2. NBBO cannot be reconstructed.** `QU_COND`, `QU_CANCEL` and `NATBBO_IND` were dropped as well, so
non-firm and cancelled quotes cannot be excluded and no national best bid/offer can be derived. The
tier of [ADR-067](adr-067-market-data-derived-from-the-book.md)'s hierarchy closest to the question
that started all of this — *a reference derived from resting quotes* — **is not available from this
corpus.** What is available is the print: ADR-051's mark tier, sourced externally.

### Why this matters more here than it would elsewhere

A bad print is not a cosmetic problem in this system. The collar reads `BlpRiskState.lastPrice[]`;
under [ADR-066](adr-066-price-band-follows-the-market.md) that re-anchors a book's band, and under the
[price-derived grid design](format-8-price-derived-grid-design.md) it re-derives the book's **grid**
by decade whenever the book is empty. A single erroneous print on a $200 name therefore moves both
where the band sits and how finely the book is priced. **The unfiltered-print property lands exactly
on the two mechanisms built this week.**

## Decision (proposed)

### 1. The tape is resampled offline; the publisher replays the resampled series, not the tape

Do **not** stream prints. For each symbol in the demo universe (~69, not 10,100) and each replay
interval, compute **one reference price offline** and publish that.

This one choice does four jobs, and they should be stated together so no later reader removes three
of them by simplifying:

- **It bounds the message rate.** Sequenced `PRICE_TICK`s consume consensus. Replaying real print
  rates — for 69 symbols, under time compression — multiplies today's flush by an unbudgeted factor.
  Resampling to the publisher's existing cadence makes the rate **identical to today's**, so nothing
  downstream is resized. (It is also why compression becomes free: a lookup index, not a throttle.)
- **It defuses the unfiltered print.** Take a **median** over the window, not the last print. A median
  is robust to isolated erroneous or out-of-sequence prints, which is the failure mode the missing
  `TR_CORR`/`TR_SCOND` columns leave in the data. This does not make the series reference-grade and
  must never be described as such — it makes it *sane*, which is all a collar reference needs to be.
- **It collapses the extract.** 69 symbols × 40 days × one price per interval is a few megabytes
  against a 650 GiB corpus, computed once, in-region, at zero egress.
- **It keeps the whole thing revertible**, because a small named artifact can be switched off in a way
  a streaming integration cannot.

A live move-limit guard in the publisher (reject a tick more than N% from the prior) remains worth
having as defence in depth, and is worth having whatever the source is.

### 2. The clock is stateless: position is derived, never stored

```
replay_position = (wall_clock_now − epoch_start) × compression
tape_timestamp  = FIRST_TRADING_DAY + replay_position   # skipping non-trading days
```

`epoch_start` is the fresh-epoch mint. Deriving position rather than storing a cursor means a
**publisher restart resumes in the right place with no coordination and no persisted state**, and two
publishers would agree without talking. This is the property to protect if the design is changed.

**The members never see the mapping.** The publisher decides which tick to emit; the cluster sequences
whatever arrives. There is no replicated clock, therefore no divergence risk, therefore **no
deterministic-core change and no snapshot format change**. This does not ride the format-8 mint and
can roll in place.

### 3. Equities and ETFs only

ADR-068's per-class table is otherwise unchanged: Treasuries stay on FRED, options stay synthetic by
decision, swaps and swaptions stay computed, corporates stay synthetic pending the ICE BofA / Moody's
licensing outcome. **This closes exactly one row of that table.**

Worth stating plainly because it is easy to over-read: the instruments behind this week's defects —
FNMA at 1.145 landing on the equity grid, an option accepted at 20× premium — are **not covered by
TAQ**. This ADR does not touch them.

### 4. Provenance: the boolean cannot express a replay

ADR-068 rule 2 put `simulated` on the wire so nobody could mistake an invented number for a real one.
A replay is a **third category — a real price at a fabricated time** — and a boolean cannot say that.
Either value is a lie by itself.

Proposed: `source: taq-replay-2025-02` plus an explicit **`asOf`** carrying the true tape timestamp,
so a consumer reading a Feb 2025 print can tell it is not this morning's. Without `asOf`, provenance
answers "was this invented?" but not "when was this true?", and for a replay the second question is
the one that matters.

## The collision with ADR-068's durability rule — and why it does not need an exception

ADR-068 states the rule that makes "revert before anything public" real:

> External data must never become durable. It may live in memory and on the wire. It may not be
> committed to the repository, baked into fixtures or seed files, cached to disk in a tracked path, or
> embedded in recorded or published material.

**A replay needs a tape on disk. The obvious implementation — ship the extract alongside the publisher
— is precisely what that rule forbids.** The temptation is to carve TAQ out on the grounds that the
permission is institutional rather than a vendor ToS.

**Do not carve it out. It is not necessary.** Put the resampled extract in
`gs://traderx-501015-tick-store`, beside the corpus it came from, and have the publisher fetch it at
bring-up. Then:

- nothing enters the repository, no fixture is baked, no tracked path is cached;
- the artifact lives in a bucket **we already control**, under the same access posture as its source;
- turning the source off stays a **config change**, so ADR-068 rule 1 holds — synthetic remains the
  default and remains sufficient with no network, no key and no account;
- and the rule survives intact for the next source, which may well be a vendor whose terms make it
  load-bearing.

The rule was written to keep the revert real. Fetch-at-bring-up keeps it real. An exception would have
spent the rule to buy convenience.

ADR-068's two consequences apply here unchanged and are repeated because they are the ones that get
skipped: **the demo must be rehearsed in synthetic mode, not merely capable of it**, and provenance is
what makes the switch auditable after the fact.

## Consequences

**The one that argues for doing this.** [ADR-069](adr-069-the-session-opens-where-the-last-one-closed.md)
asserts that a session opens where the last one closed, and today that rule is checked against a
system that fabricates both ends. A tape has **real opens, real closes and real overnight gaps** —
Feb 3's close to Feb 4's open is a discontinuity nobody invented. ADR-069's rule stops being an
assertion and becomes something checkable against the tape. Time compression shortens the gap's
*duration* but preserves its *discontinuity*, which is the part that carries the argument.

**A second writer to the sequence, again.** The feed adapter going live on 2026-08-24 broke three
proofs that read the cluster's global `applied` counter as if it were private to their own bookings.
Resampling holds the tick rate flat, so this changes the *content* of that traffic and not its volume
— but any proof still reading a global counter is affected by the change in tick timing regardless.
The class must be closed before this lands, not after.

**Fixture prices stop being arbitrary.** Proof probes already derive prices from the publisher at
runtime rather than from literals, so a real tape is tolerated by construction. The producer sweep's
tightening-band cohort should be re-measured against replayed prices before the switch, not assumed
to survive it.

**A demo becomes explicable.** "This is Apple on February 4th" is a sentence an audience can check.
"This is a random walk seeded at 200" is not.

## What this is explicitly NOT

- **Not a market simulator.** The tape drives a *reference*; the book is still made by the orders this
  system receives. Replayed prints are never injected as trades, and the resampled series is never
  presented as depth.
- **Not a TCA or VWAP source.** The store holds unfiltered prints. Any volume-weighted number computed
  from it is not reference-grade and must not be described as one — a constraint on what we assert,
  independent of this ADR.
- **Not a dependency.** Synthetic remains sufficient (ADR-068 rule 1).
- **Not a change to what the collar references.** ADR-067 question 1 decided the collar keeps an
  exogenous-first reference precisely so that a book cannot determine its own guard rail. Replacing
  the exogenous series with a better one honours that decision; it does not revisit it. If anything it
  strengthens it — the reference is now genuinely exogenous rather than a walk this system invented.

## Open questions

1. **Compression ratio, and whether it is fixed or configurable.** 1:1 puts 40 trading days across
   eight weeks of wall clock, which no rig survives; one trading day per ten minutes puts the corpus
   in a working day. This is a sizing question with a measurable answer, not a preference — it sets
   both the demo's pace and the replay index's granularity.
2. **What happens at the end of the tape?** Forty trading days ends. Halt, loop to Feb 3, or fall back
   to synthetic? Looping creates a discontinuity at the seam — Mar 31's close to Feb 3's open — which
   is a fabricated gap of exactly the kind ADR-069 exists to make real. Falling back to synthetic is
   honest but drifts. **No recommendation yet; this needs deciding before it is discovered live.**
3. **Which resample statistic, and over what window?** Median is proposed above for robustness. The
   window length is coupled to question 1.
4. **Does the console display it?** ADR-068 open question 1, unchanged and still open. The permission
   recorded above covers *use*.
5. **Do the 14 trade-only days matter?** Not for a print-driven replay. They would rule out any future
   quote-derived reference over Mar 13–31, and `dt=2025-03-11` should be excluded or verified
   regardless.

## Related

- [ADR-066](adr-066-price-band-follows-the-market.md) — the band follows the reference; this changes what the reference *is*
- [ADR-067](adr-067-market-data-derived-from-the-book.md) — publishing vs collaring; question 1 decided they diverge
- [ADR-068](adr-068-external-price-sources.md) — the pluggable-source decision and the durability rule; this fills its equities row
- [ADR-069](adr-069-the-session-opens-where-the-last-one-closed.md) — the tape supplies the real opens and closes its rule is written against
- [ADR-051](../../YU13-limit-order-book/system/adr-051-last-trade-price-output.md) — the mark tier this sources externally
