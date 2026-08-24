# The incremental reconciler's counters outlive the epoch they describe

> **The values below are a record, not a rig you can query.** Order refs (`1-66`), trade ids
> (`4060-S`), trace ids, security ids, pod names and run counts come from the epoch this was
> measured on. That epoch has been rolled and will be rolled again — order refs restart at 1, the
> symbol table is renumbered, trace ids follow the client order ids of a run that no longer exists.
> Read them as a worked example of the SHAPE. Do not look them up, and do not treat their absence
> on a current rig as evidence about this issue.

**Found 2026-08-20 by the coordinator**, running down an observation the console lane flagged but
deliberately did not file as fact: two recon surfaces answer "is the projection complete?" differently.

```
GET /recon/status              -> cursor 292, matched 286, missingInProjection 6, fieldMismatch 0
POST /recon/orphan-sweep       -> orphanCount 0, localTradeCount 292, fullHistoryTradeCount 292
```

**Both are correct. They answer different questions, and one is worded as if it answers the other.**

## Mechanism

`ReconciliationService` (YU05 layer, `trade-processor`):

- `matched` / `missingInProjection` / `fieldMismatch` are **`LongAdder`s** — cumulative, never reset,
  never decremented.
- The sweep fetches `?sinceSeq=cursor`, and **`cursor` advances past every entry whether or not
  `classify()` found it**. A miss is therefore counted once and **never re-examined**.
- All of it is **in-memory process state**.

So `matched + missing = 292` looks like a partition of current state. It is a partition of *history* —
every entry ever processed, once each, across the whole process lifetime.

## What actually happened here

The six misses are in the log, and they name themselves:

```
2026-08-19T00:39:09Z  recon MISSING_IN_PROJECTION id=25-S  security=AAPL260918C00260000
2026-08-19T00:39:09Z  recon MISSING_IN_PROJECTION id=26-B  security=AAPL260918C00260000
2026-08-19T00:40:39Z  recon MISSING_IN_PROJECTION id=27-S  ...   (31-S, 32-B at 00:44:09)
```

All six are the same 19-character OCC option symbol. But query those ids **now**:

```
25-S  IBM   26-B  IBM   27-S  IBM   28-B  IBM   31-S  IBM   32-B  IBM
```

Same ids, entirely different trades. The timeline settles it:

| | |
|---|---|
| trade-processor started | 2026-08-19T00:25:03Z |
| the six misses counted | 00:39–00:44 |
| **oldest row now in the projection** | **2026-08-19 02:54:34** |

A fresh-epoch roll at ~02:53 cleared the projection and restarted the engine's trade counter at 1 —
**and did not restart trade-processor.** Its cursor and counters survived the reset of everything they
describe. The six were counted against the *old* epoch's seqs 25–32; those keys now belong to new-epoch
IBM trades, and `cursor=292` refers to the new epoch's numbering.

Trade ids are `<tradeSeq>-<side>` and carry **no epoch qualification**, so the same key means different
trades on either side of a roll. A lifetime counter spanning that boundary is comparing keys that are
not comparable.

## Consequences

- **"Reconciliation is clean" can be a true sentence** — but only if it cites the **full-history
  sweep**, which re-examines current state. The incremental counter cannot support that sentence and
  never will.
- **`missingInProjection` can only go up.** Once it has counted a miss it holds that number until
  trade-processor restarts, regardless of how healthy the projection becomes, and every later epoch
  roll can add more. (It stood at 6 when this was written; the figure is an illustration of the
  ratchet, not a current reading.)
- A UI rendering it as current state will announce data loss on a clean rig. The console lane hit
  exactly this and caught it before shipping, by cross-checking against the sweep.

## Not fixed here — options, cheapest first

1. **Rename and re-present**: they are lifetime tallies. `missingInProjectionTotal` with the process
   start time beside it makes the surface honest with no logic change.
2. **Reset the counters and cursor on an epoch change.** The engine's counter restarting is already a
   detected condition — `scripts/yu15/run-proofs.sh` compares the engine trade counter against the
   highest SQL trade id for exactly this reason.
3. **Re-check misses instead of advancing past them**, so a transient lag heals itself. Note this alone
   is not enough across an epoch boundary, where the id now resolves to a *different* trade and would
   match spuriously.

**Do not "fix" this by trusting the incremental number more.** The full-history sweep is the
authoritative comparison, and the console lane's surface now says so on its face.

## Credit and correction

The console lane surfaced the discrepancy and guessed identity — "the two sides keying a trade
differently, which would rhyme with the epoch-qualification traps this project keeps hitting". Right in
spirit and worth recording: the key is not epoch-qualified. The mechanism is a lifetime counter over a
once-through cursor spanning an epoch reset, not a live disagreement between two keying schemes.

