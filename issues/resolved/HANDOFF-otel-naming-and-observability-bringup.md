# Handoff: name the OTel proofs for the state that owns them, and bring observability up with the rig

**Written:** 2026-08-03 by the OSFF session. **Untracked by design** (session handoff, not a project
record). Two small tasks, one correction each to the instruction as given.

---

## Report back when done

yaakov asked that you **message the originating session when this is finished**, not just reply to
him:

```
mcp__ccd_session_mgmt__send_message
session_id: local_872b3441-8939-499b-922a-02bba9c3e9f3
```

If that id does not resolve, `mcp__ccd_session_mgmt__list_sessions` and match on content — the
session that ran the 19/19 proof suite, the upstream merge across all 16 branches, and today's YU14
clustered-service repair. It could not set its own title (the tool refuses to rename the caller), so
match on content rather than a title string.

---

## Task 1 — rename `yu15-otel-*` to `yu13-otel-*`

**Why.** `scripts/proofs/` names a proof for **the state whose feature it proves**, not the branch
the file sits on — `yu13-clordid-suppression`, `yu13-readmodel-effect-end`, `yu05-recon`,
`yu06-quality-gate`. Every piece of the tracing lives in the **YU13** layer: `OrderTrace.java`,
`SpanSink.java`, the gateway-side spans in `ClusterGatewayMain.java`, and the Prometheus/Grafana
configmaps. The two proofs are named `yu15-otel-*` because they were written during the brief-05
OTel work on 2026-07-29, when YU15 was the working state — the name records the session, not the
layer.

It matters because the name is the first place someone looks. A reader who wants to find tracing
reads `yu15-otel-trace-join.sh`, goes to the YU15 layer, and finds nothing — it is all one layer
down. That is the same wrong-layer confusion that cost a full repair earlier today, delivered by a
filename instead of a stale file.

### ⚠️ Correction to the instruction: this is a RENAME, not a move

yaakov said "the changes will be done on YU13, propagated to YU14 and YU15." **Measured, that is not
where these files are:**

| branch | otel proofs | `start-cluster-kind.sh` | `start-observability-kind.sh` |
|---|---:|---:|---:|
| YU13-limit-order-book | **0** | 1 (`scripts/yu12/`) | **0** |
| YU14-listed-equity-options | **0** | 2 | **0** |
| YU15-eod-risk-extract | 2 | 3 | 1 |
| main | 2 | 3 | 1 |

The proofs do not exist on YU13 at all, and neither does the observability script. Proof scripts live
on the **rig branch** (YU15) and are merely *named* for the feature's state — which is exactly why
this is a naming fix and not a relocation. **Do not move files onto YU13.** yaakov's own constraint
says so too: *"It should still be able to run on YU15."*

### Scope

`git mv` on **YU15 and main** (the two carriers):

```
scripts/proofs/yu15-otel-trace-join.sh            -> yu13-otel-trace-join.sh
scripts/proofs/yu15-otel-reject-trace-log-join.sh -> yu13-otel-reject-trace-log-join.sh
```

Then update every reference. Known ones — **grep for the rest rather than trusting this list**:

- `scripts/yu15/run-proofs.sh` — the `PROOFS=(…)` array, two entries.
- `specs/YU15-eod-risk-extract/quickstart.md` — step 5 (just edited in `e7c50c91`/`a8e639d2`; see
  Task 2, which removes one of those lines anyway).
- `docs/engineering/observability-and-replay.md` and `testing-strategy.md` / `test-coverage.md` if
  they name the scripts.
- `specs/YU13-limit-order-book/generation/implementation-status.md` — its verified-evidence block
  names both proofs.

**Verify by running them**, not by grepping: `bash scripts/yu15/run-proofs.sh otel` should select and
pass both under the new names. A rename that leaves a stale reference fails as "proof not found",
which reads like a missing capability.

---

## Task 2 — make the two trace proofs fail fast when the stack is down

**The problem:** the trace pipeline dies *silently*. The observability stack has shipped in the
manifests since state `007`, but `start-cluster-kind.sh` deploys only the trading tier, so on kind
the two halves land in different clusters and the collector endpoint resolves to nothing. Orders
book normally, spans go nowhere, and the only symptom is an empty Tempo. There is no error to grep
for.

**What to build:** a precondition check at the top of each `yu13-otel-*` proof — if Tempo is not
reachable, fail immediately naming the fix.

```
[fail] Tempo unreachable at localhost:3200 — the observability stack is not up.
[hint] bash scripts/yu15/start-observability-kind.sh
```

Check whether they already do this. `run-proofs.sh` verifies the Tempo forward before every proof,
so the suite path may already be covered and **standalone invocation is the gap** — which is exactly
how yaakov is running them now, one at a time from an IDE.

### ⚠️ This replaced an earlier plan — do not implement the earlier one

An earlier draft had `start-cluster-kind.sh` invoke `start-observability-kind.sh` so the stack came
up with every rig. **That was reconsidered and rejected.** If you find that instruction elsewhere,
this supersedes it. The reasons, so nobody re-proposes it:

- **Kind CPU contention is a documented root cause on this rig, not a hypothetical.** A contended
  box makes the Aeron cluster stop applying while still reporting 3/3 Running, and it is the
  recorded source of throughput spread and proof flakiness (`CLUSTER_IDLE_SLEEP_MS` exists solely
  because of it). The stack is six more workloads — collector, Tempo, Loki, Grafana, Prometheus,
  plus a promtail daemonset.
- **17 of 19 proofs would pay that tax so 2 could run**, right when the proofs are being run
  individually and a flake costs a debugging session rather than a retry.
- **It would fight an existing proof.** `yu13-stp-and-replace` deliberately scales the stack to zero
  to get a quiet box for consensus; an auto-start means the bring-up and that proof undo each other.
- **The actual defect was discoverability, and it is already fixed.** The prerequisite lived only in
  `run-proofs.sh` header comments where nobody working from the quickstart could find it;
  `e7c50c91` / `a8e639d2` put it in step 5 next to the proofs it gates. Auto-starting solves the same
  problem a second time at real runtime cost.

The fail-fast check is strictly better: zero CPU cost, and it survives being run from a different
rig, a fresh terminal, or after something scaled the stack down — none of which an auto-start covers.

### Scope

Both proofs live on **YU15 and main** only (see the table above — YU13 and YU14 carry neither).
Keep the quickstart's step 5 as it stands; it is now correct and this change makes it belt-and-braces
rather than load-bearing.

### One judgement call

Decide whether the check probes **Tempo** (where the proof reads traces from) or **the collector**
(where spans are written to). Tempo is the right answer if the proof queries it, since that is the
dependency the assertion actually has — but confirm against what each proof does rather than
assuming they are the same. A precondition check that guards the wrong service is its own kind of
vacuous pass.

---

## Ground rules (from `~/dev/lmax/CLAUDE.md`)

- **Never `git push`.** yaakov pushes. Sole allowlisted exception: `git push origin YU15-eod-risk-extract*`.
- **Never add a `Co-Authored-By: Claude` trailer** or "Generated with Claude Code" to any commit.
- Worktrees are shared by several lanes. **Stage explicit paths, never `git add -A`** — two worktrees
  currently carry parked diffs (`traderX-YU12` presentation/YU11-slides.html, `traderX-blp-ha-demo`
  CLAUDE.md) that must stay untouched.
- The rig is **deleted right now** — yaakov is rebuilding it by hand from the quickstart. Coordinate
  before assuming a cluster exists; `kind get clusters` first.
