# ADR-092: A FIX session holds a bounded count of messages in the gateway, however long it runs

Status: accepted 2026-10-28 (coordinator queue; yaakov chose "1+2": bound the inbound path with backpressure, and
fix why gateways fall behind, starting with the fill-feed retry loop). Pack:
[FIX gateway memory](../maintenance/fix-gateway-memory/spec.md) (FR-FGM-01..05). Builds on ADR-084 and ADR-086.

## Context

**f15ho.** The 1 h FIX arm at 400K/s on tip `41a77949`: 18 gateways, 64 in-memory sessions each, ~22K orders/s per
gateway, gen-ZGC 768 MiB heaps.
- Heap after GC sat flat at 300–360 MB for 20+ minutes.
- From +24 min the leader's applied rate fell: 400K → 334K → 311K → 26K/s. Member queue depth stayed 0: ingress
  dropped first.
- 4 of the 18 gateways (1, 6, 9, 10) then jumped to 768 MiB, with 14,624 allocation stalls on QF/J dispatcher
  threads, and readiness failed.
- Binary gateways stayed at 10–20 % over the same hour.

It was not QuickFIX/J's message store: in-memory sessions run `PersistMessages=N`, and every map the acceptor keeps
(`refByClOrdId`, `tracked`, the early-fill ring) is capped. A local load on the real gateway JVM (in-process 3-member
cluster, `jcmd GC.class_histogram` every 20 s) found two places where the client, not the gateway, decides the heap:

1. **Unanswered orders.** Each session has an inbound queue in front of its dispatcher. QuickFIX/J's default holds
   10,000 parsed messages (~2.5 KB each), so 64 sessions can hold ~1.6 GB.
   - A session is answered in order, one ack at a time, so an open-loop client offering faster than 1/ack-latency
     fills its queue. f15ho's client is open-loop.
   - This matches f15ho: the gateways that filled were the ones whose ingress had fallen behind.
2. **Unread answers.** MINA's per-connection write queue has no limit. A client that keeps reading, but slower
   than it is answered, leaves reports queued: ~690 bytes each (`DefaultWriteRequest`, `HeapByteBuffer`, `byte[]`).
   - Measured: +55 MB over 112K unread answers, linear.
   - Only a client that reads nothing at all is cut off, by MINA's 60 s write timeout.

On the ADR-086 (JetStream) path, a gateway taking a session's lease read the whole FIX day's outbound log into a
list. That costs heap proportional to the session's length, at the moment another gateway dies.

**The fill feed** (ADR-084) retried `nats://nats:4222` every 2 s for the whole hour, because f15ho ran no NATS.
jnats logged two SEVERE lines per attempt: 900–2,200 per gateway. Measured locally, 100 failed attempts took 110 ms
in total, left no thread and no retained heap. It was noise, not load. Why the 4 gateways fell behind at +24 min is
not established by f15ho's evidence (GC logs and control samples only).

## Decision

1. **A slow consumer is dropped, not buffered** (FR-FGM-01).
   - `MaxScheduledWriteRequests` = `FIX_MAX_QUEUED_WRITES` (default 2,048, ~1.4 MB). The next report beyond it ends
     the session ("Slow consumer", ERROR in the gateway log).
   - QuickFIX/J closes on flush: the queued reports stay readable until the client drains them, or until MINA's
     60 s write timeout.
2. **Inbound is a bounded queue per session** (FR-FGM-02).
   - `FIX_INBOUND_QUEUE` (default 256, ~0.64 MB): QuickFIX/J's queue capacity in place of its 10,000.
   - When it is full, the MINA I/O thread reading that session waits for room. TCP then holds the client back:
     nothing is dropped, everything read is answered.
   - Sessions that share that I/O thread (MINA runs cores + 1 of them) pause reading with it until it drains. On
     a gateway they fall behind together anyway, because they share one cluster ack path.
   - **Not QuickFIX/J's queue watermarks.** Their `resumeRead()` runs on the dispatcher thread, while MINA 2.1.4
     updates `SelectionKey.interestOps` with an unsynchronised read-modify-write. A stale OP_WRITE update from the
     I/O thread can land after the resume and clear OP_READ, so "socket reads resumed" is logged and nothing is read
     again. Seen locally in 1 of 3 runs at watermarks 64/256. The bounded queue never touches interest ops: 5 of 5
     clean.
3. **Adoption streams** (FR-FGM-03). The stored log is read one message at a time. Only the session's open orders
   are kept, at most `TRACKED_CAPACITY` (65,536), as `tracked` itself is.
4. **The bound, stated** (FR-FGM-04):

   FIX heap ≤ ~100 MB fixed + ~2 MB × connected sessions

   - Fixed: two 65,536-entry maps and a 4,096-entry ring (~30 MB), plus up to 64 MB of fill-feed messages buffered
     by jnats's default pending limit.
   - Per session: 256 × 2.5 KB inbound plus 2,048 × 0.7 KB unread.
   - Example: 64 sessions ≈ 230 MB.
   - Neither term depends on how long a session has run, how many orders it has sent, or the FIX day. The 17:00
     reset changes nothing: no term grows between resets.
5. **The fill feed backs off** (FR-FGM-05).
   - Retries go from 1 s to 60 s, with one WARN for the outage, `traderx_fix_fill_feed_connect_failures_total`,
     and `traderx_fix_fill_feed_subscribed`.
   - jnats's per-attempt logging is silent until the feed first subscribes, then logs as before.

## Consequences

- **Resend semantics are unchanged.**
  - With ADR-086 sessions, a report is stored before it is sent (`Session.sendRaw`), so a report lost to a
    slow-consumer drop is resent on the client's ResendRequest after it logs back on.
  - In-memory sessions reset on Logon, as before (TD-AC01). Their client reconciles with OrderStatusRequest or
    OrderMassStatusRequest.
  - ADR-091's exactly-once client resends un-acked orders with the same ClOrdID after any disconnect. A drop is
    one of those disconnects.
- **A client that cannot keep up loses its session,** and the gateway keeps its memory. Raise
  `FIX_MAX_QUEUED_WRITES` only together with the heap.
- **Backpressure moves lag into the client and into sibling sessions on the same I/O thread.** The gateway's own
  latency metrics do not show it, so read client-observed latency for a FIX arm.
- **Excluded:** ADR-090's per-FIX-day order index (`FIX_EXACTLY_ONCE=jetstream`, opt-in). As first built, it kept
  every stored order's sequence and raw message in the store's heap until the day reset. ADR-090 §4a
  (FR-XOD-13) now bounds it: a window of ClOrdIDs, plus the requests the engine has not answered.
