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
     on `K.seq` that claims the batch's highest number and carries the inbound number.
   - The compare-and-set is sent only once every body of the batch is stored. A refused body leaves nothing of its
     batch durable: neither its sender numbers nor its inbound numbers. The session recovers as if the gateway had
     died before the batch: the client resends those requests and the engine answers duplicates (ADR-086 §4).
     The first build sent the compare-and-set with the bodies. A refused body then left `K.seq` durable past a
     request whose answer was never stored (codeX review 2026-10-30 §11).
   - Messages that arrive meanwhile wait for that acknowledgement and form the next batch, so the batch grows with
     the load and a session is no longer bound by the round trip.
2. **The fence is unchanged in kind.**
   - Every body is published with `Nats-Expected-Last-Subject-Sequence` set to the `K.seq` revision this gateway
     last wrote, and `Nats-Expected-Last-Subject-Sequence-Subject` set to the `K.seq` subject.
   - The claim is a compare-and-set from that same revision.
   - If anyone else has written the session since, the server refuses both, and the session is dropped as before.
     A body never overwrites a slot behind the fence.
3. **A new owner fences first.** Taking a lease (Logon, fill, retake) rewrites `K.seq` at once, before anything is
   stored, in both modes. A previous owner's bodies still in flight are then refused. If one of its commits lands first, the new
   owner rereads and fences again. A previous owner's unclaimed body that landed before the fence is for a number
   the new owner will store again before it can be sent or resent.
   - **A retake is the continuity check.** A gateway stalled past the TTL that finds its lease free again rewrites
     `K.seq` from the revision it last saw, unchanged. That write succeeds only if nobody served the session
     meanwhile: every owner moves `K.seq`.
   - If the write is refused, the session is not resumed: `confirm()` fails, the connection closes before the
     message reaches the cluster, and the lease is freed for the client's next Logon, which rereads the session.
   - The first retake only checked that the lease was free now. A gateway that took the session, moved its
     numbers and left was invisible to it, so the stalled owner was confirmed with stale numbers. Its next write
     was refused, but only after `fromApp()` had submitted the request to the engine (codeX review 2026-10-30
     §10). That retake predates group commit (ADR-086 §6b) and the synchronous store had the same gap.
   - Every owner moves `K.seq` because every acquisition fences it. The first fence ran only under group commit,
     so a synchronous owner that recorded a sequenced order (the order bucket, not `K.seq`) or only answered
     resends, and left before any number moved, left `K.seq` unchanged, and the stalled owner's retake was
     confirmed over it (codeX review 2026-10-30 §17).
4. **Store-before-send, by a write barrier.**
   - `FixWriteBarrier` sits at the tail of each connection's MINA chain, ahead of the FIX codec and TLS. It holds
     QuickFIX/J's write of message n until the store's committed number passes n, then releases writes in order.
   - A resend (n already committed) passes straight through, unless writes are held ahead of it.
   - QuickFIX/J ends a session with `closeOnFlush`, which flushes MINA's queue but not the barrier. So the close
     waits for what is held, for example the Logout answering the client's.
   - The store decides each write from its own state:
     - **send:** committed, or a write group commit never took, such as a refused Logon's transient Logout;
     - **hold:** taken, its commit still to come;
     - **drop:** taken and never to be committed, because the lease or the store is gone.

     Losing the lease is never a reason to send. A dropped write ends the connection, and nothing after it is sent
     on that connection. The first build let a write through when the lease was cleared between QuickFIX/J's
     `set()` and its write reaching the barrier, before the disconnect: it read "not pending" (codeX review
     2026-10-30 §7, `FixBarrierLossTest`).
   - Every failed commit, fenced or unanswered, releases the lease, so the next Logon here or elsewhere rereads and
     fences.
5. **Commit acknowledgements run on their own thread**, never jnats' reader thread. That thread also delivers the
   reply a lease renewal waits for under a session's lock, and the first build stalled every lease that way.
6. **Checked, not assumed.** On connect the store makes two writes to a probe subject, both fenced on the probe's
   `.seq` record at revision r.
   - Fenced at r, the current revision: it must be accepted. A server that checks the destination subject instead
     refuses it.
   - Fenced at r - 1, stale: it must be refused with wrong-last-sequence (10071). Any other refusal proves nothing.

   Only a server that passes both gets group commit. Any other server gets the synchronous store with an ALERT, as
   does `FIX_STORE_GROUP_COMMIT=false`.
   - The first probe made only the stale write. That passed any API error, and a server that checks the destination
     subject whenever the bucket held messages (codeX review 2026-10-30 §4; `FixFenceProbeTest`).
   - Measured with r = 8: NATS 2.10 accepts both fenced writes (it skips the check when the header names another
     subject), so it falls back. NATS 2.14 accepts the first and refuses the second with 10071. Production runs
     `nats:2.14-alpine`.
7. **Bounds.**
   - A session holds at most `FIX_STORE_MAX_UNCOMMITTED` (256 since FR-GFS-21, 2026-11-10; 1,024 before: each
     waiting body is commit latency, and 600 sessions × 1,024 at one stream was ~2 s) uncommitted bodies, counting the batch in flight
     until its commit completes or fails. The next `set()` waits for room, so a slow store slows the sender as it
     did before. The first build stopped counting a batch once it was sent, so 1,025 could be pending (codeX review
     2026-10-30 §12).
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
  - A server that does not enforce the fence keeps the synchronous store (NATS 2.10, bucket already holding
    messages).
  - A stalled owner whose session another gateway served and left is not confirmed, in both modes (§10; the first
    retake confirmed it at its stale numbers).
  - A refused body leaves none of its batch durable (§11; the first build resumed past the request). The control
    with a body the stream accepts commits the batch.
  - With NATS paused, the 1,025th message waits: the batch in flight counts (§12; the first build took 1,025).
  - All four fail on the first build. codeX's own `ReviewRetakeIT` and its body-refusal case pass now.
  - Synchronously, a stalled owner is not confirmed after another gateway recorded an order and left, nor after
    one only held the session (§17). Both fail on the build where only group commit fenced; codeX's
    `ReviewSyncRetakeIT` passes now (`confirm=false`).
- **`FixBarrierLossTest`:** a taken, uncommitted message is never sent once the lease is gone (fails on the first
  build), nor once its commit failed. Controls: with the lease held it waits and then goes; untaken and committed
  messages pass.
- **`FixSessionPersistenceIT.anAnswerWhoseCommit…`:** NATS paused longer than the commit timeout. The client gets no
  answer and the session ends. On resume, the answer is either resent from the store or its number reused.
- **`FixFenceProbeTest`:** with a simulated server, the probe trusts only one that checks the named subject. The
  case from the review, a server that checks the destination subject on a busy bucket, returned true with the first
  probe. A stale write refused with another error (10077) proves nothing.
- **`FixSessionPersistenceIT.anAnswerReaches…`:** without the barrier, the answer arrives while NATS is paused.
- **`FixSessionStoreBoundIT`:** passes. Its full-bucket case now uses a 512 KiB bucket, larger than one batch.

Waiting for the bodies before the compare-and-set (§11) adds a round trip per batch, not per message. Measured with
the same harness and the same box, alternating builds:

| | sends the compare-and-set with the bodies | waits for the bodies |
| --- | --- | --- |
| gateway end to end, execution reports/s | 99,549 / 96,863 | 97,903 / 108,090 |
| store alone, orders/s | 126,040 / 91,524 | 174,473 / 155,373 |

Batches grow by the extra round trip, so the store alone gains.
