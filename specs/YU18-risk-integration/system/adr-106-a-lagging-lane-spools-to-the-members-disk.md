# ADR-106: A lane that falls behind its stream spools to the member's disk

Status: accepted (yaakov, 2026-11-12), with one follow-up before production use: the spool moves off the archive
volume onto its own (below). Component:
[downstream read side](../components/downstream-read-side/spec.md) (FR-DRS-57). Builds on
[ADR-083](adr-083-bridge-receipt-publish.md) decisions 19–23 (FR-DRS-47, FR-DRS-49).

## Context

Since FR-DRS-47 the apply thread writes every trade side and order event into its lane's ring
(`BRIDGE_LANE_RECORDS`, 2^20 slots) and is never refused. Since FR-DRS-49 a lane releases a record only once the
stream has confirmed it stored. So the ring holds two things: what the lane has not published yet, and what it
published that the stream has not confirmed. Either one can outgrow the ring.

- Acceptance run 5 (2026-11-11): one NATS server's volume sat at 84–87 % busy for minutes. The lanes kept publishing;
  the stream stored slowly; published − acked reached 20.3M records. The rings wrapped over records the stream had not
  confirmed, connections were replaced, and the catch-ups found the stream's last behind the oldest record they still
  held. `queueDropped` 172, `gaps` 9, history NEW 116,662 short of what the clients sent.
- The ring holds about 16 s (trade lane) and 8 s (order lane) at 500K orders/s. Any slower event loses records:
  a saturated volume, a NATS restart, a leader change during either.

The requirement: a slow stream never costs a record, and the apply thread is never blocked or refused.

A backlog of minutes at rate is tens of millions of records per lane. That does not fit the heap, so the records have
to be on a disk. The question is which disk, in which form, and who writes it.

## Options

### A. Replay the cluster log from the Aeron Archive (the direction named in the brief)

An overflowing lane drops its ring, re-derives its records from the archive from its last confirmed position, and
rejoins the ring.

The archive does not hold the records. It holds the commands; a trade side or an order event exists only as the output
of applying them to engine state. So "replay" means a second engine: restore a snapshot at or before the lane's
position, then apply the log through a shadow `MatchingEngineClusteredService`. `ClusterRecon` already does this for
recon and audit (`restoreSnapshot`, `replayRange`), which is what makes the option look cheap. What it costs here:

1. **A second engine inside the leader, at the moment the leader is stressed.** The shadow holds the whole book and
   the idempotency table again, in a 12 GiB heap, and applies the log at full speed on a leader core. ClusterRecon's
   shadow is an admin query an operator chooses to run; this one would start itself during a disk stall or an election.
   RI38 already traced a second election to allocation stalls from a republish storm on a new leader.
2. **The shadow's records are not the live records.** `drainOutputs` stamps a NEW order's booking event with the
   submitting gateway's name from the live `ClientSession` (FR-FFR-14); the shadow applies with a null session
   (`ClusterRecon.applyAndProject`), so its booking events carry no gateway, and the read model would not route those
   orders' fills. The trace key is also live-only. Closing this means rebuilding Aeron's session table from the
   snapshot's cluster section and the log's session events.
3. **The archive is purged behind the second-latest snapshot (FR-PRG-40).** Snapshots are every 60 s. A lane more than
   about two minutes behind has no log to replay unless purge is pinned to the slowest lane's position. Pinning it
   makes a slow NATS volume fill the member's archive volume, and a full archive kills the leader (2026-11-09).
   The fallback would turn a capture problem into a consensus problem.
4. **Rejoining needs a second position scheme.** A ring index is a per-process counter. The shadow would have to hand
   over on (tradeSeq) and (consensusSequence, outputOrdinal), per lane, while the ring keeps moving.

Rejected for the leader. **Falsifier:** A is right once capture runs in a process that is not a voting member (T3),
because there the second engine *is* the capture process, its heap is its own, and back-pressure on it is free. See
"T3" below.

### B. A bigger ring

Moves the cliff, costs heap (a full default ring is already 1.6 GiB across 16 lanes). Rejected in the brief.

### C. Back-pressure on the apply thread

Forbidden: a slow NATS volume would stall consensus.

### D. Record the output stream with the member's archive

The apply thread offers each record to an IPC publication that the archive records; lanes replay it. Rejected: a
publication's `offer` back-pressures when the recorder is behind, so the apply thread is refused or blocked by a slow
disk, which is C by another route. It also adds a recording to the consensus volume at the full output rate, always.

### E. Spool the ring's older half to the member's disk, off the apply thread (chosen)

A spooler thread per bridge copies a lagging lane's records from the ring to files before the apply thread overwrites
them. The lane reads its records by the same index from the ring or from the spool. Nothing else about the lane
changes: the catch-up, the cut, the confirmation probe and the release rule are the ones FR-DRS-37/38/47/49 already
define, over a longer window.

## Decision

1. **The older half of a lagging ring is mirrored to disk.** When a lane holds more than half its ring (`head − low`
   over `slots / 2`), the bridge's spooler thread writes the records from the oldest held up to `head − slots / 2`
   into segment files, in apply order, and keeps doing so while the lane lags. A lane that keeps up never spools: it
   holds about 100K records (the 65,536 keep plus one probe interval) against a threshold of 524,288.
