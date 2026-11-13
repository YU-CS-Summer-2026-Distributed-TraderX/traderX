# ADR-083: The bridges count every NATS ingest drop, and alarm instead of retrying

Status: accepted (yaakov, 2026-10-27). Component: [downstream read side](../components/downstream-read-side/spec.md)
(FR-DRS-11..17). Measurements: `docs/engineering/downstream-at-400k.md` §7.9 and §7.10.

## Context

The leader's trade and order bridges publish core NATS messages that the JetStream stream
`TRADERX_FEED` captures. When the stream's ingest queue (`max_buffered_msgs`, `max_buffered_size`)
is full, the NATS server drops the message. It logs a rate-limited WARN, but it raises no advisory
and no metric. The only per-message signal is a `429 Too Many Requests` reply, and the server sends it
only when the published message carries a reply subject. With the default queue, a 400K/s
burst lost 893,759 messages on a bench and 16,192 trade sides on a live cluster. Nothing upstream of
the projection saw either loss.

## Decision

1. **One lane per partition.** Each bridge has one queue, one publisher thread and one NATS connection
   per `BRIDGE_PARTITIONS` partition. A partition's messages are published in the order the engine
   applied them.
2. **Receipts, not waits.** Every message is published with a reply subject on its lane's inbox. One
   async subscription per lane counts each reply. A PubAck counts as stored. A 429 status, or an
   `"error"` body, counts as rejected. The publisher never blocks on a reply. On the trade bridge the
   reply subject is `<inbox>.<trade id>`, so a rejection names the exact trade side.
3. **Loud and per partition.** The counts are reported on `/health` `bridges` and on `/metrics`
   `traderx_bridge_published`, `traderx_bridge_dropped` and `traderx_bridge_ingest_rejected`. An
   `ALERT` log line names the bridge, the partition and its subject. It is written on the first
   rejection and every 10,000th. The trade bridge also logs the rejected ids in batches.
4. **Alarm, not retry.** A rejected message is never resent. By the time its 429 arrives, later
   messages of the same partition may already be stored. A resend would land after them. The
   projection books a partition in stream order, and average cost depends on order, so a resend
   would book a different position from the engine's. The loss is made exact and visible instead.
   **Amended 2026-11-12 (FR-DRS-63):** the lane no longer publishes on after a refusal. It stops, re-reads the
   stream's last on a new connection and republishes what follows, in order, with half its window. That covers a
   refused tail. This decision still holds for a refused message that a later one was stored after: it is never
   resent, and it is counted (`queueDropped`, a gap).
5. **Repair is a replay of the source, in order.** The member archive holds every fill in consensus
   order (`GET /recon/catchup-events`, FR-APR01..04). Repair re-derives from it. It never resends a
   bridge message.

## Consequences

- An ingest drop now shows up at its source, in the same ids that the projection's recon reports
  MISSING. Measured live: 16,192 counted and 16,192 MISSING, the identical set.
- The cost is one extra NATS core per bridge and about 2.8 µs of server CPU per message (bench: core
  1.5–2.1 µs, receipt 2.8 µs, synchronous ack 4.3–4.6 µs). The engine's apply thread does no extra work.
- The ingest queue is sized so that the drop does not happen at the rated load: 2,000,000 messages / 1 GB
  on GKE (0 rejected in a live 400K burst). The receipt count proves it.
- No wire format changes. The JSON bytes and subjects are unchanged. A core subscriber ignores the reply
  subject.

## Amendment, 2026-11-08: a promoted member publishes what the stream is missing (FR-DRS-37)

Only the leader published, and live. A record the dying leader applied but did not get stored was sent by no
one: in its lane queue, on the wire, or committed only in the new leader's term and applied there before the
role change. Production lost 4 trade sides at a leader kill (e1108) and 6 the day before (e1107b).

6. **Every member offers, the leader publishes.** The service offers every trade and order record on every
   member, tagged with the role it was applied in. A follower's lane keeps the last `capacityPerLane` records.
7. **Catch-up from the stream's last stored message.** At a promotion the lane reads its subject's last message
   (`$JS.API` `STREAM.MSG.GET` by last-by-subject, the stream found by subject) and publishes the kept records
   after it, then everything new, holding later records to the same cut. Apply order is the same on every member,
   so the partition stays in apply order and each record is stored once. Decision 4 is unchanged: this is not a
   resend of a 429 but the first publish of a record its leader never stored.

