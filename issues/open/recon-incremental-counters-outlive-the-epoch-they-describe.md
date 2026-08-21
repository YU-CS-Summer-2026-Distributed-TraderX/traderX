# The incremental reconciler's counters outlive the epoch they describe

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

- **"Reconciliation is clean" is TRUE right now** — but cite the **full-history sweep**, which
  re-examines current state. The incremental counter cannot support that sentence and never will.
- **`missingInProjection` can only go up.** It will read 6 until trade-processor restarts, regardless
  of how healthy the projection becomes. Any future epoch roll can add more.
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
