# Six issues were lifted to the tip without triage, and their status lines are unverified

> A record, not a rig you can query. Re-derive from the tree before acting.

**Filed 2026-08-24 by the `issues/` reconciliation lane**, as the deliberate remainder of that task.
Ratified by the coordinator: lift first, triage separately.

## What was done, and what was knowingly not done

Six issue documents existed on exactly one branch each and were **absent from the tip**, so the
project-wide `issues/` record (see `README.md`) could not be established without destroying them —
a sync from a source that is not a superset is a deletion. They were lifted to the tip **verbatim,
with their status lines untouched and unverified**:

| document | was only on | dated |
|---|---|---|
| `HANDOFF-issue-yu12-bridge-at-least-once.md` | YU12 | 2026-07-20 |
| `HANDOFF-issue-yu12-failover-measurement.md` | YU12 | 2026-07-20 |
| `HANDOFF-issue-yu12-gateway-sessionaffinity-split.md` | YU12 | 2026-07-20 |
| `HANDOFF-issue-yu12-services-ui-rewire.md` | YU12 | 2026-07-20 |
| `HANDOFF-issue-yu12-sustained-throughput.md` | YU12 | 2026-07-20 |
| `YU15-s5-gke-fifo-correlation-offset.md` | YU15 | 2026-08-16 |

**Nobody has checked whether any of them is still open.** They are five weeks old and several read
stale on their face. The concrete example: `HANDOFF-issue-yu12-gateway-sessionaffinity-split.md`
says *"MANIFESTS DONE 2026-07-20 … pending `kubectl apply` on GKE + the 3-pod bench re-run"* — the
work may well have landed since, and the document would not know. The five YU12 documents also
describe themselves as *"Untracked working note"*, which is what they were before they were
committed; their status lines were never written to be authoritative.

## Why lifted rather than triaged

Deliberate, and the reasoning should survive: these documents were invisible on sixteen of
seventeen branches. **A stale-but-visible issue is strictly better than an invisible one.** Lifting
is mechanical; triage is judgement. Bundling them would have made a thirty-minute mechanical sweep
hostage to six independent verdicts, which is how the sweep does not happen at all.

## The work

Run `verify-an-issue-is-still-open` (`~/dev/lmax/.claude/skills/`) against each of the six. Its first
step — run the issue's own repro verbatim — is the whole job for most of them. Expect a meaningful
share to be already fixed: that skill records a session where four of five worked issues had already
been closed and only the status line lagged.

Then either close them into `resolved/` with the evidence, or leave them open with a re-verified
date. Either outcome is an improvement on the current state, which is six documents asserting things
about July that nothing has checked.

**Not urgent, and not blocking.** The record is now consistent and complete; this issue exists so
that its one known soft spot is written down rather than discovered again.