The deterministic core is unchanged: same outputs, same snapshot, no format change. So no snapshot barrier or
fresh epoch is needed, and the build rolls like any bridge change. A follower now allocates one record per output
and keeps up to 65,536 per lane per bridge.

**Rejected:**

- *Every member publishes with `Nats-Msg-Id` dedupe.* That triples the bridges' NATS traffic. The dedupe table
  also holds every id for the duplicate window: 48M ids at 400K/s for the 2-minute default.
- *Replay from the Aeron archive at a promotion.* It is correct, but a shadow replay of the log is seconds of work
  on the new leader's critical path, and the kept tail already holds every record that can be missing.

## Amendment, 2026-11-08: a NATS restart runs the same catch-up (FR-DRS-38)

Decision 7 ran only at a promotion. When NATS restarted under a steady leader, whatever was in flight at the kill
(in its buffers or ingest queue, not yet stored) was lost, and jnats' reconnect then carried on after the hole. On
kind (2026-11-08) the stream was missing 952 sides that all three members had applied.

8. **Every lane keeps its tail in every role.** The ring of the last `capacityPerLane` records (Decision 6) is now
   kept on the leader too, with a pointer to the next record to publish.
9. **The lane owns its reconnect.** The connection is opened with `noReconnect`. It is replaced when NATS closes it,
   when a publish throws, or when no receipt (Decision 1) arrives for 5 s with publishes outstanding. On every new
   connection while leading, the lane runs Decision 7. The read waits for the subject's last message to hold still
   for 200 ms, and a stream it cannot read is retried on the next connection.
10. **Past the ring is loud.** If the newest record the ring evicted is after the stream's last, the catch-up counts
    a gap (`bridges.*.gaps` on `/health`), logs an ALERT, and publishes what it kept, in order. Repair is the archive
    replay (Decision 4). While NATS is unreachable the lane waits in its reconnect, so it is the lane's queue that
    holds the outage; a full queue refuses the newest records and that too is a counted gap (Decision 11).

Cost: the leader now retains the records it publishes for as long as a follower already did, one array store per
record. A record is held for one ring's length, about 1 s at 400K/s and P=6, instead of until its publish. There is
no new per-message NATS traffic: the receipts already flow. Measured locally against 9728ba51, with P=6 trade lanes,
200K sides/s for 15 s, and Docker NATS (two runs per arm). Both arms published and stored every side with no drops.
Process CPU was 26.0-28.3 s against 23.8-25.5 s, GC 208-217 ms against 141-156 ms, and heap 78-112 MB against
51-57 MB. At 400K sides/s the local NATS refused 50-75% at ingest (429) in both arms, so that rate is measured on the
bench, not here.

11. **A queue drop is a gap.** A record the lane's full queue refuses never reaches the ring. The lane remembers the
    newest one and counts a gap with an ALERT: at the next catch-up if it is after the stream's last, or at once
    while leading. `/health` adds `queuedByPartition`, each lane queue's depth.

The 50,338 sides one local 400K run dropped were queue drops at a cold start, not a reconnect or the ring. With the
publish path still interpreted, a lane ran at 33-42K sides/s against 67K/s offered until the JIT compiled it, 0.4 s to
5.8 s into the load in three logged runs. The build before this change (9728ba51) backs up the same way (6 of 10 runs
past 10K queued, one dropping 583). After a 4 s warm-up at 100K/s the queues peaked at 1,649 in six runs.

Warmed A/B against 9728ba51 (three runs per arm and rate, 15 s, P=6 trade lanes, Docker NATS). No run in either arm
dropped a side or reconnected.

| | this build | 9728ba51 |
|---|---|---|
| 200K sides/s: CPU | 24.3-25.2 s | 22.3-23.1 s |
| 200K sides/s: GC | 138-236 ms | 154-169 ms |
| 200K sides/s: heap at end | 124-175 MB | 44-64 MB |
| 200K sides/s: peak queue | 297-393 | 180-2,298 |
| 400K sides/s: CPU | 32.1-33.5 s | 28.2-29.8 s |
| 400K sides/s: GC | 345-467 ms | 132-321 ms |
| 400K sides/s: heap at end | 115-156 MB | 103-560 MB |
| 400K sides/s: peak queue | 786-2,162 | 247-3,359 |

