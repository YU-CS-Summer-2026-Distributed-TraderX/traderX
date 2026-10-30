# ADR-099: The FIX session store commits in groups, and a write barrier keeps store-before-send

Status: accepted 2026-10-30 (coordinator queue: "design and build a faster store that keeps the guarantees ADR-086
proved"). Pack: [FIX session persistence](../maintenance/fix-session-persistence/spec.md) (FR-GFS-18). Amends
ADR-086 §1–§2; keeps §2's fence, §3's store-before-send, §6a's bound and §6b's lease.

## Context

ADR-086 stored every outbound message with a compare-and-set on the session's `K.seq` (the claim), then a put of the
body, and moved the inbound number with another compare-and-set: three synchronous JetStream round trips per
order, under the session's lock. QuickFIX/J sends one message per session at a time, so a session's rate is one
over that latency, and a gateway's is the session count over it. FIX stalled at about 29K/s per gateway on GKE.

Measured locally (one NATS 2.14 server in Docker; `FixStoreThroughputIT`, `FixGatewayThroughputIT`):

| | sessions | per gateway |
| --- | --- | --- |
| store alone, synchronous | 1 | 2,183 orders/s (458 µs per order: ~150 µs per round trip × 3) |
| store alone, synchronous | 33 | 23,948 orders/s |
| store alone, synchronous | 100 | 36,750 orders/s: about 110K synchronous writes/s is the most one connection drove |
| gateway end to end, synchronous | 33 | 16,587 execution reports/s |

Both limits are round trips: per session the latency of three, per gateway how many synchronous writes one
connection completes.

## Decision

1. **Group commit per session.**
   - `set()` publishes the body asynchronously and returns.
   - The inbound number moves in memory.
   - While no commit is in flight, everything pending goes out as one batch: the bodies, then one compare-and-set
     on `K.seq` that claims the batch's highest number and carries the inbound number. One acknowledgement
     completes it.
   - Messages that arrive meanwhile wait for that acknowledgement and form the next batch, so the batch grows with
     the load and a session is no longer bound by the round trip.
2. **The fence is unchanged in kind.**
   - Every body is published with `Nats-Expected-Last-Subject-Sequence` set to the `K.seq` revision this gateway
     last wrote, and `Nats-Expected-Last-Subject-Sequence-Subject` set to the `K.seq` subject.
   - The claim is a compare-and-set from that same revision.
   - If anyone else has written the session since, the server refuses both, and the session is dropped as before.
     A body never overwrites a slot behind the fence.
3. **A new owner fences first.** Taking a lease (Logon, fill, retake) rewrites `K.seq` at once, before anything is
   stored. A previous owner's bodies still in flight are then refused. If one of its commits lands first, the new
   owner rereads and fences again. A previous owner's unclaimed body that landed before the fence is for a number
   the new owner will store again before it can be sent or resent.
4. **Store-before-send, by a write barrier.**
   - `FixWriteBarrier` sits at the tail of each connection's MINA chain, ahead of the FIX codec and TLS. It holds
     QuickFIX/J's write of message n until the store's committed number passes n, then releases writes in order.
   - A resend (n already committed) passes straight through, unless writes are held ahead of it.
   - QuickFIX/J ends a session with `closeOnFlush`, which flushes MINA's queue but not the barrier. So the close
     waits for what is held, for example the Logout answering the client's.
   - If the store fails, everything held is dropped, never sent.
5. **Commit acknowledgements run on their own thread**, never jnats' reader thread. That thread also delivers the
   reply a lease renewal waits for under a session's lock, and the first build stalled every lease that way.
6. **Checked, not assumed.**
   - On connect the store writes a probe body fenced on a stale sequence. Only if the server refuses it is group
     commit on.
   - NATS 2.10 accepts it, as `FixStoreGroupCommitIT` shows, so a 2.10 server gets the synchronous store and an
     ALERT. Production runs `nats:2.14-alpine`.
   - `FIX_STORE_GROUP_COMMIT=false` also keeps the synchronous store.
7. **Bounds.**
   - A session holds at most `FIX_STORE_MAX_UNCOMMITTED` (1,024) uncommitted bodies. The next `set()` waits for
     room, so a slow store slows the sender as it did before.
   - A commit not acknowledged in 5 s drops the session, as a failed synchronous write did.
   - Resets, sequence resets and resend reads first wait for every pending commit, then write synchronously as
     before.

## What is kept, and what moves

- **Kept:**
  - Any-replica resume, the lease, the fence, the resend window, the reset Logon and the takeover times.
    `FixSessionPersistenceIT` passes unchanged: all its cases, plus FR-GFS-17's.
  - Nothing is sent before it is stored. `anAnswerReachesTheClientOnlyOnceItIsStored` pauses NATS, and no
    answer arrives until it resumes.
- **The inbound number is durable with the next commit,** typically a millisecond later, not before QuickFIX/J
  goes on. A gateway that dies in between resumes one or more numbers short. The client resends those messages and
  the engine answers each duplicate ClOrdID as a duplicate (ADR-086 §4), as it already did for an order in flight
  at a crash.
- **One batch must fit in the bucket.** At most 1,024 bodies of ~300 B is ~300 KB, against a 1 GiB bucket sized at
  sessions × window (§6a). A bucket smaller than one batch would evict the session's own `K.seq` before the claim
  lands.
- **Heap:** the uncommitted bodies plus the writes the barrier holds come to at most ~0.6 MB per session. That is
  on top of ADR-092's ~2 MB per session.
- **ADR-090's order records** (`FIX_EXACTLY_ONCE=jetstream`, opt-in) are still written synchronously, one per
  sequenced order.

## Evidence

Measured locally, the same build, 33 sessions, three runs each, on a shared Mac (load average 3–9):

| | synchronous (before) | group commit (after) |
| --- | --- | --- |
| gateway end to end, execution reports/s | 16,587 / 16,117 / 20,857 | 101,479 / 114,608 / 81,543 |
| store alone, orders/s | 23,916 / 23,948 / 21,815 | 139,769 / 132,447 / 120,928 |

That is about 6× per gateway. Not measured on GKE.

Tests:
- **`FixStoreGroupCommitIT`:**
  - A replaced owner's late write never overwrites the new owner's message. Without the cross-subject header, it
    does.
  - A server that does not enforce the fence keeps the synchronous store.
- **`FixSessionPersistenceIT.anAnswerReaches…`:** without the barrier, the answer arrives while NATS is paused.
- **`FixSessionStoreBoundIT`:** passes. Its full-bucket case now uses a 512 KiB bucket, larger than one batch.
