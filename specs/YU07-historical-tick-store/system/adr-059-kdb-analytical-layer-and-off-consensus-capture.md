# ADR-059: KDB-X Analytical Layer over the Existing Corpus, Fed by an Off-Consensus Capture Tap

**Status:** Accepted, implemented
**Date:** 2026-07-27
**State:** `YU07-historical-tick-store` (added after the state's original implementation)

## Context

ADR-029 established the tick store: a partitioned ZSTD Parquet corpus of NYSE TAQ data, queried
through DuckDB. Two things were then wanted on top of it. First, a real q/kdb query surface over
the same corpus — the language this asset class is actually analysed in, and the one a reviewer
will ask about. Second, a way to put TraderX's *own* order and execution flow into the same
analytical world, so the engine's behaviour could be queried and replayed with the same verbs as
the market's tape.

Both had to be answered without touching the ingestion contract, and — critically for the second —
without putting anything on or near the consensus path. The cluster's deterministic replay source
of truth is the Aeron Archive, and nothing built for analytics may compete with it, slow it, or be
mistaken for it.

## Decision

1. **KDB-X reads the existing Parquet corpus in place; there is no conversion step.** A q layer
   maps each ZSTD Parquet object as a virtual table and stitches the per-file tables into one
   date/symbol-partitioned table, with partition columns taken from the path the ingest already
   wrote. Nothing is rewritten into kdb's own on-disk format, so there is no second copy to keep
   in sync, no migration, and no divergence between what DuckDB and q see.

   The reader works a row group at a time rather than materialising the table, which is why the
   Community edition's 16 GiB cap turned out not to bind: an aggregate over all 47.8M quote rows
   peaked at 768 MiB.

2. **The market's tape and our own flow are separate tables with separate names** — `quote`/`trade`
   versus `txOrder`/`txTrade`, in separate scripts. A tape print and an engine execution are
   different objects with different provenance, and `txTrade` rows carry an account where tape
   trades never do. A single `trade` table holding both is precisely how a VWAP silently answers a
   question nobody asked — a number that looks right, reconciles against nothing, and is wrong.
   The names cost nothing and make that class of error impossible rather than unlikely.

3. **Our own flow is captured by a leader-side tap that sits off the consensus path.** The tap is
   the third sibling of the existing `TradeNatsPublisher` and `OrderNatsPublisher` in the same
   output-ring drain: leader-only, best-effort, non-blocking. The service thread does one
   non-blocking offer; a daemon thread does every file system call. A stalled disk fills the queue
   and drops rather than wedging apply. It is inert unless `KDB_TAP_DIR` is set, and it stops
   capturing at `KDB_TAP_MAX_MB` (default 256) so analytics can never consume the disk the Archive
   needs.

   Drops are the sampling policy and they are loud: the first and every 10,000th log a WARN, and
   the totals print at shutdown. `.tx.gaps[]` answers the narrower question from the store's end —
   which consensus sequences produced no captured row — and is *expected* to be non-empty around
   control events, which consume sequence numbers without producing a row. It exists so nobody
   quotes an aggregate as a census unread.

4. **Nothing here is authoritative.** The capture log is a kdb tickerplant log, not a journal.
   Delete this entire directory and the cluster still recovers byte-identically; delete the Aeron
   Archive and it does not. That asymmetry is the design.

5. **Both stores are gated by falsifiable q checks.** `selfcheck.q` runs 17 checks over the tape
   with every expected value computed independently in DuckDB over the same files — a
   cross-implementation check rather than kdb agreeing with itself. `txselfcheck.q` runs 18 checks
   over a fixture the cluster itself wrote under real consensus, needing no cluster, corpus or
   network to run.

## Alternatives Considered

- **Convert the corpus into kdb's native partitioned format** — rejected. It doubles storage,
  introduces a migration step on every ingest, and creates two sources that can disagree. The
  native Parquet reader already prunes row groups against the WHERE clause, which was the only
  thing conversion would have bought.
- **One `trade` table with a `source` column distinguishing tape from engine** — rejected. It is
  the cheaper schema and the more expensive mistake: every price aggregate is then one forgotten
  predicate away from being quietly wrong, and the wrongness is invisible in the result.
- **Publish capture through NATS like the trade and order bridges** — rejected for the capture
  path. The bridges exist to feed live consumers; capture is a bulk analytical sink whose natural
  shape is an appended file, and routing it through the broker would put analytics volume on a
  path the trading tier depends on.
- **Capture from the SQL projection instead of the engine** — rejected. The projection is already
  a lossy read model shaped for the UI; capturing there would inherit its filtering and answer a
  different question from the one the store exists to answer.
- **Block or apply backpressure when the capture queue fills** — rejected outright. A backpressure
  path from an analytics sink into the apply thread is the worst outcome available; dropping with
  a loud counter is correct behaviour, and the design makes stalling unreachable rather than
  unlikely.
- **Drop a security whose ticker was never registered** — rejected. Skipping it thins the store
  silently, which is the exact failure mode the tap exists to make impossible; it is captured
  under a synthetic identifier instead.