12. **A lane warms its publish path before its first record (FR-DRS-39).** It sends 20,000 sample records through
    the same encode-and-publish code to `_bridge.warm.<bridge>`, a subject with no stream and no subscriber. The
    samples vary as real records do (side, status, the optional keys), so the compiled code keeps those branches.
    With it, the path was C2-compiled 0.9-1.7 s before a 0-to-400K step in three logged runs, and the startup
    backlog over 10 runs peaked at 3,674 (56,512 without). Cost: about 1 s of each lane thread at start, and
    20,000 discarded messages per lane, per bridge, per member.

**Rejected:**

- *A follower keeps the path warm by encoding a share of what it keeps.* Not built. The start-up warm-up covers a
  follower for as long as its JVM keeps the compiled code, and a promotion after hours of following has not been
  measured. `queuedByPartition` on the new leader shows it if the path has gone cold.
- *A larger lane queue.* It buys seconds of a cold lane, not a compiled one, and costs member heap.
- *Async JetStream publish acks with a bounded in-flight window.* The receipts are already per-message acks
  (Decision 1), and a window bounded by the same ring cannot recover more than the ring holds. It would only move
  where an over-long outage loses records, from unacknowledged to unsent, and add a hold path to the lane.
- *Keep jnats' reconnect and catch up on its RECONNECTED event.* jnats flushes its buffered publishes to the new
  server before the event, racing the stream read, so a record can be stored twice.
- *A larger ring sized for a NATS pod restart at 400K/s.* A trade record is about 64 B and an order record about
  130 B, so ten seconds at 400K orders/s is over 1 GiB of member heap. The archive replay already repairs a gap this rare.

- *Synchronous JetStream publish with retry.* It waits on every ack (~350K msgs/s ceiling on the bench),
  and its retry reorders the partition.
- *Retrying 429s from the lane.* It reorders the partition (Decision 4).
- *Watching the server's WARN log.* It is rate-limited, carries no ids, and is not on the publisher's
  path.

## Amendment, 2026-11-09: an order event carries its position under every id scheme (FR-DRS-40)

Decision 7 cuts an order lane at (`consensusSequence`, `outputOrdinal`), and only epoch-v1 ids wrote that pair.
Production and kind run no run descriptor (ADR-101), so their order events had no position: the order lane never
caught up, and a NATS restart lost its in-flight events without a counter. The amendments above were proven for
order events with managed ids only.

13. **The position is on every order event.** The legacy message gains `consensusSequence` and `outputOrdinal`,
    which the engine already assigns to every output; it gains no scope, epoch, scheme or hash, so it is still an
    unattributed event to every reader. The cut reads the pair without a descriptor. The epoch check stays for
    managed ids; a legacy message cannot name an epoch, so the stream must start empty with the epoch (FR-PRG-29),
    which the trade cut already relies on.
14. **No position is a gap, not a guess.** A stream whose last message has no position was written by an earlier
    member build. If the lane holds records it applied while following or sent earlier, it counts a gap and logs an
    ALERT, and publishes only what it has not sent as leader. This is the state during the roll that deploys
    Decision 13, and never after a fresh epoch.

Cost: about 50 bytes per order event on the wire and in the stream. No engine, log or snapshot change, so no
snapshot barrier and no fresh epoch.

**Rejected:**

- *Managed epoch-v1 runs on production.* Not a render change: the descriptor must be provisioned on every member
  disk, an epoch-v1 core starts with admission closed and the transition that opened it was retired (FR-THT-01),
  trade ids and notification subjects change, and the rig's gates assume the legacy scheme (ADR-101 declined it).
- *The position in a NATS header.* history-feed and trade-processor already read the pair from the payload, and a
  header moves the lane to `HPUB` for every message.

## Amendment, 2026-11-09: a lane publishes its drain as one message (FR-DRS-41)

Context. The 400K campaign (RI34, 2026-11-08/09) was lossless at 67K orders/s and one NATS server dropped feed
messages from about 285K messages/s with 23–29 CPUs; FR-DRS-34 had measured one c4d-standard-32 at 415–505K
messages/s with its disk 16% busy and CPU 35–60% idle, whatever the stream count. The cost is per message, not per
byte: a local 4-CPU server (2026-11-09) stored 363–406K messages/s at 540 B, 385K at 1,080 B, 150K at 5,400 B and
22K at 54,000 B, i.e. 0.4M, 0.77M, 1.5M and 2.2M records/s. The leader paid the same per-message cost on its 16
publisher and 16 receipt threads and held about 185–200K orders/s against the bench's 400–450K with the bridges off.

