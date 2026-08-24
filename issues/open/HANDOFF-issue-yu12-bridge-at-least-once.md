# YU12 — Trade Bridge At-Least-Once Gap (stub)

**Purpose:** the leader→NATS trade-egress bridge has no published-offset checkpoint. Across a leader
failover mid-publish, booked trades can be duplicated or dropped on the DB/UI side. Fine for a demo;
not acceptable for a correctness-first OMS. This is a placeholder to keep it tracked.
**Status:** OPEN, low priority (stub — flesh out when the correctness track is picked up). Created
2026-07-20. Untracked working note.
**Parent:** `RECAP-2026-07-20-yu12-bridge-bench-session.md`, `adr-048-trade-egress-nats-bridge.md`.

---

## The gap

The bridge (`TradeNatsPublisher`) offers each booked trade onto an in-memory SPSC queue on the apply
thread and a daemon thread publishes to NATS `/trades`. There is **no persistent record of which
trade sequences have been published**. So:

- **On leader failover:** the new leader has the full replicated state (every booked trade) but no
  memory of what the *old* leader already published. It could re-publish trades the DB already has
  (**duplicates**) or, if it only publishes new trades from now on, miss trades booked-but-not-yet-
  published at the moment of failover (**drops**).
- The DB side (trade-processor) has no dedup key strong enough to make this idempotent today (see the
  batch-insert / FK behavior noted in the state history).

## Sketch of a fix (not decided)

- Persist a **published watermark** (last published trade sequence) in the cluster snapshot, so a new
  leader resumes publishing from the last acknowledged offset — at-least-once with idempotent
  consumer, or exactly-once if the consumer dedups on trade sequence.
- Give the trade-processor a **dedup key** on `(trade sequence)` so replays are absorbed.
- Consider whether the bridge should be a **snapshotted ClusteredService concern** rather than a
  best-effort side channel — that's the real design question.

## Why it's low priority now

The demo path works and real rates don't stress it. This only bites under failover-during-load, which
is exactly the scenario the failover-measurement issue will start exercising — so revisit this
alongside that work.