A staleness hypothesis was tested and **refuted** before this one was accepted: `lastSweepAt` advances
every 20s, so the sweep is live and genuinely re-reporting the same historical tally.

## Why those six actually missed — NOT async lag, and this is the more serious half

**Corrected 2026-08-20.** A natural reading of the counter mechanism is that the six were classified in
the gap between the 10s engine poll and the asynchronous projection write, i.e. a benign timing
artifact that happened to be recorded permanently. **The evidence refutes that.**

Four seconds before the first recon miss, trade-processor was *rejecting* the writes outright:

```
2026-08-19T00:39:05.733Z  Error: 1406-22001: Data too long for column 'security' at row 1
2026-08-19T00:39:05.746Z  ERROR TradeFeedHandler   Batch trade processing failed for 1 trades; retrying individually
2026-08-19T00:39:05.750Z  WARN  OrderFeedHandler   orderbook write rejected for order 1-25 (rejected=1): ... Data too long for column 'security'
```

**114 timestamped `Data too long` errors**, and they hit `orderbook` as well as `trades`. A 19-character
OCC symbol did not fit the `security` column. The projection did not lag — it **could not store the
row**. This is the known `VARCHAR` OCC blocker, caught in the act.

### The discriminator that made this worth checking

All six misses are the **same OCC symbol** inside a five-minute span, and **no other security ever
missed**. Random async lag scatters across whatever is trading; a systematic cause clusters. The
clustering is what justified reading the logs instead of accepting the timing explanation — and the
timing explanation is exactly the kind that sounds right, requires no evidence, and closes the
investigation.

### Scope — historical and closed, but not benign

```
first: 2026-08-19T00:39:05Z     last: 2026-08-19T00:57:12Z     114 errors, all within that hour
```

Nothing since. All four affected columns are now `varchar(32)` (`trades`, `orderbook`, `positions`
security; `stocks.ticker`), and long-security rows are present in each (4 / 24 / 2), so the widen is in
effect and options store correctly.

So: **historical, resolved, non-recurring — and a genuine write-rejection event rather than a timing
artifact.** The six recon misses are its surviving trace. "The numbers were never wrong" is true of the
counter's semantics; it would be wrong to conclude the six recorded nothing real.

## DECIDED 2026-08-21 by yaakov: option 2 — reset the counters and cursor on an epoch change

Not the cheapest option, deliberately. Renaming them to lifetime tallies would make the surface
honest, but it would leave the numbers describing an epoch that no longer exists and put the burden
on every reader to remember that.

The signal already exists and is already trusted elsewhere: `scripts/yu15/run-proofs.sh` compares the
engine trade counter against the highest SQL trade id for exactly this purpose, so detecting the roll
is not new work.

Still standing from above, and not weakened by this: the full-history sweep remains the authoritative
comparison. Do NOT let a reset counter become a reason to trust the incremental number more.

---

## Resolved 2026-08-21 — option 2 shipped

`ReconciliationService` (YU05 layer, `trade-processor`) now resets the cursor and all three
`LongAdder`s when it detects that order-matcher's trade counter has gone backwards.

### How the roll is detected

No new endpoint, no `/metrics` scrape, nothing tier-specific: the signal is read out of the blotter
the sweep already polls, using the condition `scripts/yu15/run-proofs.sh` already trusts — **the
engine's counter is BEHIND the high-water mark the projection recorded**.

The blotter paginates *strictly greater than* `sinceSeq`. So the sweep now fetches from **one below
its cursor** instead of from the cursor. That re-reads exactly one entry — the one the cursor points
at — and the engine still holding it is the proof that this is still the epoch the counters were
counted against. An empty page there means the engine holds nothing at or above the cursor, i.e. its
counter restarted. That is the roll.

The re-read entry is skipped rather than reclassified, or `matched` would climb by one every poll.

Probing at the cursor rather than one below it would have been the natural-looking implementation
and is wrong in a way that is easy to miss: at the cursor the engine answers with an empty page on
**every quiet cycle**, so the reset would fire perpetually on an idle rig. There is a test whose only
job is to fail if the probe moves back to the cursor.

### The reset logs what it discarded

```
recon EPOCH_RESET order-matcher holds no trade at or above tradeSeq <N> -- its trade counter
restarted, so the epoch these counters describe is gone. Discarding cursor=<N> matched=<M>
missingInProjection=<K> fieldMismatch=<J>; reclassifying from 0. The full-history orphan sweep, not
these counters, remains the authoritative comparison.
```

A counter that silently returns to zero is its own kind of untrustworthy: an operator watching
`missingInProjection` drop needs to know whether that was a repair or a reset.

