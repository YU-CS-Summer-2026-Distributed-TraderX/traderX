# RI37: a promoted leader's bridge lanes drop records for tens of seconds at rate

Opened 2026-11-10 (trade-history-tiers lane, the ADR-103 re-test, shared notes `retest-520k-20261110/run-d-failover-at-rate`).
Status: open. Blocks ADR-103's "failover at rate" acceptance; does not block the timeouts decision (200/100 ms holds at 40Gi).

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