15. **One message per drain.** The lane thread takes what its queue holds, up to `BRIDGE_BATCH_MAX` (64; 1..1,024)
    records and 256 KiB, encodes them in apply order separated by a newline, and publishes one message with one
    reply subject. A quiet lane sends one record per message, the wire before this amendment; a loaded lane sends
    what piled up while the last message went out, which is the egress-coalescing shape the members already use.
16. **Receipts count records.** The reply subject carries the record count and the first and last key
    (`<inbox>.<n>.<first>~<last>`; `<inbox>.<n>` on the order bridge). A PubAck acks n records, a 429 rejects n and
    the rejected-ids log names each message's first~last. `/health` `bridges.*` adds `messages`.
17. **The cut reads the last record.** The catch-up compares the last record of the stream's last message; a
    one-record message is unchanged. Its republish is batched like anything else.
18. **Readers split, nothing else changes.** Every reader of `/trades*` and `/orders*` splits a message on the
    newline before its per-record parse (decisions 1–14 are per record and hold). history-feed stores one row per
    record with the message's `seq`; trade-processor's trade view keys its replay check by (seq, index) and applies
    a message's sides under one lock, so its snapshot cursor is always a whole message.

Rejected: NATS 2.12's atomic/fast batch publish (server-side batches with per-message storage: it buys the ack
round trip, which the receipt publish already does not pay); a NATS cluster first (FR-DRS-34 proved three servers at
1M messages/s, but it leaves the leader's per-message cost and is deployment work; it remains the complement if the
single server's byte rate at 400K, about 540 MB/s, has no margin); a compact wire first (bytes were not the wall).


## Amendment, 2026-11-10: the apply thread writes into the lane's ring; a stalled lane publishes its backlog (FR-DRS-47)

Context. On GKE (2026-11-10, RI37, ADR-103 re-test run D) the leader was killed at about 510K orders/s. The members
agreed at 1,003,275,732 sides and the stream was short 22,948,341, the new leader's `trades.queueDropped` exactly.
Decisions 7–11 were correct and small: each promoted lane published 6–24 records after the stream's last. The loss
was structural. A lane was the only consumer of its 65,536-record queue, and its one thread also made the blocking
stream reads of the catch-up and the reconnect. At 500K on 8 partitions that queue held about 1 s of trade sides and
0.5 s of order events. A catch-up may take 10 attempts of two 2 s JetStream requests, and JetStream API stalls of
8–30 s under this load are on record (FR-DRS-42). Whatever the queue refused never reached the ring (Decision 11), so
no catch-up could republish it.

19. **The ring is written by the apply thread.** `offer` stores the record in its lane's ring (`BRIDGE_LANE_RECORDS`
    slots, 2^20 by default) and advances the lane's head. It never blocks and never refuses. Each record carries its
    index, so a lane reading a slot the apply thread has since overwritten knows it was lapped. The lane reads the
    ring in order: the role tags (Decision 6) and the publish path (Decisions 7–18) are unchanged. A stream read, a
    reconnect or a frozen socket now holds the lane thread without losing anything the ring can hold. The lane
    publishes the backlog after the cut when it resumes.
20. **Release keeps the heap where it was.** The lane drops its reference to a record once it has read it while
    following, or published it while leading, and it is older than the last 65,536 (Decisions 6 and 8). A current
    lane therefore holds the same records as before. Only a stall holds more, up to the ring.
21. **Counted where it is exact.** A leading lane lapped after its catch-up has lost exactly the records from its next
    unpublished one to the oldest the ring still holds. They go in `queueDropped` (and `traderx_bridge_dropped`),
    with a gap and an ALERT. A lap during a catch-up is Decision 10's gap. `/health` adds `maxBacklog`, the most a
    leading lane has held unpublished. The catch-up's log line gives its duration and the records applied meanwhile.

