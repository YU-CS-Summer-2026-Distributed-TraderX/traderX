# A torn algo log replays "clean" and leaves live children with no parent

> The values below are a record, not a rig you can query. Order refs, parent ids and counts come from
> the epoch this was measured on; that epoch has been rolled and will be rolled again. Read them as a
> worked example of the shape, and re-derive from the rig in front of you.

Measured on the cluster kind rig 2026-08-21, as a follow-up to the algo-engine recovery-logging work.
It is the case that sequence of work did **not** cover, and it is the quiet one.

## The sequence

1. A TWAP parent is running and slicing — several buckets submitted as real child orders.
2. The NATS broker is wiped (its JetStream volume is `emptyDir`, so a pod restart is a wipe).
3. The engine's **in-memory** schedule survives, because the process did not die. It correctly logs
   `STATE LOST … UNRECOVERABLE`, and it **keeps slicing** — appending `submitted`/`fill` events to the
   freshly recreated, empty stream.
4. The stream now holds a **torn log**: a tail whose parent-created event is missing.
5. The engine is restarted.

## What the operator sees

```
INFO  replayed 6 of 6 algo-engine events from TRADERX_ALGO_ENGINE (last sequence 6)
```

`REPLAYED` — the **only verdict that logs at INFO**, deliberately, because it is the quiet, healthy
one. No exception, no restart loop, container ready. And:

- `GET /algo/orders` returns `[]`
- `GET /algo/orders/<parent-id>` returns **404**
- the children are **still live in the book**, `status=NEW`, in the read model

So the engine reconstructed nothing, reports a complete replay, and the resting exposure those
children represent belongs to no parent. Nothing will cancel them, resize them, or finish the
schedule. In the worked example that was four children of 120 each — 480 shares nobody owns.

## Why the classification cannot see it

This is not a bug in the verdict logic, which is why it needs its own fix rather than a tweak.

**A wipe resets the stream's sequence numbering to 1.** After it, `first_seq=1`, `last_seq=N`,
`messages=N` — a torn log is *arithmetically indistinguishable* from a complete one. `replayed N of N`
is a true statement. The recovery classifier compares the consumer's count against the broker's count,
and on a torn log those agree perfectly. The tear is invisible from both sides being compared.

## The signal that does exist

The engine already knows. `applySubmitted` / `applyFillObserved` **return early on an unknown bucket** —
that early return is the tear, observed, once per orphaned event, during replay. Today it is silent.

Counting those early returns across a replay and folding the count into the verdict turns this from a
silent orphan into a stated one — something in the shape of *"replayed N of N, but M events referred to
parents this replay never reconstructed; their child orders may still be live in the book."* That reuses
the existing recovery-verdict surface rather than adding one, and it does not require the engine to
know anything it does not already touch.

**Whatever is built, break it first**: a replay of a torn log must fail the test if the count is not
reported. A test that passes on both a torn and an intact log is asserting nothing — see
`.claude/skills/vacuous-pass-audit`.

## What was and was not established

**Measured:** the whole sequence above, end to end, on the kind rig — including that the parent keeps
executing after the wipe (a bucket was submitted a minute later) and that the post-restart engine holds
nothing while the children remain `NEW` in the read model.

**Read from source, not exercised:** that `applySubmitted`/`applyFillObserved` early-return is the only
reason this degrades quietly rather than crashing. It did not crash, which is consistent, but the
mechanism was not isolated.

**Not established:** whether the same orphaning occurs on the single-BLP tier, and whether anything
downstream (position, risk, EOD) misprices the orphaned children. Neither was looked at.

## Related

- `issues/resolved/nats-jetstream-state-is-ephemeral-decide-deliberately.md` — the no-PVC decision that
  makes a wipe a normal event rather than a disaster. This issue is one of its consequences.
- `issues/open/a-nats-restart-silently-kills-every-eod-durable.md` — same broker event, different victim.
- `issues/open/algo-parent-retries-forever.md` if present, and the algo-engine recovery-logging work in
  `issues/resolved/` — this is the case that work did not reach.
