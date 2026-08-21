# Widening the tradable set from 44 to 533 OOM-killed the cluster

**Incident 2026-08-20**, self-inflicted and fully recovered. Recorded because the trigger is a
configuration anyone would now reach by ordinary use, not by anything exotic.

## What happened

Admitting all 533 reference-data instruments (correct — see
[[securities-need-admission-like-accounts]]) and then submitting one order against **each** of them
threw `java.lang.OutOfMemoryError: Java heap space` on the `clustered-service-0-0` thread of two
members within seconds of each other. Members were running `-Xmx1536m`, sized when the tradable set
was effectively one instrument and nominally 44.

Admission itself is cheap — a byte in a preallocated array. **The cost is the first order per
security**, which allocates that security's YU13 book grid. 533 grids did not fit.

## Why it presented as a cluster fault rather than a memory one

The service agent died; consensus did not. So:

- members stayed `1/1 Running` with a live HTTP endpoint
- `/health` answered, showing `applied` frozen and `engineApplied: -1` on the worst member
- m2 reached `ELECTION-PHASE state=LEADER_INIT` and **could not finish** — an elected leader cannot
  complete `joinLogAsLeader` with a dead service
- all three settled as FOLLOWER with no leader for 3+ minutes
- all three gateways went `0/1`, `/ready` 503, and edge-proxy returned **502 HTML** to every client

`aeron-cluster-live-ops` names this shape exactly ("a full restart comes back leaderless with the
elected leader spinning in `joinLogAsLeader` waiting on its wedged service"). Reading that skill is
what turned an unexplained freeze into a five-minute diagnosis; the OOM line is only in the member
log, and every surface above it reported a cluster problem.

**It is a poison pill.** The trigger is a committed log event, so a plain restart re-OOMs on replay.
The heap must be raised *before* the members come back, or the fresh epoch must drop the state.

## Fix

`statefulset-emptydir.yaml`: `-Xms512m -Xmx1536m` → `-Xms1g -Xmx3g`, container memory `4Gi` → `8Gi`.
Member nodes have ~13.6Gi allocatable and run one member each, so this is not tight. Steady-state
use after recovery with all 533 admitted: **358–375Mi**, so the ceiling is reached by *touching*
instruments, not by holding them.

## What this costs to know

**Heap has to scale with the tradable universe, and nothing in the system says so.** There is no
metric for "book grids allocated", no limit on distinct securities touched per epoch, and the
symbol-table cap (`MAX_SECURITIES` 1024) is nearly 2× the catalog — so the config that protects
memory is a JVM flag with no relationship to the config that decides how many instruments exist.

Open, and worth a decision rather than a patch:

1. **Nothing bounds the number of book grids.** 1024 securities is reachable within the symbol cap
   and would need proportionally more heap. The cap and the heap should be reasoned about together.
2. **A session that samples the whole catalog is now possible** and was not before, so this is
   newly reachable by the demo rather than only by a sweep like mine.
3. **The OOM is invisible above the member log.** `/health` kept answering while the service was
   dead. A member whose service thread has died should say so — `engineApplied: -1` is the tell but
   it reads as "cold start", which is exactly what it looks like during a normal one.

## Second defect, found during recovery

`scripts/yu15/bring-up-gke.sh`'s proof step used `curl -sf` with no `|| die` on two calls, so a
refused order (HTTP 422) exited the script under `set -e` with curl's code 22 **after printing
nothing**. The operator saw `proving the path end to end` and a silent non-zero exit.

It also hardcoded a `200.00` limit. Under ADR-051 a `/seed` sets the mark only while no trade has
printed, and the collar band anchors on the security's **first limit into the book** — engine state,
not the mark. Measured during recovery: IBM accepted `150.00` and refused 180/190/192.40/195/200/210
on a fully healthy rig. The proof could not pass however well the cluster worked.

Both fixed: the proof now tries candidate securities until one accepts, prints the refusal reason
for each that does not, and fails loudly with those reasons if none do.

Related: [[securities-need-admission-like-accounts]], [[engine-roll-needs-snapshot-barrier]]
