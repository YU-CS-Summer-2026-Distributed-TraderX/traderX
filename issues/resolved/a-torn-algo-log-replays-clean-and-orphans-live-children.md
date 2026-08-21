# A torn algo log replays "clean" and leaves live children with no parent

**RESOLVED 2026-08-21** on `YU17-otc-rates` by `16cdeec9`, and the fix was **watched on the cluster
kind rig** — both legs of the A/B, on an image built from that commit. What it does NOT do is stop
the orphaning: it stops the orphaning being SILENT. See *Resolution* at the bottom.

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

---

## Resolution

`16cdeec9` — `algo-engine: a torn log stops replaying clean, and names the parents it lost`.

### What changed

The signal the issue identified was being discarded three times, not twice: `applySubmitted`,
`applyFillObserved` (via `bucket()`) and `applyCompleted` each did their own `orders.get(...)` and
returned early. All three now route through one `AlgoOrderState.parent(String)`, which records the
id in a set of orphaned parents — one place, so a fourth event type added later cannot forget to.

`AlgoEventStore` snapshots that set on each side of the replay drain and classifies on the
**difference**, so an orphaned apply from live traffic cannot leak into a recovery verdict. Distinct
parent ids, not an event count: what an operator acts on is how many parents are unowned.

New verdict `REPLAYED_WITH_ORPHANS`, alarming, therefore WARN. `REPLAYED` with zero orphans is
byte-identical to what it was and still logs at INFO — a test pins that exact string, because a
clause that fired on healthy recoveries would spend the silence that makes the loud verdicts mean
anything.

The message says the children **may still be** live in the book, not that they are. This engine can
observe that it never rebuilt those parents; it cannot see the book. (The rig run below confirms
they were, but that was read from the trade-processor read model — a vantage the engine does not
have. The sibling branch on this surface was reworded once already for exactly this class of
over-claim, `65993103`.)

### Measured on the rig — the A/B, 2026-08-21

Context `kind-traderx-yu12-cluster`, ns `traderx`, image `traderx/execution-algo-engine:yu17-torn`
built from `16cdeec9`. A TWAP of 600 AAPL, `durationSeconds=600`, `bucketSeconds=30`, account 22214.

**Leg A — intact log.** NATS wiped with nothing in flight, TWAP submitted, allowed to slice, engine
pod deleted:

```
INFO  replayed 9 of 9 algo-engine events from TRADERX_ALGO_ENGINE (last sequence 9)
```

Unchanged, quiet, INFO. `GET /algo/orders` returned the parent, RUNNING, 4 buckets submitted.

**Leg B — torn log.** NATS wiped with that parent in flight. The engine survived, logged
`STATE LOST … UNRECOVERABLE` (`applied 9 events off it`), and kept slicing — 7 buckets submitted by
the time the tail had been rebuilt. The stream then reported:

```
TRADERX_ALGO_ENGINE: messages=6 first_seq=1 last_seq=6
```

— arithmetically a complete six-message log, which is the whole reason no broker-side check can
see this. Engine pod deleted:

```
WARN  replayed 8 of 8 algo-engine events from TRADERX_ALGO_ENGINE (last sequence 8), but 1 parent
      order(s) named by those events were never reconstructed: no ParentOrderCreated for them
      appeared in this replay, so the log is TORN. Child orders those parents submitted may still
      be live in the book, and this engine now holds no parent that will cancel, resize or finish
      them: [a1c44799-df13-480b-9d1d-78befdaf17f3]
```

`GET /algo/orders` → `[]`, `GET /algo/orders/<id>` → 404, pod Running 1/1 RESTARTS 0 — the verdict
got louder, nothing started crashing. The read model corroborated the claim the engine hedged:
8 AAPL children of 30, all `NEW`, **240 shares** owned by nobody.

Separately, the rig was still holding the torn log from the run this issue was filed on, and the
new build caught it cold on its first boot: `replayed 6 of 6 … but 1 parent order(s) …`, naming
`e29ca74c-…`, whose four children of 120 (480 shares) were still `NEW` in the read model — the
worked example in this issue, re-derived and confirmed.

### Tests

Three new tests in `AlgoEventStoreReplayTest`; `execution-algo-engine` 43 → 46, suite 564 → 567 over
6 modules, `engine-tests.sh hosted` / `service-tests.sh` / `assert-suites-executed.sh` all rc=0.
Each new branch was detonated against the module in turn:

| defect injected | failed | of |
|---|---|---|
| orphan never recorded (`parent()` does not add) | the torn test only | 1 of 46 |
| verdict never reports the tear (branch unreachable) | torn + verdict-distinctness | 2 of 46 |
| orphan clause always fires (healthy path made noisy) | the three quiet-path tests | 3 of 46 |
| a bad bucket index counted as a tear | the precision test only | 1 of 46 |

The rest of the module stayed green with each defect in, so nothing pre-existing covered any of it.

### Still not established

- **The single-BLP tier (state-014) was not touched.** Whether the same orphaning occurs there is
  exactly as open as when this was filed.
- **Nothing downstream was looked at** — whether position, risk or the EOD extract misprice the
  orphaned children is unexamined.
- **Recovery, not repair.** The engine still cannot cancel or adopt an orphaned child; it can only
  now say the child exists. Whether it should do more is a separate decision.
- **`UNDETERMINED` swallows the orphan clause.** If the broker cannot be inspected AND the replay
  tore, the verdict reports the inspection failure and drops the orphan count. Reachable in
  principle, not seen; `UNDETERMINED` is already alarming, so the operator is already loud.
