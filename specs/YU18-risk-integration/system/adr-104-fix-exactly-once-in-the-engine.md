# ADR-104: FIX exactly-once in the engine: a ClOrdID ring per FIX session-day

Status: approved by yaakov 2026-11-10 (engine-side, relayed by the integration coordinator); implemented on
`exactly-once-production` (engine, FIX gateway, production render). Not deployed: a format-15 core rolls only through a snapshot
barrier and a whole-cluster restart (ADR-090 §3), and the snapshot pause at 400K/s is measured on GKE first
(NFR-XOD-04). Requirements: [exactly-once dedupe pack](../maintenance/exactly-once-dedupe/spec.md), FR-XOD-19..25 and
NFR-XOD-04; production render FR-PRG-46. Base: ADR-090 (the binary stream gate this sits beside), YU03 FR-IMRG14, YU10 FR-FIX10, FR-FFR-18 (the
pipelined FIX path).

## Context

FIX order entry deduplicates on the FR-IMRG14 key table: global, 256Ki keys, FIFO. It is a count window, ~1.7 s at
150K orders/s and ~0.6 s at 450K. Acceptance run 3 lost one gateway for 47 s; on reconnect one session resent
1,464 orders with PossResend=Y, long after the table had forgotten them, and the store over-counted by the double
bookings that implies.

ADR-090 FR-XOD-12/13 close this for FIX by storing each order in JetStream before it is submitted. That write sits
on the order path, so a FIX session has one order in flight: measured locally (FixGatewayThroughputIT, 33 sessions)
the store path answers 1.7–5.2× fewer orders per second than the pipelined path (FR-FFR-18), and every order is a
NATS write the 450K capture cluster (ADR-103) would also carry.

## Decision

1. **The engine keeps, per FIX session-day, the last N ClOrdIDs it was sent** (`SessionRing`, beside the ADR-090
   stream gate). The gateway tags each FIX NEW with its session stream (FNV-1a 64 of the session key and the FIX-day
   creation time, so a day's reset starts a new ring), a 32-bit check of the ClOrdID independent of the 64-bit key,
   its MsgSeqNum, and PossDup (43) / PossResend (97). A NEW whose key and check the ring holds is the original: its
   ref and state are re-emitted, not bridged (FR-XOD-03), and it books nothing. Nothing is written outside the log,
   so there is no extra hop, no NATS write, and FIX stays pipelined.
2. **A collision never refuses a real order.** Same key, different check is a different ClOrdID: it books, and both
   entries stay indexed, each found by its key and check together. Key and check both colliding is ~2^-96 per pair.
3. **Safety does not depend on N; availability does.** Once a session's ring has evicted, a NEW marked PossDup or
   PossResend that misses it may be an evicted original, so it is refused `SESSION_AGED_OUT` and never booked. One
   exception: a PossDup (43=Y, a FIX-level resend that keeps its MsgSeqNum) above every evicted MsgSeqNum was sent
   after the evicted orders and is new to the engine, so it books. A sequence reset only lowers MsgSeqNum, which
   can make the exception miss and refuse, never book twice. N = `EXACTLY_ONCE_SESSION_RING`, default 2,048, twice
   the gateway's per-session in-flight cap (FIX_MAX_QUEUED_WRITES/2 = 1,024): a resend of everything a session can
   have outstanding is answered, not refused.
4. **Bounded state, cluster time only.** At most `EXACTLY_ONCE_SESSION_STREAMS` (1,024) sessions; a new one at a full
   table evicts the least-recently-used session idle ≥ `EXACTLY_ONCE_STREAM_IDLE_MS`, else is refused
   `STREAM_LIMIT`. Every decision reads the log timestamp only (FR-XOD-08). A session's arrays are allocated when
   it is first seen and reused when it is evicted: ~60 KB per session (2,048 entries × 21 bytes plus a 4,096-slot
   index), ~60 MB for a full table.
5. **Snapshot format 15.** `T_SESSION_RING` records, one per 256 entries (~6 KB, so they go unpacked over the
   FR-XOD-18 reassembly path). Formats 9–14 load with empty rings; a snapshot written with another N is refused, as
   ADR-090 refuses another W. A worst-case full table is 48 MiB of snapshot; the production shape (tens of
   sessions) is ~1.6 MB. NFR-XOD-04 measures the pause.
6. **Roll order: members before gateways.** The session-keyed flags (4, 8, 16) are unknown to a pre-ADR-104 member,
   which refuses the message, so the gateway turns them on (`FIX_EXACTLY_ONCE=engine`) only in front of format-15
   members. A gateway in front of an old member fails safe: its FIX orders are refused, none books twice.
7. **The gateway makes a collision zero (FR-XOD-25).** A ring answer naming an order the gateway holds under another
   ClOrdID is resubmitted once under a salted key. Its map is the ADR-086 one, rebuilt from stored reports when a
   replica takes the session, so the check holds across a failover for today's orders.

## Consequences

- FIX exactly-once costs no latency and no NATS capacity; FR-XOD-12/13's JetStream path stops being the production
  path once the gateway part lands.
- A resend deeper than N after an eviction is refused, not answered: the client sees `EXACTLY_ONCE_AGED_OUT` and
  must reconcile by OrderStatusRequest (FR-XOD-17), as with FR-XOD-13 today.
- A logon with ResetSeqNumFlag (141=Y) resets the ADR-086 store, which moves its creation time and so starts a new
  ring, as FR-XOD-12's reset purges its rings. An app-level resend of an earlier order after a mid-day reset is not
  caught. The rehearsal load sets 141=Y on its first logon only; a production client that resets on reconnect has
  this gap today too.
- The binary path is unchanged (ADR-090); a NEW carries either the stream tail or the session tail, never both.

## Evidence

`SessionRingTest`: run 3's case (1,464 resends after the key table turns over and 47 s pass) books once keyed and
twice on today's path; the aged rule and the MsgSeqNum frontier at N = 2,048; collision; LRU; snapshot round trip
plus a common tail; two members fed one log write the same bytes; format 14 loads empty; another N is refused; the
codec's flag/tail rules and the pre-ADR-104 refusal. With the hook disabled, run 3 books 2,928 sides instead of
1,464, and the aged and snapshot cases fail. `FixSessionRingGatewayTest`: the tail on the wire, the restatement, the
35=j, the salted resubmit, and N ≥ 2 × `MAX_IN_FLIGHT`. `test_prepare`: the render and both validate refusals.
