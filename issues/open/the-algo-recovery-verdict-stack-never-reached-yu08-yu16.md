# The algo-engine recovery-verdict stack never reached YU08…YU16

> **Measured 2026-08-24. Re-derive before acting** — md5s and line counts rot the moment anyone
> commits to any of these branches, and a carry sized off a stale table is exactly the failure this
> file exists to stop.

Found while closing `issues/resolved/undetermined-swallows-the-torn-log-clause.md` on the tip. Not
created by that work: a **pre-existing** gap it merely tripped over, and it is filed separately
because the carry is far too big to fold into a one-clause fix.

## The gap

`AlgoEventStore.java` has exactly one carrier — the `specs/YU08-execution-algo-engine` layer — and
nothing shadows it. That is precisely the case where a hand-carry is both **necessary and
sufficient**, and it never happened. Three commits landed on `YU17-otc-rates` and reached nothing:

| commit | subject |
|---|---|
| `529c20cc` | algo-engine: distinguish an empty stream from a lost log on recovery |
| `65993103` | algo-engine: STATE LOST says unrecoverable, because "gone" was measured false |
| `16cdeec9` | algo-engine: a torn log stops replaying clean, and names the parents it lost |

`git merge-base --is-ancestor <commit> <branch>` — every commit against every branch, with the YU17
arm run as the **control**, because a check that answers "no" everywhere is the failure mode:

```
                 YU08 YU09 YU10 YU11 YU12 YU13 YU14 YU15 YU16   YU17
529c20cc           N    N    N    N    N    N    N    N    N      Y
65993103           N    N    N    N    N    N    N    N    N      Y
16cdeec9           N    N    N    N    N    N    N    N    N      Y
```

The file state corroborates it — YU08…YU16 hold a byte-identical pre-recovery-work copy:

```
YU08 / YU12 / YU16   1060f43a48c0923c58953b73a94d48a7   265 lines
YU17 (before the fix) b671e6382e9428a5baa74db67e648f3e   414 lines
main                  4ee7382d829f7caaf2090f12f6b6f854   184 lines
```

## What those nine branches actually do on recovery

One line, and it is the line the whole stack exists to replace:

```java
log.info("replayed {} algo-engine events from {}", replayed, STREAM_NAME);
```

No `Verdict` enum at all. So on YU08…YU16 an empty stream, a **lost log**, a consumer that replayed
nothing, an uninspectable broker and a **torn log** all render the same quiet INFO line — the exact
conflation `529c20cc` was written to end. `replayed 0` still means both "first boot" and "every
parent order is unrecoverable", and it is still logged at INFO.

## Size of the carry — now FOUR commits

The tip has since added a fourth, `2b40524d` (the torn-log clause surviving an uninspectable
broker). It builds directly on the other three: **a partial carry does not compile**, so this is not
merely incomplete-if-skipped, it is broken-if-half-done. Four commits × nine branches, each gated on
that branch's composed suite as the forcing function (`.claude/skills/propagate-spec-fix`).

`main` is **not** a propagation target — it carries a third, independently different copy
(`4ee7382d…`, 184 lines) and is reached by PR, not by carry.

## Open question, separable: none of YU08…YU16 carry `issues/` for this surface

Checked on YU08, YU12 and YU16: neither the resolved parent
(`a-torn-algo-log-replays-clean-and-orphans-live-children.md`) nor either issue lifted out of it
exists on any of them. So even a branch that received the code would have no record of why. Whether
`issues/` should be synced wholesale across the family, or deliberately lives only on the tip, has
never been decided — it is a bigger question than this file and wants its own answer, not a silent
assumption made during some future carry.

## A smaller, unrelated carry noted in passing

`BlpRiskStateTest.java`'s operative layer is **YU14**, carried by YU14–YU17. The two pins added by
`issues/resolved/orphaned-children-hold-risk-capacity-nobody-releases.md` therefore have their own
carry — one commit × three branches, and it compiles standalone. Genuinely different in size from
the four-by-nine above; do not bundle the decisions.

## Related

- `.claude/skills/propagate-spec-fix` — the lineage rule, and the reason its worktree table must be
  regenerated rather than believed.
- `issues/HANDOFF-issue-spec-layer-propagation-gaps.md` — the running incident list this belongs on.
