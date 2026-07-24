# 06 — kdb for journaling and playback

> *"For journaling and playback, it would be good if you could move over to kdb — a very popular time
> series db in the financial world, and it would make it all feel that much more realistic."*
> Lane: implementation. **Scope is SETTLED — build it.** See [[00-INDEX]].

## ✅ DECIDED (professor, 2026-07-24): kdb is the TIME-SERIES STORE

**kdb becomes our market-data / trade time-series and replay-analytics store.** It slots into
**YU07 (historical tick store)**, which already does this job with a different backend. This is
genuinely what kdb is used for on trading floors, so it delivers the realism the professor is after.

**The Aeron Archive consensus journal stays as-is.** That journal is the deterministic replay source of
truth for the replicated state machine, and every correctness property we have proven rests on it:
byte-identical state across all three members, reproducible replay, cold-follower rejoin from an empty
disk, and the sequence-addressed EOD risk extract Alex's engine consumes. It is not being replaced.

**Keep these two clearly named — in the code, the docs, and on the slides.** They are different things
that both get called "journal" and "playback":

| | authoritative | analytical |
|---|---|---|
| **store** | Aeron Archive (+ snapshots) | kdb |
| **purpose** | consensus, recovery, determinism | query, analytics, demo playback |
| **playback means** | replay the log to rebuild exact state | replay a captured session for analysis |
| **on the hot path?** | yes, synchronous, before commit | **no — off-consensus, best-effort** |

Conflating them would undermine the recovery story, which is one of our strongest. Being able to
articulate *why* we kept the authoritative journal on Aeron while adding kdb alongside is itself good
presentation material — it shows the durability model was a deliberate design choice, not an accident.

## The job

1. **Stand up kdb** and define the schema for ticks, orders, and trades.
2. **Feed it off-consensus** — a leader-side best-effort tap on the output ring, mirroring
   `TradeNatsPublisher` / `OrderNatsPublisher`. Best-effort, sampled under flood, never blocking apply.
3. **Playback** — replay a captured session from kdb for analytics/demo. Be explicit in the docs that
   this is *analytical* playback, distinct from the *authoritative* consensus replay used for recovery.
4. **Fold into YU07** rather than creating a parallel subsystem, unless there's a reason not to.

## Traps

- **Do not put a kdb write in the apply path.** Same rule as every other bridge: leader-side,
  off-consensus, best-effort, with a visible drop signal (the silent-drop bug class has bitten this
  project four times).
- Watch the **output-ring self-deadlock class** — the service thread is producer and consumer of the
  output ring; a fan-out that outpaces drain can wedge apply. Reuse the `OutputPublisher`
  drain-and-retry discipline.
- Keep the **two playback concepts clearly named** in docs and on slides. Conflating "kdb playback" with
  "consensus replay" would undermine the recovery story.
- kdb licensing/deployment on GKE needs checking early (there's a free tier; confirm it fits).

## Deliverable

kdb capturing the live flow, a working playback, docs distinguishing analytical playback from consensus
replay, and a note on what it cost the hot path (should be zero — prove it, don't assert it).

## Conventions

Commit per capability; `git push` goes to yaakov.
