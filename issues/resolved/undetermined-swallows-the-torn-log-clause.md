# `UNDETERMINED` swallows the torn-log clause

**RESOLVED 2026-08-24** on `YU17-otc-rates` by `2b40524d`. Proven by unit test and **not** on a rig
— deliberately, and the reason is in *Resolution* at the bottom. **Not propagated**: see
`issues/open/the-algo-recovery-verdict-stack-never-reached-yu08-yu16.md`, which this work uncovered.

> A record, not a rig you can query. Re-derive against the tree in front of you.

Small, narrow, and filed only because a known gap recorded inside a *resolved* issue is not filed —
nobody reads `issues/resolved/` looking for open work. Lifted out of
`issues/resolved/a-torn-algo-log-replays-clean-and-orphans-live-children.md`, where the lane that
built the fix recorded it honestly rather than quietly leaving it.

## The gap

`AlgoEventStore`'s recovery classifier returns exactly one verdict. When the broker cannot be
inspected it returns `UNDETERMINED`, and that branch is chosen **before** the orphan count is
consulted. So if the broker is uninspectable *and* the replay tore, the operator is told the
inspection failed and is **not** told that parent orders went unreconstructed.

## Why it is minor, stated honestly

`UNDETERMINED` is already an alarming verdict — the operator is loud either way, so nothing becomes
silent. The combination has never been observed; it needs a broker that answers enough to stream a
replay but not enough to report stream state, at the same moment as a torn log.

## Why it is still worth a line

The two facts are not interchangeable. `UNDETERMINED` says *"I could not confirm this replay was
complete."* The orphan clause says *"specific named parents were lost and their child orders may be
resting in the book right now."* The second is the actionable one, and it is the one dropped. An
operator pointed at a broker-inspection problem will go and look at the broker, not at the book.

## What fixing it looks like

The orphan set is known independently of whether the broker could be inspected — it comes from the
replay itself, not from stream state. So the clause can be appended to `UNDETERMINED` rather than
replaced by it. Likely a small change at the point where the verdict is selected.

**Do not let one verdict per recovery force the choice.** If the two facts genuinely need to co-exist,
say both in one message rather than ranking them — that ranking is the defect.

**Break it first:** a test must fail if a torn replay under an uninspectable broker omits the orphan
clause, and must not fire when either condition is absent on its own.

## Lineage

`AlgoEventStore.java` is carried by ONE layer, `specs/YU08-execution-algo-engine`. Confirm against
`generated/` after editing anyway — an inert fix to a shadowed layer looks exactly like a fix that
did not work.

---

## Resolution

`2b40524d` — `algo-engine: an uninspectable broker stops swallowing the torn-log clause`.

### What changed

The torn-log sentence was written inline inside the `REPLAYED_WITH_ORPHANS` branch, which is the
whole reason only that branch could say it. It now lives in one helper —
`tornClause(connector, orphanedParents)`, empty string when the replay was intact — and **both**
verdicts that can carry the fact append it: `UNDETERMINED` and `REPLAYED_WITH_ORPHANS`.

The verdict for the combination stays `UNDETERMINED`. No new enum value, and no ranking: the two
facts are about different things — the broker, and this replay — and the orphan set is known
independently of whether the broker could be asked anything, because it comes from the applier.
Which fact happens to be selected must not decide whether the other gets said. `UNDETERMINED` was
already `alarming()`, so it already logged WARN; nothing about routing changed.

`REPLAYED` is byte-identical to what it was and still logs at INFO — an exact-string test pins it,
because a clause that leaked into a healthy recovery would spend the silence that makes the loud
verdicts mean anything. The `REPLAYED_WITH_ORPHANS` line is byte-identical too, pinned the same way:
sharing a clause between two call sites is exactly how the line this issue's parent quotes from the
rig would have drifted a comma without anyone noticing.

### Tests

Two new tests in `AlgoEventStoreReplayTest`; `execution-algo-engine` 46 → 48. Both directions were
detonated against the module, which is the point — a test that passes on both a torn and an intact
log under an uninspectable broker is asserting nothing:

| defect injected | failed | of |
|---|---|---|
| the clause is dropped under `UNDETERMINED` (the state this issue describes) | `anUninspectableBrokerStillNamesTheParentsThisReplayLost` only | 1 of 12 |
| the clause fires unconditionally (`isEmpty` guard deleted) | `theTornClauseNeedsATearAndFiresOnNeitherConditionAlone` only | 1 of 12 |

Every pre-existing test in the class stayed green under both, so nothing already covered either
direction.

### Still not established

- **Not exercised on a rig, deliberately.** The combination needs a broker that answers enough to
  stream a replay but not enough to report stream state, at the same moment as a torn log. It has
  never been observed and is not worth manufacturing on a shared rig; the classifier is pure and
  package-visible precisely so this can be proven without one.
- **The orphaning itself is untouched**, as it was in the parent issue. This engine still cannot
  see the book, so the wording stays "may still be live".
