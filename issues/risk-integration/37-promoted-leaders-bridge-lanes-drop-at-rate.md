# RI37: a promoted leader's bridge lanes drop records for tens of seconds at rate

Opened 2026-11-10 (trade-history-tiers lane, the ADR-103 re-test, shared notes `retest-520k-20261110/run-d-failover-at-rate`).
Status: fix landed on branch `ri37-promoted-lane-ring` (FR-DRS-47, ADR-083 decisions 19–21); unit test, kind proof and their
controls pass; the GKE re-run is pending. Blocks ADR-103's "failover at rate" acceptance until the GKE re-run passes; does not block the timeouts
decision (200/100 ms holds at 40Gi).

## What

Run D: 520K orders/s setting on the three-server NATS render (members c4d-standard-16, 15 CPU / 40Gi, 200/100 ms), the
leader's JVM killed (`kill -9`) at 10:18:51 am ET at ~510K/s. The cluster did its part: member 2 was LEADER_READY 0.17 s
after the election opened (about 1.1 s after the kill at the 200 ms heartbeat timeout), a second election four seconds
later made member 1 the leader, the driver kept sending at 510K/s with 0 rejected, the killed member rejoined
(FOLLOWER_CATCHUP 96 s at rate) and all three members agree at 1,003,275,732 sides.

The stream did not: the history store holds 980,327,391 of those sides, 22,948,341 missing, every one after sequence
922,679,229 (the failover). history-feed reports 0 pending and 0 duplicates. The new leader's `/health` bridges say
where they went: `trades.queueDropped` 22,948,341 (the missing count exactly) and `orders.queueDropped` 46,297,224;
member 2, leader for four seconds, `trades.queueDropped` 6,800,148. FR-DRS-37's catch-up itself was small and correct
(each lane "publishing 6-24 record(s) after the stream's last, skipped ~65,520 already stored"); the drops are live flow:
for ~45 s after promotion the lanes could not drain the apply thread's queue (capacity 65,536 per lane) at 510K/s and
the non-blocking enqueue counted the overflow as dropped. The members' state is exact; the stream, the read model and
the history store are short by those sides, and nothing republishes them inside the new leader's life (FR-DRS-33's
ack-gap item has the same shape for an unacked publish).

Runs A-C the same morning, without a failover, were lossless at 400K and ~500K (G3h 1..830,602,622 each once).

## Why it matters

Production at this rate loses ~13% of a run's sides from the stream on every leader change, counted but not
recovered. The kind failover proof (FR-DRS-37/40, `bridge-failover-replay`) passed because kind rates never fill the
queue. The acceptance hour of ADR-103 §5 cannot include a failover until this is fixed, and a production failover at
rate today needs the FR-DRS-33 recon to rebuild the stream from the members.

## Fix (to decide)

- Back-pressure instead of drop on promotion: the apply thread cannot block, but the lane can publish from the
  member's own ring/log (every member keeps the last capacityPerLane in every role) and catch up from there, as the
  catch-up path already does for the stream's tail; i.e. treat an overflow as "catch up again from the last acked
  sequence" rather than as a loss.
- Warm the promoted member's lanes before it takes live flow (FR-DRS-39 warms the publish path at start; the lanes of
  a follower that becomes leader also need their NATS connections and receipt windows hot), and measure the lanes'
  throughput in the first 60 s after promotion.
- The second election at +4 s: find why member 2 lost the leadership (its lanes' catch-up plus live flow at 510K/s
  on a cold leader, against a 200 ms heartbeat?) and whether it recurs; two elections double the loss window.
- Prove on the p4k rig: kill the leader at 500K, expect `queueDropped` 0 on every member and G3h exact.

## Evidence

Shared notes `retest-520k-20261110/run-d-failover-at-rate/`: `findings.txt`, `failover.txt`, `lat-before.txt`,
`lat-after.txt`, `load-result.txt`, `lag.tsv`; the members' `/health` bridge counters and the ClickHouse gap query
(gaps of 1 from 922,679,230 on) are in `findings.txt`.

## Diagnosis (2026-11-10, from the code and run D's counters; run D kept no member logs)

