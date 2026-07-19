# ISSUE: empty-member rejoin wedges after term-history accumulation (Aeron 1.51)

Severity: BLOCKS joint-plan Phase 5 (kill campaigns mint leadership terms rapidly) and any
long-lived emptyDir deployment. Found during the c3-pool migration (m1 never became ready;
readiness gate correctly froze the roll; quorum + data stayed safe).

## Symptom

A wiped/new member loops forever in election with, in stdout:

    ArchiveException: requested replay start position=0 is less than recording start
    position=<N> for recording 0

thousands of times (`grep -c newLogReplay` on the pod log), `applied:-1` in /health, pod
0/1 Ready. Live members are unaffected (quorum keeps serving).

## Mechanism (ground truth from ClusterTool recording-log + ArchiveTool describe)

- Each election bumps the leadership term; after a day of kill testing the cluster was at
  term 46, and the RecordingLog carried terms 0..44 as DEGENERATE entries
  (termBaseLogPosition=0, logPosition=0) — placeholder bookkeeping accumulated across
  wipe/rejoin generations (RecordingLog gap-filling).
- A fresh joiner replicates the recent term's log range only (its local recording starts at
  that term's base, e.g. 36,012,928), then the backward walk over the degenerate entries
  finds "0..0" ranges, declares replication complete, and computes a recovery plan with
  logPosition=0 (its fresh RecordingLog has no snapshot entries — 1.51 static-member
  elections do NOT replicate snapshots to joiners).
- followerReplay then asks its own archive for position 0 -> ArchiveException -> the
  consensus module restarts the election -> infinite loop. Deterministic (reproduced on a
  clean second attempt; same start position both times).
- Leader-side snapshots don't help: snapshot entries exist in the LEADER's RecordingLog but
  are never shipped to the joiner.

## Why rejoins worked earlier

Early in an epoch the term history is shallow and intact: replication ships the log from
position 0 and replay-from-0 succeeds. The failure emerges once degenerate term entries
accumulate ahead of the earliest retained/advertised range — i.e., the cluster ages into it.

## Immediate remediation (applied)

Full clean reset (scale 0 -> 3): new epoch, clean RecordingLog. State loss acceptable here
(proof-client traffic). The readiness catch-up gate made the failed roll safe: it blocked
progression, so quorum and state were never at risk.

## Durable-fix candidates (must land before Phase 5 kill campaigns)

1. **Aeron upgrade**: check 1.52+ changelogs for election/RecordingLog fixes around
   replication bookkeeping and snapshot-aware joins; upgrade is cheap to trial on kind.
2. **Snapshot-aware join via ClusterBackup-style seeding**: provision a joiner's cluster dir
   from the leader's latest snapshot pair + tail (scripted: ArchiveTool replicate snapshot
   recordings + RecordingLog.appendSnapshot) before first boot. Turns rejoin into
   snapshot+tail like a normal restart.
3. **Periodic epoch hygiene**: scheduled clean resets are NOT acceptable once state matters;
   log purge (`ClusterTool purge`?) may compact term history — verify whether it rewrites
   degenerate entries or only trims segments.
4. Regardless: add a rejoin canary to the acceptance harness (wipe one member every N kills
   and require convergence) so this class is caught the moment it re-emerges.

## Triage additions (also folded into the aeron-cluster-live-ops skill)

- `applied:-1` + 0/1 Ready + exception loop in pod stdout => THIS issue (not the service
  wedge, not session-limit).
- Ground-truth commands (in-image):
  `java -cp "/opt/app/classes:/opt/app/lib/*" io.aeron.cluster.ClusterTool /data/cluster recording-log`
  `java -cp "/opt/app/classes:/opt/app/lib/*" io.aeron.archive.ArchiveTool /data/archive describe`
  Degenerate (0,0) TERM entries + a local log recording starting mid-stream = confirmed.
