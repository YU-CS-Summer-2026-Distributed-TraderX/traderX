# RI38: the members' egress and failover gaps after RI37

Opened 2026-11-10 (coordinator, three items on the members' egress and failover path). Branch `ri38-egress-failover-gaps`
from `traderX-risk-integration` `922c327e`. Status: item 1 fixed (FR-DRS-49: a unit test and kind, each with a failing control).
Item 2 counted, not yet prevented (FR-BSP-03, unit test; prevention is T-BSP07). Item 3 is diagnosed from evidence, with one contributing defect
fixed. The second election's cause is open. Nothing has run on GKE.

## 1. The FR-DRS-33 "ack gap": not covered by FR-DRS-47 + FR-DRS-37; fixed (FR-DRS-49)

§7.15 item 4 (2026-11-09): a stalled volume left the leader's publishes unacked, a full 32 Gi archive killed it, and
1.9M sides were lost. The label "FR-DRS-33 ack gap" is a misnomer: FR-DRS-33 is the reader's cumulative ack. The loss
is the bridges'.

From the code at `922c327e`: the successor republishes, at its catch-up, what it holds after the stream's last stored
message. While following, a lane released records once they were older than the last 65,536 it read (KEEP). FR-DRS-47's
1M-record ring only holds what the promoted lane applies itself. So a dead leader's unstored tail was covered up to
65,536 records per lane. That is about 0.5–1 s at 500K orders/s on 8 partitions. Beyond that the catch-up counts a
gap and the records are lost. The 11-09 tail was about 240K sides per lane. The same bound applied within one leader's
life: a reconnect's catch-up (FR-DRS-38) found only the last 65,536 the leader had published.

Failing test first: `ReceiptLanesIT#aDeadLeadersUnstoredTailLongerThanTheKeepIsRepublishedByItsSuccessor`.
1. NATS freezes.
2. The leader publishes 100,000 sides and order events into the frozen socket.
3. NATS is killed, so none of them is stored.
4. The leader dies and the follower is promoted.

On `922c327e` the successor stored 65,636 of 100,100 sides: 34,464 lost, gap 1.

Fix (FR-DRS-49): no lane releases a record the stream has not confirmed stored.
- The lane asks for the stream's last stored message on its subject asynchronously: `STREAM.MSG.GET`, last by
  subject, at most every 250 ms, and only when the keep would release past the last confirmed position.
- It bisects its ring for that position.
- A current stream confirms well within the keep, so steady-state retention is unchanged.

The same test passes: 1..100,100 each once, in order, gaps 0.

kind (`kind-bridge-failover-at-rate.sh KILL_AFTER=100 FREEZE_SECS=140 SECS=420 RATE=400`; evidence in the shared notes
`ri38-kind-20261110/`). The leader was killed 100 s into a 141 s NATS freeze, with about 120K sides applied since the
freeze and none stored. Only `ReceiptLanes` differs between the two arms' images.

| | this rule (`:ri38`) | the parent's ReceiptLanes (`:ri37`) |
|---|---|---|
| unstored tail at the kill | 118,052 sides | 121,886 sides |
| store sides (G3h) | 387,332 of 387,332 | 407,430 of 465,088: 57,658 lost |
| order events | 774,665 of 774,664 (5 ambiguous orders) | 752,959 of 930,176 |
| promoted leader republished | 108,828 sides, 241,208 events | exactly 65,536 of each, `gaps` 1 per bridge |
| `reconnects` on the promoted leader | 0 | (no field) |

The control's first attempt (`control-ri37-attempt1-old-criterion/`) lost 61,319 sides, but the script refused it as
"lost nothing". Its control check knew only FR-DRS-47's `queueDropped`, and this loss is a gap. The script now accepts
either.

Order-matcher suite: 962 of 963 tests pass, with the allocation gates. The one failure is
`SnapshotBarrierPerformanceTest`, a 50 ms timing gate. It read 62, 124 and 571 ms in three runs, with the machine's
load average at 15–39 (other lanes' builds and this lane's kind rig). It does not exercise the code changed here. It
was not rerun on a quiet machine.

## 2. Uncounted egress ack drops: counted (FR-BSP-03); production can still drop

`offerEgress` and `flushCoalesced` try 20 times, then drop the record (or a whole coalesced message), and count
nothing. The leader now counts them:
- `/health` `egress.dropped`: records a live session refused on every attempt.
- `egress.closing`: records for a session that was already closing.
- `/metrics` `traderx_cluster_egress_dropped` and `traderx_cluster_egress_closing`.
- An ALERT on the first drop and every 10,000th.

Test: `EgressDropCountTest`.

Production's `--throughput-members` render turns on `CLUSTER_EGRESS_COALESCE`. That makes 64-fold fewer offers, but
the coalesced flush has the same 20-attempt bound and drops a whole message. RI35's 187 FIX drops were on coalesced
members. So production can still drop; it is now visible. Preventing the drop is T-BSP07: retry until the session
closes, or a term large enough for a burst.

## 3. Run D's stall and second election (from evidence; no rig run)

Run D's render and E3's (`ri37-gke-recipe-20261110/e-1.template.yaml`) are identical except for the member image and
the dead-man deadline. Every difference between the two runs is therefore runtime state.

**Correction.** E3 had a second election too.
- 1:48:03.992 pm ET: member 1 became leader.
- 1:48:08.882 pm: member 2 began an election (`member-2.log`).
- 1:48:09.741 pm: member 1 won it again.

E3 was first recorded as one election because `failover.sh` stops at the first new leader. Run E's kill had no second
election, but run E traded almost nothing: 93% of its orders hit `POSITION_LIMIT`. So the second election recurs at
full trading, whichever bridge code runs. A healthy NATS does not prevent it: E3's catch-ups took 201–221 ms.

**The stall (run D, 15–45 s stream reads). Most likely cause: the streams were at `max_bytes`, inferred from observed
counts.**
- Run D's `lag.tsv` stream count fell from 75,224,606 to 75,179,098 between 10:20 and 10:22 am. Meanwhile about 144M
  records were stored, so the stream was deleting as fast as it was writing.
- `max_age` is 2 h on a rig about 1 h old, so it cannot explain this. The limit is `max_bytes`: about 136 GB per
  partition.
- §7.15 records the same discard churn saturating this volume, with each partition's JetStream API stalled 8–30 s.
- E3 ran on fresh streams, with about 6.6M messages.
- The streams are R1 with no direct get. So `getLastMessage` is answered only by the server that holds the partition,
  and `getStreamNames` only by the meta leader.
- Old-code timing fits: a catch-up made up to 10 attempts × (2 s + 2 s + 0.5 s), about 45 s.

**An amplifier, fixed here.** The stall check held a following lane's quiet timer at its connect time. At a promotion,
the first publish therefore read as 5 s without a receipt, and the lane reconnected and caught up a second time.
- E3 logged this on all 16 of member 1's lanes.
- In run D each extra catch-up was another stalled stream read.
- Now silence counts only while publishes wait for receipts. A catch-up's stream read does not count as silence either.
- `bridges.*.reconnects` is on `/health`.
- Test: `ReceiptLanesIT#aPromotionDoesNotReadTheTimeSpentFollowingAsSilence`.

**The second election: open.**
- The render's timeouts are a 50 ms heartbeat, a 200 ms heartbeat timeout and a 100 ms election timeout. With one
  member dead, quorum is both survivors, so a pause of about 200 ms on either one opens an election.
- In E3 member 1's election watcher (1 ms poll) logged nothing between member 2's INIT and member 1's NOMINATE, 724 ms
  later. That suggests member 1 was not scheduled (inferred).
- The only line in between: the leader-only kdb capture tap reached its 256 MB cap (3,027,465 rows, about 5 s of the
  new leader's output, written to `/data`, the Archive's volume).
- Run E's tap also reached its cap without an election, so the tap is a candidate, not a cause.
- Other candidates, none measured:
  - CPU quota throttling: a 15-CPU limit with spinning agents;
  - memory reclaim;
  - JIT of leader-only code;
  - a stall on member 2 instead.

No mechanism links the stall to the election. A stalled lane does not stall Aeron, and E3 had the election without the
stall. The link runs the other way only: the second election starts every lane's catch-up again.

**Reproduction plan, cheapest first.**
1. Done here: the unit test for the reconnect.
2. Local three-server NATS: streams at a small `max_bytes` under load with a backlog reader. Time `STREAM.NAMES` and
   `MSG.GET` against the same streams under their limit.
3. kind: a full-trading leader kill at 200/100 ms, with and without `KDB_TAP_DIR`. Sample the members' cgroup
   `cpu.stat`, `memory.events` and `/proc/pressure/*` every second, and make `failover.sh` keep polling for 15 s.
4. GKE, only with yaakov's yes in this lane's chat and a free slot from the coordinator. Run D's render, two kills:
   one on fresh streams, one with the streams at `max_bytes`.
   - Before each kill, read `/jsz` bytes against `max_bytes`, plus NATS io-busy and `/proc/pressure/io`.
   - Keep the NATS logs ("took too long", "IPQ"), the members' GC/safepoint logs and their cgroup samples.
   - Filling 8 partitions to 136 GB takes 40–60 minutes: about 450 MB/s offered, 311 MB/s stored with s2. The
     estimate is 90–110 minutes of rig on the morning's shape (about $20 per 67 minutes), so about $30–35 (range
     $25–50). Not planned until items 2 and 3 have run.

## 3b. The second election reproduces on kind (2026-11-10 night)

`scripts/proofs/kind-second-election.sh` (kind, run D's 50/200/100 ms timeouts, members at a guaranteed 2 CPUs). There
were no elections in the no-kill baselines. A second or third election followed a leader kill in 4 of 5 kills, 2.5–22 s
after the first. Each one began on the follower, which lost the new leader's heartbeat, as in E3. cgroup CPU
throttling and memory events stayed near 0, so it is not CPU quota or cgroup memory. The tap-off arm and the GC/safepoint
logging rerun did not run: the Mac stayed above load 12 all night. Evidence and next steps: shared notes
`ri38-second-election-kind/README.md`. `SnapshotBarrierPerformanceTest` passed run alone at load 12.