- **Structural.** A lane was the only consumer of its 65,536-record queue, and the same thread did the blocking NATS
  work:
  - the FR-DRS-37/38 catch-up's stream read: up to 10 attempts of `$JS.API.STREAM.NAMES` and
    `STREAM.MSG.GET`, each with jnats' 2 s request timeout, plus the 200 ms settle;
  - reconnects (1 s between tries);
  - replacing a connection that has had no receipt for 5 s.

  While it waited, the apply thread's offers were refused. At 500K orders/s on 8 partitions that queue held about
  1 s of a trade lane and 0.5 s of an order lane. A refused record never reached the ring, so no catch-up could
  republish it.
- **Why ~45 s.** On member 1, trades `gaps` 24 over 8 lanes is about 3 drop episodes per lane, and 22.9M sides is
  about 45 s of the whole flow (507K sides/s). The catch-ups found the stream's last inside the ring (6–24
  published). So the lanes were not slow; they were held, about 15 s an episode. A lane that kept up only slowly
  would drop continuously and count a gap on every pass. A held stream read fits:
  - FR-DRS-42 records JetStream API stalls of 8–30 s on this rig under load;
  - one catch-up can wait 10 × (2 + 2 + 0.5) s.

  Cold JIT was not it: FR-DRS-39's warm-up compiles the publish path at start, and a held lane publishes nothing
  either way. The catch-up scan competing with live flow was not it either: the scan is 65K in-memory compares.
- **Member 2.** It led for about 4 s, published 1,368,142 sides and dropped 6,800,148 (about 13 s of flow). None of
  those drops is in the store's shortfall, which is member 1's count exactly. So member 2's lanes were held mostly
  after it lost the lead: a follower's lane only reads its queue and drops only when held. Its catch-up, or its
  replacement of a receipt-stalled connection, plausibly outlived its leadership.
- **The second election (+4 s).** Not caused by the bridges: the apply thread's offer never blocked, before or
  after the fix. A refused offer cost one volatile store. Open questions, which the GKE recipe answers from the
  members' logs:
  - Did member 1 time out member 2's heartbeats at 200 ms?
  - Or did member 2 step down?
  - Is a cold leader path under 24 reconnecting gateways at 510K/s the cause?

  Under the fix a second election is one more catch-up within the ring, not a second loss window.

## Fix (FR-DRS-47)

The apply thread writes each record into its lane's ring (`BRIDGE_LANE_RECORDS`, 2^20 slots) and is never
refused. The lane reads the ring by index. A held lane publishes its backlog after the cut when it resumes:
about 16 s of a trade lane and 8 s of an order lane at 500K.

- The lane releases what it no longer needs (published or read, and older than the last 65,536), so a current lane
  holds what it held before. The slots cost 64 MiB for 16 lanes; records stay on the heap only while held.
- `queueDropped` now counts only a leading lane more than its ring behind, exactly.
- `/health` adds `maxBacklog`.
- The catch-up logs its duration and the records applied meanwhile.

Proven:

- `ReceiptLanesIT#aPromotedLaneThatCannotReadTheStreamKeepsWhatItAppliesMeanwhile`: NATS frozen 3 s across a
  promotion, 100,000 sides and order events. All stored once, in order. The control on the parent build at 65,536
  stored 65,700 of 100,100 sides, 34,400 missing.
- `#aPromotedLaneLappedPastItsRingIsACountedGap`: past the ring is a counted gap.

On kind, `scripts/proofs/kind-bridge-failover-at-rate.sh` (2026-11-10) froze NATS for 121 s across a leader kill at
4 x 250 orders/s:

- this build: 231,662 of 231,662 sides and 463,324 of 463,324 order events, with `queueDropped` 0;
- the control, the parent's image: 201,966 of 232,066 sides and 338,327 of 464,132 events. The new leader's
  `queueDropped` was 30,100 and 125,805, each shortfall exactly, as in run D.

GKE re-run: the recipe below, **written, not run** (needs yaakov's yes).

## GKE re-run recipe

Shared notes `ri37-gke-recipe-20261110/` (README, `run-ri37.sh`, `watch-bridges.sh`). The run is run D on the
FR-DRS-47 member image: kill the leader at 500K. Expect `queueDropped` 0 and `gaps` 0 on every member, and G3h
exact. It also keeps every member's log for the election question. Cost about $20 (range $15–30): about 40 minutes
of the 2026-11-10 rig, which cost about $20 for 67 minutes.
