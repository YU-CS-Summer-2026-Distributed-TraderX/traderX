# ADR-108: The recon's engine window is a ring of primitives, read from a follower

Status: accepted (yaakov, 2026-11-12, in the recon-throughput lane's chat, after the GKE proof). Component:
[downstream read side](../components/downstream-read-side/spec.md) (FR-DRS-59). Builds on FR-DRS-56.
(ADR-106 and FR-DRS-57 are the bridge-overflow lane's; 107 and 58 are left for the latency-tail lane.)

## Context

The ledger recon compares what the engine booked with what reached the capture stream and the projection. Its
engine side is the members' live blotter: every member records each booked side into a bounded window, and
trade-processor pages through it over HTTP (`/recon/trades/blotter`).

Acceptance run 7 (`3526dcdf`, 2026-11-12) found two faults in that window.

1. **It costs the leader 27% of its service thread.** A 60 s profile of the leader's clustered-service thread in
   stage 2 (about 56% of a core at ~431K sides/s; `run7-3526dcdf/profile/leader-w1.collapsed`):

   | Under `ClusterRecon.onLiveOutput` | Share of the thread |
   |---|---|
   | `ConcurrentSkipListMap.pollFirstEntry` (evicting the oldest side) | 12.8% |
   | `ConcurrentSkipListMap.put` | 10.0% |
   | building the record (`BigDecimal.stripTrailingZeros`, the id string) | 2.3% |
   | the rest | 2.1% |

   That is ahead of egress (18%), matching (15%) and exactly-once (11%). The window is a
   `ConcurrentSkipListMap<Long, TradeRecord>`: per side it allocates a record, an id string, a `BigDecimal`, a boxed
   key and the list's nodes, then walks the list twice.
2. **It is too short to read.** `RECON_BLOTTER_CAPACITY` is 500,000 sides: 1.2 s at 431K sides/s. FR-DRS-56 made the
   recon read to the window's head each second and count what the window dropped. Over stage 2's hour it classified
   933,861,844 of 1,550,788,228 sides (60%) and counted 616,926,384 unverified; 0 missing, 0 mismatched. One reader
   reached about 260K sides/s there, and the window gives it no slack.

What must hold:
- the recon classifies every side at the full mixed rate, with room to spare;
- the leader's service thread stops paying for it;
- the recon stays independent evidence. Its engine side must come from the members' own applied state, not from the
  bridge or the stream, because the bridge and the stream are what it checks.

## What the window is, and is not

Checked in the source before choosing (`ClusterRecon`, `MatchingEngineClusteredService.outputSink`):
- The window is written from the read-side output tap, on every member, whatever its role. Only
  `/recon/trades/blotter` reads it.
- It is not in the snapshot, not replicated, and no decision reads it. A restarted member starts with an empty
  window and fills it from the log tail it replays.
- So changing it touches no format and no apply-path decision: no fresh epoch (`roll-a-format-change` does not
  apply), and determinism and snapshot completeness are untouched. Members and gateways can roll one at a time.

## Options

### (a) Record only on followers, read from a follower

The leader skips the tap; followers keep the skip list. The leader's 27% goes. The followers still pay it on their
own service threads, where it is the largest item too, and a follower that falls behind the leader is a slower
failover. A role change flips who records, so the new follower starts with an empty window. It moves the cost; it
does not remove it, and the window stays 1.2 s.

The reading half is worth keeping: any member holds the same sides, so the read need not land on the leader.

### (b) A shadow reader off the Aeron Archive

A second engine restores a snapshot and applies the log tail off the service thread, and the recon reads its output.
This is the strongest independence: the sides are re-derived from the log. `ClusterRecon` already does it for the
full-history index and the audit report. But live it is a whole second engine per member: the book and the
idempotency tables again in a 12 GB heap, and the log applied twice, forever, to produce a list the first engine
already produces. It also proves less about the running engine than reading the running engine does.

### (c) Reconcile the bridged feed against SQL

No engine cost at all. It loses the engine as a witness. The recon would compare the stream with a projection of that
same stream, so a side the bridge never published (FR-DRS-49, FR-DRS-57's whole class) is absent on both sides and
reads as agreement. That is the check passing most confidently when it knows least. Rejected.

### (d) A cheaper structure and a bigger window

The sides are numbered densely (`tradeSeq`, one per booked side), so the window can be a ring indexed by
`tradeSeq & mask` over arrays of primitives: seven stores and one ordered write of the head, no allocation, no
eviction walk. The record (id string, exact price, JSON) is built by the HTTP thread that serves a page. Memory is
37 bytes a side, so 8,388,608 sides are 310 MB, where 500,000 skip-list entries were about 125 MB.

## Decision

**(d), with the reading half of (a).**

1. **The live window is a ring of primitives** (`LiveTradeRing`): `tradeSeq`, account, security id, side, quantity,
   price ticks and time, single writer. Recording a side on the service thread is array stores. Nothing is
   allocated there.
2. **The reader builds the records.** A page read copies its slots, then re-reads the head and drops any slot the
   writer may have lapped meanwhile, so a torn slot is never served. The wire is the same JSON, record for record.
3. **`RECON_BLOTTER_CAPACITY` is 8,388,608 in the production render**: 19 s at 431K sides/s and 9.7 s at twice that.
   `prepare.py` sets it with the other throughput member settings (`--throughput-members`). The capacity is rounded
   up to a power of two.
4. **The gateway forwards a blotter read to a follower first**, the leader last. It already learns the leader from
   the cluster client. A read that reaches the leader is still correct. (Amended by FR-DRS-61: an answer that starts
   after the side asked for, as a restarted member's does while it replays, is passed over for one that holds it.)
5. **The recon reads ranges in parallel when it is behind** (`recon.fetch.parallel`, 4): because the numbering is
   dense, four pages after the last side read can be asked at once and joined in order. At the head it asks one.
   Its read cap rises to 2,000,000 sides/s (at 1,000,000 the pacing itself held it under twice the mixed rate). A range is as long as the pages the engine serves, and a range that
   does not follow the one before it is dropped and asked again, so the joined page has no hole the engine did not
   have.

Why not (b) as well: the ring makes the live window nearly free and the full-history replay already exists for the
cases that need re-derivation (the orphan sweep, the audit). **(b) is worth reopening if and only if** the ring's
write is ever measured above about 1% of the service thread, or a defect is found where the live tap and the log
disagree. Nothing else revives it.

Why not (a) alone: it leaves the cost where failover speed depends on it, and leaves the window too short.

## What independence this keeps

The same as before. The engine side is still each member's own applied output, taken at the tap, never the bridge's
copy. Reading from a follower adds a witness: a follower applies the same log, and a recon against it also covers a
leader whose bridge published something its followers never applied.

## Consequences

- No fresh epoch, no format change. Roll: members, gateways and trade-processor in any order. An old gateway
  forwards to member 0 as before; an old trade-processor reads one page at a time, as FR-DRS-56 does.
- A member's heap holds 310 MB more at 8,388,608 sides, allocated once at start, against a 12 GB heap.
- A read's cost moves to the HTTP thread of the member that answers: measured in FR-DRS-56 at 0.35 µs and 961 B a
  side. At 431K sides/s that is about 15% of a core and 414 MB/s of garbage on a follower. Not removed here.
  The cheaper wire (records written straight from the ring, no object per side) is the next step if that shows.
- A restarted member's window is empty until it has replayed; the gateway may forward to it. The recon then sees a
  gap and counts it unverified, as today.
- Correction to FR-DRS-56: it sized `recon.page-limit` against "the member's 12 GB G1 heap". The production render
  runs the members on generational ZGC, so that reason does not hold; 10,000 stays as the measured value.
