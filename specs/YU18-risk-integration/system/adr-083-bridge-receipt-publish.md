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
    replay (Decision 4).

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