### "Cannot determine the epoch" is not "the epoch changed"

The unreachable arm resets **nothing**. Treating a failed fetch as a roll would let one network blip
erase a real miss count — leaving the surface clean at exactly the moment least is known about it,
which is the failure shape `.claude/skills/vacuous-pass-audit` exists for.

Nor is it silently taken as "unchanged" forever: that cycle does not touch `lastSweepAt`, so a reader
of `/recon/status` sees it stop advancing and can tell the counters are a stale reading rather than a
current one. No new field was added for this — `lastSweepAt` already carries it, and it is the same
field that refuted the staleness hypothesis recorded above.

### The standing constraint is unchanged and restated in the code

The full-history sweep is still the authoritative comparison. These tallies are still once-per-entry
and forward-only; they are now merely **scoped to the live epoch** instead of spanning dead ones. The
class javadoc and the reset log line both say so, so a reader arriving at either cannot take a
freshly-zeroed counter as a reason to trust the incremental number more.

### What was proven, and how

- Three tests added to `ReconciliationServiceTest` (6 → 9). Each of the three mechanisms was
  detonated against the **whole module** (86 tests) and failed **exactly one** test:

  | inverse applied | failed | the other 85 |
  |---|---|---|
  | the reset block removed | `epochRollResetsTheCountersAndTheCursorAndReclassifiesTheNewEpoch` | green |
  | probe moved back to the cursor | `idlePollWithNoNewTradesKeepsTheCountersAndTheCursor` | green |
  | reset added to the unreachable arm | `unreachableOrderMatcherDoesNotCountAsAnEpochChange` | green |

  Nothing pre-existing covered any of them: without these cases the bug ships.
- The epoch test models the roll as it actually happens — the engine's highest `tradeSeq` drops
  *below* the cursor and the same ids come back naming different trades — and asserts past the reset
  that the next sweep reclassifies the new epoch on its own terms, so the reset re-enables
  classification rather than merely zeroing.
- The stub blotter was changed to honour `sinceSeq` the way `TradeBlotter.since()` does. It had to
  be: a stub that ignores the query answers every probe alike and **cannot tell an idle poll from a
  renumbered engine**, so the epoch tests would have been measuring the fixture.
- `pipeline/generate-state.sh YU17-otc-rates`, then `engine-tests.sh hosted`, `service-tests.sh`,
  `assert-suites-executed.sh`: all rc=0, 564 tests across 6 modules, 0 failures. (Baseline 555; +3
  here, +6 from concurrent algo-engine work by another lane in the same worktree.)

### What was NOT proven

- **Never exercised on a rig.** No epoch roll has been driven against a live trade-processor
  carrying this build; the image has not been rebuilt or rolled on either tier. Everything above is
  unit-level against a stub blotter. The `EPOCH_RESET` line has never come out of a deployed pod.
- **The mid-replay false positive was reasoned about, not constructed.** If order-matcher serves the
  blotter before its journal replay has repopulated it, this reads as a roll. The consequence is one
  full reclassification from seq 0, logged — the same remedy `scripts/proofs/yu05-recon.sh` performs
  by hand today by restarting trade-processor — so it is benign in direction. That is an argument
  about the code, not a measurement: no rig was put into that state to watch what the reset does.
- **No hysteresis.** A single empty page triggers the reset; two consecutive were not required.
  Deliberate, given the benign failure direction above, but it means one transient blotter gap costs
  a reclassification.
- **`lastOrphanSweep` is deliberately not reset.** It is a dated snapshot carrying its own `sweptAt`,
  so it does not ratchet the way the adders did. Out of scope for the decision above; left alone.
- **`/recon/status` gained no new field.** Nothing on that surface says "these counters were reset
  at T" — only the log does. A console rendering the counters still cannot distinguish "0 because
  clean" from "0 because just reset" without reading logs. Left out deliberately: adding a
  `since`-style field is option 1's re-presentation, which was explicitly not chosen.
- **`scripts/proofs/yu05-recon.sh` was not changed.** It still restarts trade-processor before it
  will believe a forward-sweep verdict. That restart remains a valid way to force a from-zero
  classification and the proof does not depend on this fix; rewriting a shared proof script was not
  part of this work.
- **Branch-local.** This landed on `YU17-otc-rates` only. Every branch from YU05 onward carries its
  own copy of the YU05 layer, so `YU05` … `YU16` still have the ratcheting version. Whether to
  hand-carry it (`.claude/skills/propagate-spec-fix`) is the coordinator's call, not assumed here.
- **The six historical misses recorded above were not re-examined.** They were a `VARCHAR`
  write-rejection event, resolved separately by the column widen; nothing here revisits them.
