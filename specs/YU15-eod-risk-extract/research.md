# Research: EOD Risk Extract

## Why positions cannot come from the database

TraderX maintains positions in two places. The SQL `positions` table is a CQRS read model: the
ADR-048 trade bridge republishes booked trades to `/trades`, `trade-processor` consumes them and
upserts rows on its own schedule. Nothing about that pipeline is synchronised across accounts —
each row lands when its trade's message happened to be processed.

A portfolio assembled by querying that table is therefore a set of accounts each sampled at a
slightly different moment. For a blotter that is fine. For a VaR computation it is not: the
covariance structure of a portfolio only means something if the positions in it were held
simultaneously. A portfolio stitched from different instants is one the firm never held, and the
risk number computed from it does not describe any real exposure.

The cluster has the opposite property for free. Every member applies the same totally-ordered
committed log, so the state of the replicated state machine at any log sequence is, by
construction, a state that genuinely existed — atomically, across all accounts. No coordination is
needed to obtain it; only a way to name a sequence.

A second, blunter reason points the same way: every `security` column in the SQL schema is
`VARCHAR(15)` or `VARCHAR(16)`, and an unpadded OCC option symbol is 19 characters. The read model
cannot represent a listed option at all.

## How to name a sequence

Two mechanisms were considered.

**Parsing the Aeron Archive snapshot.** Snapshots already carry positions as T_POSITION records
(ADR-046), so the data is there. But a snapshot happens when the consensus module decides it
should, so its sequence is whatever it is — possibly hours before the EOD batch runs. There is no
way to request "the state as of now". The reader would also have to re-implement the snapshot
record codec outside the state machine and stay in lockstep with its format version, which
duplicates exactly the logic ADR-046 centralised.

**A sequenced command.** Ingress that mutates nothing still occupies a sequence, and that sequence
is agreed by consensus rather than sampled by a reader. The state at it is the same on every member
and on every replay. This is strictly more precise (exact N, on demand), needs no new codec, and
keeps the rendering inside the state machine where the state actually lives. It is what ADR-055
adopts.

## Why the cut leaves over NATS rather than cluster egress

ADR-048 already established that cluster egress is the wrong tap for anything that must be
complete: it is best-effort by design and delivered only to the submitting session. For trades,
that meant losing the resting side under load. For a risk extract the failure mode is worse,
because a partially delivered cut arrives looking like a complete one — a portfolio missing rows is
indistinguishable from a smaller portfolio.

Publishing the cut as a single NATS message that carries its own row count makes the failure modes
honest: a lost message is a visible timeout, and a truncated one fails the row-count check. The
same leader-side, non-blocking, daemon-thread shape ADR-048 uses keeps the deterministic apply
thread off the network.

## Why marks still come from the database

The published closing-price snapshot is the opposite kind of table from `positions`. It is
addressed by `(session_date, version)`, it is written once when a version is published, and a
correction produces a new version rather than an update. Reading it is a lookup in a frozen table
— reproducible forever, with no consistency hazard, which is exactly why it can be joined to a
cluster cut without reintroducing the problem the cut exists to solve.

The extract does not read `eod_position_pnl`. It recomputes market value from the same published
price version using the same formula, which reproduces that table's values exactly for equities
while still being correct for portfolios the read model cannot represent. It also removes a
dependency on a table that is empty for any account holding an option, because YU06's fail-safe
halts an entire account when any of its holdings is unpriced.

## Why quiescence is witnessed rather than assumed

The extract joins state from two different subsystems: positions from the cluster at sequence N,
marks from the EOD price chain. That is only sound because the market is closed and neither is
moving. "The market is closed" is an assumption about the outside world, and assumptions about the
outside world are the ones that quietly stop being true.

The consensus log can check it directly. A second marker sent after the join must land at exactly
`N + 1` — nothing but the two markers can have been sequenced in between. It costs one extra log
entry and turns an assumption into a verified precondition, with the witness sequence recorded in
the delivery announcement.

## What reproducibility actually requires

"Identical bytes forever" decomposes into two independent properties, and separating them is what
makes each provable:

1. **The cut at N is reproducible.** It is a pure function of replicated state, so every member
   renders it identically and any replay to N renders it again. Verified by comparing the hash
   every member logs, and by deleting a member and watching it re-emit the same hash during
   replay.
2. **The fixture is a pure function of the cut.** Everything else it consumes — the published price
   version, the counterparty mapping — is immutable and addressed by the stamp. Verified by
   rebuilding from the stored cut and byte-comparing.

Operational facts about a build (the witness sequence, the sink URI, timestamps) are deliberately
kept out of the fixture body and put in the announcement instead. If they were in the file, the
file would no longer be a function of the cut alone and could not be rebuilt from it.

The mechanical requirements follow: explicit sorting rather than trusting hash iteration order,
fixed column order, integer ticks with exact decimal arithmetic rather than floating point, no
clock anywhere in the body, and US-ASCII with `\n` endings.

## An idle cluster is the normal EOD state

Readiness compared each member's `engine().blpSeq()` against its peers'. That counter advances only
when the engine applies an event, and a member restored from a snapshot has applied none — so it
reports `-1` while in fact holding fully caught-up state, and never becomes ready until trading
resumes.

Any member restarting into a quiet cluster hit this, but the EOD window is precisely when the
cluster is quiet, so this state is the one that surfaces it. The consensus-log position is the
correct measure of catch-up in any case: it is what the snapshot restores and what every sequenced
input advances, including inputs the engine never sees.