Cost. The slots are 4 MiB per lane, 64 MiB for 16 lanes, allocated at start. Per record, the apply thread does two
ordered stores in place of a queue offer. The lane does one compare-and-set to release the record. A full ring is
about 64 MiB of trade records or 130 MiB of order records per lane, held only while that lane is stalled. Measured
locally (P=6, 200K sides/s, two runs per arm): process CPU 20.7–21.0 s against 20.5–20.6 s, GC 126–141 ms against
133–169 ms, live heap after a full GC 78 MB against 48 MB (the slot arrays). The offer took 24.6–25.5 ns per side
against 21.8–34.8. Neither arm dropped a side.

Superseded rejections. "A larger lane queue" and "a larger ring sized for a NATS pod restart" (amendment of
2026-11-08) were rejected for member heap. Both assumed the records stay resident. Under Decision 20 they stay only
while a lane is behind.

**Rejected:**

- *Run the catch-up's stream read on another thread and keep the queue.* It covers the catch-up but not a reconnect
  or a blocked publish. It still needs a bounded place for what arrives meanwhile, and that place is this ring.
- *Back-pressure on the apply thread.* The deterministic core cannot wait on NATS (Decision 1).
- *Re-derive refused records from the Aeron log.* The records are the engine's outputs, not its inputs. Re-deriving
  them is the archive replay (Decision 5), not something a lane can do live.
- *A ring sized to cover any stall.* JetStream can stall longer than any heap. Past the ring the loss is counted and
  the repair is still the archive replay.

## Amendment, 2026-11-10: release only what the stream confirmed (FR-DRS-49, RI38)

Context. Decision 20 released a record once it was older than the last 65,536 a lane had read or published. That
bounds what a successor can republish of a dead leader's unstored tail at 65,536 records per lane, about 0.5–1 s at
500K orders/s on 8 partitions. On 2026-11-09 (downstream-at-400k.md §7.15 item 4) a stalled volume left the leader's
publishes unacked, and a full archive then killed it: about 1.9M sides lost, about 240K per lane. The same bound held
within one leader's life, for a reconnect's catch-up after a stall. Decision 20 assumed a current stream. Under a
stall, "published" is not "stored", and only the stream can say what it stored.

22. **The stream's last stored message bounds release, in every role.** Each lane asks the stream for its last
    stored message on its subject: `STREAM.NAMES` once, then `STREAM.MSG.GET` last-by-subject. It asks
    asynchronously, at most every 250 ms, and only when Decision 20 would release past the last confirmed position.
    It never releases a record after that position. The answer is compared with Decision 7's cut and bisected in
    apply order. A current stream confirms within the keep, so Decision 20's heap holds. A stalled stream holds
    records up to the ring (Decision 19). Past the ring the apply thread overwrites the oldest unconfirmed records,
    and the successor's catch-up counts that as a gap (Decision 10).
23. **The stall check counts only silence.** A lane's quiet timer advances while no publish waits for a receipt, in
    every role, and a catch-up's stream read restarts it. Before this, a promoted lane's first publish read its whole
    time following as 5 s without a receipt, and it reconnected and caught up again (GKE run E3: all 16 lanes).
    `/health` adds `reconnects`.

Cost. At most one small JetStream API request per lane per 250 ms: 64 per second for a member's 16 lanes, answered by
the stream's server. The lane thread never waits on it. With no JetStream, no stream, or no comparable position,
Decision 20 alone applies.

**Rejected:**

- *Learn the leader's receipts on the followers.* That is off-consensus state crossing members: a protocol addition,
  §8 item 1's option (i). The stream already holds the answer, and every member can ask it.
- *Followers keep the whole ring.* That is 1.6 GiB of resident records per member at all times, to cover a case the
  stream can bound.
- *Block the lane on the probe.* A JetStream API stall would then hold the lane, which is RI37's mechanism again.

## Superseded in part, 2026-11-12: past the ring is the spool (FR-DRS-57, ADR-106)

Decisions 10, 21 and 22 end at the ring: a lane further behind than its ring has lost what the apply thread overwrote.
[ADR-106](adr-106-a-lagging-lane-spools-to-the-members-disk.md) copies a lagging ring's older half to the member's
disk, so those decisions' gap now begins past the spool's byte budget. Everything else here stands and runs over the
ring and the spool together. Decision 2 gains a bound: a lane still never waits on one reply, but it publishes no more
than 1,024 messages ahead of its receipts (ADR-106 decision 9).
