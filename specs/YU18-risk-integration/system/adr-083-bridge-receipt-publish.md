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

- *Synchronous JetStream publish with retry.* It waits on every ack (~350K msgs/s ceiling on the bench),
  and its retry reorders the partition.
- *Retrying 429s from the lane.* It reorders the partition (Decision 4).
- *Watching the server's WARN log.* It is rate-limited, carries no ids, and is not on the publisher's
  path.
