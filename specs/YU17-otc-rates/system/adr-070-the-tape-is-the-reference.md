# ADR-070: The tape is the reference, replayed on an epoch clock

## Status

**Accepted** (2026-08-26), implemented on YU17. Open questions 1, 2 and 3 are answered in
[Sizing, measured](#sizing-measured-2026-08-26-not-picked) and
[Open questions](#open-questions--dispositions-2026-08-26) below; the end-of-tape ruling (hold at
Mar 31's close, never loop, never fall back) and the share-class scoping are recorded there too.

Originally **Proposed** (2026-08-24). Raised by yaakov: we hold a licensed TAQ corpus
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

## Sizing, measured (2026-08-26), not picked

The proposal called questions 1 and 3 "a sizing question with a measurable answer, not a
preference", and they are coupled through one identity: the resample window is the tape time that
passes per sequenced tick. The adapter's flush interval is the sequenced cadence — 15s, itself
measured into `feed-adapter.yaml` — so

    window = FEED_FLUSH_MS x compression

makes every flush land in a fresh window: the sequenced tick rate stays byte-for-byte identical to
today's (decision 1's bound), no window is ever skipped, and none is published twice.

(On counts, to head off a units confusion: the flush carries the rig's full published universe of
~69 *instruments* — the "~69" this ADR's context uses. The replay changes the *content* of the 23
equity/ETF rows in that flush and nothing about its size or cadence; treasuries, corporates and
options keep their own sources per decision 3.)

**Chosen: window 195s, compression 13x.** One trading day (23,400 s of RTH) = 30 wall-clock
minutes, 120 windows/day; the 40-day tape spans **20 hours** of wall clock. Measured against
samples chosen to bracket the corpus — most and least liquid demo names (SPY/AAPL vs DFS/FNF),
calmest and wildest days (Feb 3, Mar 11, Mar 31), regular hours only:

- **Sample floor.** The worst single 195s window in the sample holds **22 prints** (FNF, Feb 3);
  p5 across all symbol-days is >= 42, and there are **zero empty RTH windows** — at 60s windows
  FNF still never goes empty, so 195s carries wide margin, not the minimum.
- **Step size.** Adjacent-window median moves: p99 <= 1.3%, worst 2.06% (DFS on Mar 31 — a real
  repricing, not noise). Far inside the collar band, so a per-flush step can never strand the band.
- **Why 13x and not the proposal's example 39x (one day per ten minutes):** at 39x the tape spans
  6.7 hours, and the epoch live on the rig while this was measured was already **12.6 hours old**
  (member PVC age) — every demo on it would have found the reference held at Mar 31 before anyone
  looked. At 13x the tape outlasts that epoch with margin while a one-hour demo still crosses two
  real session boundaries. Fixed, not configurable: it is baked into the extract, because the
  window and the compression are one decision and a knob that moved one without the other would
  silently break the flush identity above.

**The filtering emphasis in decision 1 was wrong, and the tape says by how much.** Re-measured on
raw TAQ (A, AAL, AAPL, ABBV; Feb 3 RTH; 1.07M prints, `TR_SCOND`/`TR_CORR` intact):

- Corrections (`TR_CORR != 00`), the failure mode the proposal emphasised: **0.034%** of RTH prints.
- Prints whose condition codes mark them non-current (derivative, average-price, out-of-sequence,
  prior-reference…): 1.6–9.1% per symbol. Their effect on the per-195s-window median —
  |median(all) − median(filtered)|: **0.00 bps median, <= 3.8 bps p99, 32 bps worst** across 480
  symbol-windows.

So the unfilterable-print exposure the dropped columns leave behind is worth basis points at this
window size, and the median absorbs it. The proposal's *mechanism* (median over the window) was
right; its *threat model* (corrections) was two orders of magnitude smaller than the condition
codes, and both are immaterial. Recorded so nobody re-ingests 650 GiB to recover columns worth
single-digit basis points to a collar reference. (Same measurement, free corroboration: raw AAPL
Feb 3 RTH has 802,923 trades and so does the tick store — YU07's ingest dropped columns, never
rows. And `dt=2025-03-11`, the OOM-retry suspect, verified complete: full RTH coverage on every
sampled symbol, fragmented into more files but missing nothing.)

**The replayed universe is 23 of the rig's 25 equity/ETF names** — every one verified present on
`dt=2025-02-03`, the same day the listable universe was pinned to (`473dff07`). The two exclusions
are deliberate, and they do **not** fall back to the synthetic walk.

**Widened to 100 symbols, 2026-08-26 (yaakov's request).** The extract now carries the 23
incumbents — byte-identical series, verified against the running publisher's own mount — plus the
77 most liquid S&P 500 names not already in it. It is a **union, deliberately**: the ranking alone
would have dropped thirteen names that were already replaying (IBM, GS, UBS, DB, COF, DFS, FIS, FNF
and the five ETFs), silently reverting each to the walk. Liquidity is ranked by day-1 partition byte
size — the parquet is one row per print, so bytes are prints — from a single recursive listing of
`dt=2025-02-03`, which orders all 10,081 tape symbols with no query and no download. The cut lands
at LUV, 3.9 MiB against a 1.48 MiB all-symbol mean.

Three facts the widening established that the 23-symbol build could not:

- **The Secret still fits, with room.** 100 symbols x 40 days x 120 windows gzips to **837,323
  bytes** against a Kubernetes Secret's 1,048,576-byte ceiling (measured against the rig: 1,048,576
  accepted, 1,200,000 refused). No init container, no volume, no change to
  `lib-replay-epoch.sh`. `build-taq-replay-extract.py` now refuses to write an extract over that
  ceiling, so the next widening finds it at build time rather than at a bring-up.
- **Two symbols cannot be read at all**, and they are why the universe is 100 and not 102. `AMD`
  and `ANET` each carry 41 **truncated** copies of one `dt=2025-03-11` object (`PAR1` header, no
  `PAR1` footer), and one unreadable file fails the entire external-table scan. BA and AFL take
  their places. This does not contradict the paragraph above — that verification sampled the 23,
  which contain neither — and it is filed as
  `issues/open/two-symbols-are-unreadable-on-the-oom-retry-day.md`. Seven other symbols carry 42
  *complete* duplicate copies on the same day, which a median is invariant under; only the footer
  separates the harmless case from the fatal one.
- **Nothing on the wire changed.** The rig quotes 25 equity/ETF names, so the 77 additions are
  carried by the extract and published by nobody: the flush is the same size, at the same cadence,
  with the same five provenances (23 / 24 / 15 / 4 / 2). Decision 1's rate bound is untouched. The
  additions exist for the console's day and range views, and for the day the quoted universe grows.

**Corrected 2026-08-26, measured off the wire.** Earlier drafts of this section, and the briefs
written from it, said the exclusions "stay on the synthetic walk". They do not: both publish
`source: previous-close` with a **wall-clock** `asOf`, i.e. a carried-forward last close that does
not move. The distinction is worth the correction because it is the difference between a name that is
visibly **frozen** and one that is visibly **fake**, and the first is the more honest thing to show.
There are in fact **five** provenances on the wire simultaneously — `taq-replay-2025-02` (23),
`black-scholes` (24), `fred-us-treasury-cmt-curve` (15), `simulated-corporate-credit-spread` (4) and
`previous-close` (2) — so **any consumer that models provenance as tape-vs-synthetic is wrong twice**:
it labels the FRED curve fake, and it labels a stale carried-forward close live. Found by the UI lane
building the provenance chip; the wire was always right and the prose was not.

- **GOOGL** — the store's `GOOG` partition merges Alphabet's two classes (the ingest dropped
  TAQ's `SYM_SUFFIX`; `issues/open/tick-store-drops-taq-sym-suffix-and-merges-share-classes.md`).
  A median over a merged root is a price for no security that exists, and it is *invisible in the
  price distribution* — so neither Alphabet symbol replays until a re-ingest recovers the suffix.
  Scoping ruling, 2026-08-26: exclude now, re-ingest later if ever; the issue stays open.
- **FNMA** — OTC, not in TAQ, exactly as this ADR's decision 3 already noted.

**The live move-limit guard proposed under decision 1 was not built.** The extract is a median
computed offline over >= 22 prints — the sanitisation already happened, once, where the whole
window is visible. A runtime N% guard would have to wave through the tape's real overnight gaps
(the headline feature) while catching bad values a median already caught; the judgment it needs is
the one the offline build already makes with more information. It stays worth having the day the
source is a live stream again.

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

**A fresh epoch rewinds the tape, and the EOD database does not rewind with it** (found on the
first suite run, 2026-08-26). The mint restarts the clock at Feb 3 while the prior epoch's
published closes persist in `eod-price-db`, so the first close after a mint flags every replayed
equity — and the option quoted off one — SPIKE against a baseline from a different point on the
tape. The gate is *right*: the discontinuity is real. And it cannot resolve itself, because a
flagged session stays DRAFT and the baseline never re-anchors (measured: flagged=19 held across
60 consecutive closes). The resolution is the one every EOD proof already uses for a flagged
mark: **override at the observed close**, once, after which closes are tape-continuous for the
rest of the epoch. `yu06-quality-gate` does exactly this, and an operator minting an epoch
outside the suite should expect the same one-time override set on the first close.

## What this is explicitly NOT

- **Not a market simulator.** The tape drives a *reference*, and the resampled series is never
  presented as depth. **The clause forbidding injected prints was REVERSED 2026-08-26 by
  [ADR-072](adr-072-replayed-prints-become-order-flow.md)** — replayed prints may now enter as order
  flow, sampled and with a tick-rule side. The rest of this bullet stands: replayed orders do not set
  the collar's reference, and a replayed print that breaches the collar is rejected.
- **Not a TCA or VWAP source.** The store holds unfiltered prints. Any volume-weighted number computed
  from it is not reference-grade and must not be described as one — a constraint on what we assert,
  independent of this ADR.
- **Not a dependency.** Synthetic remains sufficient (ADR-068 rule 1).
- **Not a change to what the collar references.** ADR-067 question 1 decided the collar keeps an
  exogenous-first reference precisely so that a book cannot determine its own guard rail. Replacing
  the exogenous series with a better one honours that decision; it does not revisit it. If anything it
  strengthens it — the reference is now genuinely exogenous rather than a walk this system invented.

## Open questions — dispositions (2026-08-26)

1. **Compression ratio: 13x, fixed.** Measured, with the reasoning and the numbers in
   [Sizing, measured](#sizing-measured-2026-08-26-not-picked). Fixed because window and compression
   are one decision (the flush identity), and both live in the extract, not in a knob.
2. **End of tape: HOLD at Mar 31's close, and let `asOf` say so.** Ruled by yaakov 2026-08-26,
   before it could be discovered live. Looping fabricates the exact seam this ADR exists to make
   real (Mar 31's close to Feb 3's open is an invented overnight gap); falling back to synthetic
   silently changes provenance category mid-run, which is what decision 4 exists to prevent; and
   holding needs no new mechanism — `asOf` was introduced to carry "a real price at a fabricated
   time", and a frozen reference with an honestly ageing `asOf` is exactly that. At 13x the tape
   spans 20 hours, so the hold is a rare path rather than a demo path — and
   `yu17-taq-replay.sh` exercises it deliberately, by re-stamping the epoch into the past.
3. **Statistic and window: median over 195s.** The median survived its re-examination (the merged
   share class is the one thing it cannot defend against, which is why GOOGL is excluded rather
   than defended); the window is measured above.
4. **Does the console display it?** Unchanged, still ADR-068 open question 1. The permission
   recorded above covers *use*; nothing here extends it.
5. **The trade-only days do not bite** a print-driven replay, exactly as suspected; and
   `dt=2025-03-11` was **verified rather than excluded** — complete RTH coverage on every sampled
   symbol, more fragmented, nothing missing. Any future *quote*-derived reference over Mar 13–31
   remains ruled out by the corpus itself.

## What the implementation is, in one paragraph

`price-publisher` gains `taq-replay.js`: at bring-up it reads the resampled extract (~820 KB
gzipped: 100 symbols x 40 days x 120 window-medians; 23 symbols and ~280 KB until 2026-08-26) from a Secret that `start-cluster-kind.sh`
fetches out of `gs://traderx-501015-tick-store/replay/`, and the epoch anchor from a `replay-epoch`
ConfigMap that the bring-up and `rebuild_fresh_epoch` stamp from the member-0 PVC's
`creationTimestamp` — which IS the mint instant, so restamping is idempotent and the anchor can
never disagree with the epoch it describes. Every tick derives its position from
`(now − epochStartMs) x compression` and looks the median up; nothing stores a cursor (decision
2). A symbol the extract does not carry falls through to the walk with its provenance unchanged.
Failures — no Secret, no stamp, an invalid extract — are all-or-nothing and loud on
`/health.taqReplay`, and the walk continues underneath (ADR-068 rule 1). The extract is built once
by `scripts/yu17/build-taq-replay-extract.{sh,py}` — a BigQuery external table over the parquet
tree computes the medians in-region and `EXPORT DATA` writes them bucket-internally, so decision
1's "computed once, in-region, at zero egress" is literally true: no byte of the corpus ever
reaches a laptop or a node (measured: ~50 s, ~5 GiB scanned, free tier). The exact medians were
cross-checked against an independent pandas computation over raw partitions — 1,800 of 1,800
windows identical. `scripts/proofs/yu17-taq-replay.sh` asserts
the clock/extract agreement, the wire provenance, the exclusions, the member-side sequencing, the
restart-invisibility, and the hold.

## Related

- [ADR-066](adr-066-price-band-follows-the-market.md) — the band follows the reference; this changes what the reference *is*
- [ADR-067](adr-067-market-data-derived-from-the-book.md) — publishing vs collaring; question 1 decided they diverge
- [ADR-068](adr-068-external-price-sources.md) — the pluggable-source decision and the durability rule; this fills its equities row
- [ADR-069](adr-069-the-session-opens-where-the-last-one-closed.md) — the tape supplies the real opens and closes its rule is written against
- [ADR-051](../../YU13-limit-order-book/system/adr-051-last-trade-price-output.md) — the mark tier this sources externally
