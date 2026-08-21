# `UNDETERMINED` swallows the torn-log clause

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
