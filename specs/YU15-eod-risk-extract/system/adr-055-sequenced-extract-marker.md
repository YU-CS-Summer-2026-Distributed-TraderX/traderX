# ADR-055: A sequenced marker names the consensus sequence the extract is cut at

Status: Accepted

## Context

The pricing/risk engine consumes a portfolio snapshot and computes VaR from it. Its author's
binding constraint is that **every account must be frozen at the same instant**: a portfolio
assembled from accounts sampled at different moments is not a portfolio the firm ever held, and a
VaR computed on it is not a number that means anything. Everything else the extract does is
negotiable; that is not.

TraderX has two places positions live. The SQL `positions` table is a read model fed
asynchronously through the ADR-048 trade bridge — `trade-processor` consumes `/trades` and upserts
rows on its own schedule. Reading it account by account samples each account at whatever moment its
last upsert happened to land, which reproduces exactly the invalid-VaR case above. It also cannot
represent a listed option at all: every `security` column in the schema is `VARCHAR(15)`/`VARCHAR(16)`
and an unpadded OCC symbol is 19 characters.

The other place is the cluster itself, where positions are replicated state maintained by the
deterministic engine. Every member applies the same totally-ordered consensus log, so **the state
at any given log sequence is a consistent cut by construction** — no quiesce, no locks, no
stop-the-world. The question is only how to name a sequence and get the state at it out.

Two ways to do that:

1. **Parse the Aeron Archive snapshot.** Snapshots already contain positions (T_POSITION records,
   ADR-046). But a snapshot is taken when the consensus module decides to take one, not when the
   EOD batch asks — so its sequence is arbitrary and possibly hours stale, the reader would have to
   re-implement the record codec outside the state machine and track its format version forever,
   and there is no way to ask for "the state as of now".
2. **Send a sequenced command through the consensus log.** The command mutates nothing; its only
   effect is that it *occupies a sequence*, and that sequence is agreed by consensus.

## Decision

A **risk-extract marker** (`RiskExtractMessage`, SBE template 8) is ordinary cluster ingress. It
carries only the stamp the extract is named by — request id, session date, closing-price snapshot
version — and no state whatsoever.

On apply, every member advances `appliedSeq`, renders the position cut at that sequence, and
records its SHA-256. The render is a pure function of replicated state with rows sorted by
`(accountId, securityId)`, fixed columns, integer ticks, and no clock — so all members produce
identical bytes, and so does any later replay to the same sequence. Only the **leader** hands the
cut to a NATS bridge, on the same non-blocking SPSC-queue-plus-daemon-thread shape ADR-048
established for booked trades: the deterministic apply thread renders, a daemon thread does the
I/O, and the state machine never blocks on the network even for a once-a-day batch.

The cut travels as **one NATS message** carrying its own `rows=` count. Cluster egress was rejected
for the same reason ADR-048 rejected it: egress is best-effort and reaches only the submitting
session, so a truncated cut would arrive looking complete. A single self-counting message makes
truncation detectable and a lost message a visible timeout instead of a silently short extract.

**Quiescence is witnessed, not assumed.** The producer sends a second marker after the join and
requires it landed at exactly `N + 1`. Nothing but the two markers can have been sequenced in
between, so the log is its own witness that no trading occurred while the extract was built. If the
sequences differ by more than one, the producer refuses to emit.

## Consequences

"The extract for sequence N" is a stable, immutable name. It is reproducible in the strong sense:
a member restarted and replayed to N re-renders the identical cut (proven live on kind — member 2
deleted, recovered, and re-emitted the same SHA-256 at the same sequence), and the fixture is a
pure function of the cut plus immutable reference data, so it rebuilds byte-identically from the
stored cut alone with no cluster involved.

The marker is a hot-path change in the sense that it lands in `onSessionMessage`, so it is routed
by template id before the order-flow branch and never allocates on the ordinary path; `noGcTest`
and all four allocation gates stay green. Because it mutates nothing, it is safe to interleave with
live trading — the cut is simply a read of state the log has already agreed on. It costs one
sequence per extract and one O(positions) render per member per extract, which at once a day is
free.

The cut is bounded by the NATS 1MB default payload — roughly 15k position rows. Past that the cut
has to be chunked or written straight to the object store; the row count in the header is what
would catch a silent overrun today.
