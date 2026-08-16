# HANDOFF — put the consensus sequence on the trade egress (kind-local)

**Status:** not started. **Rig:** kind only (`kind-traderx-yu12-cluster`) — no GCP credits.
**Size:** small. This is the smallest of the four and the highest leverage per line.
**Unblocks:** `HANDOFF-continuous-portfolio-risk.md` — that work cannot make its central claim
without this.

---

## The gap, precisely

The EOD risk extract is **cut at a consensus sequence N**. Its header says so:

```
# consensusSequence=396
```

and the readiness announcement carries `quiesceWitnessSequence` = N+1, the cluster proving nothing
was sequenced between the cut and the witness.

The trade egress carries **no consensus sequence at all**.
`TradeNatsPublisher` (in `specs/YU12-aeron-cluster/generation/runtime-overrides/order-matcher/src/
main/java/finos/traderx/ordermatcher/cluster/TradeNatsPublisher.java`) publishes a
`NatsEnvelope<TradeOrder>` JSON onto `/trades`, whose identity is `id = tradeSeq + side` — the
engine's own trade counter, not the log position.

**Consequence:** any downstream consumer built on `/trades` can tell you what it has seen, but not
*where in the log it is*. It can never say "my state as of sequence N". So it can never be compared
against the extract, which is defined at exactly that instant. The two artifacts are computed from
the same log and are structurally unable to be checked against each other.

Closing that is a field.

---

## What the publisher does today (verified, from its own header)

- publishes `NatsEnvelope<TradeOrder>` JSON on the `/trades` subject that `trade-processor` already
  consumes; trade-processor persists Trade + Position to SQL and republishes
  `/accounts/{id}/trades` and `/accounts/{id}/positions`, which the Angular UI subscribes to
- **at-least-once** delivery: `id = tradeSeq + side`, and trade-processor keys Trade by id so a
  replay dedups
- **leader-only**: role check at the call site, so followers never duplicate
- **never blocks the service thread**: `offer` is non-blocking onto a lock-free SPSC queue, drained
  by a daemon thread

Those four properties are load-bearing. Do not break any of them.

---

## The work

Add the consensus sequence to the envelope at the point of `offer`, and carry it through the drain
thread to the published JSON.

Design notes:

- The sequence must be captured **on the service thread at apply time**, not read by the daemon
  thread when it drains. By the time the daemon runs, the engine has moved on and the value is
  wrong. This is the whole subtlety of the change.
- The `Rec` record in the publisher is the queue element — the sequence goes there.
- Adding a field to the JSON envelope is backward compatible for existing consumers
  (`trade-processor` ignores unknown fields), so this does not require a coordinated rollout of
  consumers. **Verify that assumption against the consumer's deserializer before relying on it** —
  a strict-mode Jackson config would reject unknown properties.
- Consider adding the **epoch** alongside it. Trade numbering restarts on a wiped epoch, and a
  consumer that conflates two epochs' trade ids will silently mis-key. The order read model already
  uses epoch-qualified ids (YU13, brief 07) — follow that pattern rather than inventing a second one.

---

## Is this a deterministic-core change?

**Read this before touching anything.** The publisher lives inside the order-matcher's clustered
service, so the file is in the deterministic core's blast radius — but the *bridge itself is not
replicated state*. It is a leader-side, off-consensus egress, in the same category as the kdb tap.

What matters:

- If your change only reads a sequence the engine already holds and puts it in an outbound JSON, it
  does **not** alter the state machine, and members cannot diverge from it.
- If you find yourself adding a field to the snapshot, changing an event's wire layout, or making
  the publisher's behaviour affect what is applied — stop. That *is* a core change, it cannot be
  rolled gradually, and it needs a `SNAPSHOT_FORMAT` bump (currently **4**, with
  `MIN_READABLE_SNAPSHOT_FORMAT` **3**).

A mixed-version window on the deterministic core diverges members permanently. This change should
not create one; make sure yours doesn't.

---

## Acceptance

- [ ] A message on `/trades` carries the consensus sequence at which the trade was applied.
- [ ] The sequence is captured on the service thread, and a test proves it is the apply-time value —
      not whatever the engine had reached when the daemon drained.
- [ ] The four load-bearing properties survive: at-least-once, leader-only, non-blocking offer,
      dedup by id.
- [ ] `trade-processor` still consumes the stream unchanged and the UI still updates.
- [ ] `bash scripts/yu15/run-proofs.sh` → 19/19.
- [ ] A rolling restart of the members does not diverge the book (digest identical on all three).

---

## Explicitly out of scope

- Building the consumer. That is the continuous-risk handoff.
- Changing what `trade-processor` does with the field. It can ignore it.
- Adding sequences to any other egress (the order read model, the kdb tap) unless it falls out for
  free — keep the diff small enough to review.

## Conventions

Never `git push`. No `Co-Authored-By: Claude` trailer, no "Generated with Claude Code" — commit as
yaakov only. This handoff file stays **untracked**.