2. **The apply thread is unchanged.** `offer` is the same two stores as FR-DRS-47. It does not know the spool exists.
3. **One index, two homes.** A record's ring index is its address in the spool. `at(k)` reads the spool for an index
   below the spool's end and the ring above it. `r.sent` becomes an index high-water mark (`sentHigh`), since a
   spooled record is a decoded copy.
4. **Every role spools.** FR-DRS-49 makes a follower hold what the stream has not confirmed, so a follower's lanes
   lag with the leader's and spool with them. A member promoted mid-overflow runs the FR-DRS-37 catch-up over its own
   ring and spool. This is what covers a leader change during a slow stream.
5. **Release deletes whole segments.** `low` advances as before (confirmed stored, and older than the keep). A segment
   wholly below `low` is deleted. When `low` passes the spool's end the spool is empty and the lane is back on its
   ring alone.
6. **The spool is bounded by a byte budget, and past it the loss is counted, not silent.** `BRIDGE_SPOOL_BYTES`
   per member (default: 64 GiB or half the free space of `BRIDGE_SPOOL_DIR` at start, whichever is smaller; 0 turns
   the spool off). When the budget is spent, or a write fails, or the spooler itself falls more than half a ring
   behind, the spool stops growing and keeps what it has. The lane publishes and confirms everything spooled, then
   counts the records after it exactly (`queueDropped`, a gap, an ALERT) and resumes from the ring. The oldest part of
   the backlog survives; the count names the rest.
7. **The spool is process-lifetime.** It is written to the page cache, not fsynced, and wiped at start. A restarted
   member's lanes start empty as they do today; its records were also held by the other members.
8. **A spooled record is the bridge's binary fields, not its JSON.** 51 B a trade side and about 150 B an order event
   against 300–400 B of JSON: the spooler's write rate is what decides whether it keeps ahead of the apply thread.

9. **A lane publishes no further ahead of its receipts than a window** (1,024 messages, 65,536 records at the default
   batch). The first promotion run without it republished 500,001 spooled sides at the lane's speed and had 68,001
   rejected at the stream's ingest (429), which ADR-083 decision 4 does not resend. The window keeps the backlog in
   the ring and the spool, where it is safe, instead of in NATS's buffers, where run 5 had 20.3M.
10. **Behind a frozen spool the lane does not guess.** It waits for the stream to confirm the spool's last record
    before counting the hole; after 5 s without that confirmation it reconnects and catches up from the stream's
    last. A catch-up that lands just past the hole counts a gap.

### What bounds it now

"At any duration" is not literally reachable on a member: every design that keeps the records keeps them on a finite
disk. The bound moves from 8–16 s of heap to the budget:

| Event at 450K orders/s (8 partitions) | Spool rate | 64 GiB lasts |
|---|---|---|
| One NATS server slow (run 5: 2 partitions) | ≈ 45 MB/s | ≈ 25 min |
| All of NATS down | ≈ 180 MB/s | ≈ 6 min |

(From the record sizes at 63K sides/s and 127K order events/s per partition; not yet measured at rate.) The members' archive volume is 600 Gi. Past the
budget the loss is the same loud, counted gap as today, repaired from the archive (`/recon/catchup-events`).

The remaining cliffs, each counted when it happens:
- the budget;
- the member's own volume writing slower than the lagging lanes' rate for longer than half a ring;
- a member restart during an overflow (decision 7), when no other member survives with the tail.

### T3 (capture off the leader)

This does not build T3 and T3 does not need it. T3 moves the lanes to a process that reads the log at its own pace;
there a slow stream back-pressures only that process's consumption, the archive copy it reads is the buffer, and the
ring, the spool and the every-member offer all go away. Option A's shadow engine with a real session table is half of
T3; this ADR deliberately does not start it inside the leader. Until T3, the spool is what stands between a slow
stream and a lost record.

## Consequences

- A member writes to `BRIDGE_SPOOL_DIR` (default `<CLUSTER_BASE_DIR>/bridge-spool`) only while a lane lags. In a NATS
  outage all three members write, each to its own volume.
- The spool shares the archive volume by default. The budget is what keeps a long outage from filling it.
  **Follow-up (yaakov, at acceptance): before production use the spool gets its own volume**, so a long outage's
  writes do not compete with the archive's on the consensus disk and a full spool can never be a full archive. Until
  then `BRIDGE_SPOOL_DIR` is unset in every render and the default applies. Not built yet.
- `/health` `bridges.*` adds `spooled` (records ever written) and `spoolBytes` (bytes held now, the member's total).
- No log, snapshot, ack or stream format changes. The spool's file format is private to one process.
- The publish window is new behaviour at rate for healthy lanes too: a lane whose receipts lag more than 65,536
  records now waits instead of publishing. At 127K records/s that is 0.5 s of receipt latency, far above what a
  healthy stream shows, but it is not yet measured on GKE at 450K.
