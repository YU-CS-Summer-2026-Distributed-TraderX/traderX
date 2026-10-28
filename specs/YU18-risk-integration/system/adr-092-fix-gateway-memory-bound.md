# ADR-092: A FIX session holds a bounded count of messages in the gateway, however long it runs

Status: accepted 2026-10-28 (coordinator queue, approved by yaakov: "fix it"). Pack:
[FIX gateway memory](../maintenance/fix-gateway-memory/spec.md) (FR-FGM-01..04). Builds on ADR-084 and ADR-086.

## Context

f15ho (the 1 h FIX arm at 400K/s on tip `41a77949`, ~22K orders/s per gateway, 64 sessions each, in-memory
sessions) filled the gateways' 768 MiB gen-ZGC heaps at about 25 minutes: 14,624 allocation stalls on QF/J
session dispatcher threads, then readiness failed on 4 gateways. Binary gateways stayed at 10–20 % over the
same hour.

It was not QuickFIX/J's message store. With in-memory sessions `PersistMessages=N`, so nothing is stored for
resend, and every map the acceptor keeps (`refByClOrdId`, `tracked`, the early-fill ring) is already capped.
A local load on the real gateway JVM (in-process 3-member cluster, `jcmd GC.class_histogram` every 20 s) found two
places where a session's client, not the gateway, decides how much heap the gateway uses:

1. **Unread answers (no limit).** QuickFIX/J hands every ExecutionReport to MINA, whose per-connection write queue
   has no limit. A client that reads slower than it is answered leaves them queued: about 690 bytes each
   (`DefaultWriteRequest`, `DefaultWriteFuture`, `HeapByteBuffer`, `byte[]`). Measured: 16 sessions, live heap
   +55 MB over 112K unread answers, growing linearly. Only a client that reads nothing at all is cut off, by MINA's
   60 s write timeout; one that keeps reading, just too slowly, never is. The hxb loadgen is open-loop and
   parses FIX on one event loop. Falling ~2 % behind at 22K reports/s per gateway is about 300 KB/s, which is
   the order of what filled f15ho's heap in 25 minutes. f15ho kept GC logs, not a histogram, so they cannot say
   which of these two filled it; both are bounded below.
2. **Unanswered orders (bounded above the heap).** Each session has an inbound queue in front of its dispatcher.
   QuickFIX/J's default holds 10,000 parsed messages (~2.5 KB each), so 64 sessions can hold ~1.6 GB. A session is
   answered in order, one ack at a time, so an open-loop client offering faster than 1/ack-latency fills it. When
   it is full, the put blocks a MINA I/O thread that other sessions share.

On the ADR-086 (JetStream) path, a gateway taking a session's lease read the whole FIX day's outbound log into a
list and kept every order's last report parsed. That is proportional to the session's length, at the moment
another gateway dies.

## Decision

1. **A slow consumer is dropped, not buffered** (FR-FGM-01). `MaxScheduledWriteRequests` = `FIX_MAX_QUEUED_WRITES`
   (default 2,048, ~1.4 MB). The next report beyond it ends the session ("Slow consumer", ERROR in the gateway log).
   QuickFIX/J closes on flush: the queued reports stay readable until the client drains them, or until MINA's 60 s
   write timeout. A reconnecting client therefore holds at most two connections' worth for 60 s.
2. **Inbound is flow-controlled by TCP** (FR-FGM-02). Watermarks replace the fixed capacity: above
   `FIX_INBOUND_QUEUE` (default 1,024) queued messages the gateway suspends reading that session's socket, and it
   resumes at a quarter. The client's own TCP send blocks, so an open-loop client sees its lag as latency. No
   shared I/O thread blocks, and no order is lost: everything read is answered.
3. **Adoption streams** (FR-FGM-03). The stored log is read one message at a time. Only the session's open orders
   are kept, at most `TRACKED_CAPACITY` (65,536), as `tracked` itself is.
4. **The bound, stated** (FR-FGM-04):

   heap ≤ ~30 MB fixed (two 65,536-entry maps and a 4,096-entry ring) + ~5 MB × connected sessions

   - Per connected session: 1,024 + one 64 KiB read (~400) inbound messages × 2.5 KB, plus 2,048 unread reports × 0.7 KB.
   - Example: 64 sessions ≈ 350 MB, within a 768 MiB heap.
   - Neither term depends on how long a session has run, how many orders it has sent, or the FIX day.
   - The 17:00 reset changes nothing: no term grows between resets.

## Consequences

- **Resend semantics are unchanged.**
  - With ADR-086 sessions, a report is stored before it is sent (`Session.sendRaw`), so a report lost to a
    slow-consumer drop is resent on the client's ResendRequest after it logs back on.
  - In-memory sessions reset on Logon, as before (TD-AC01). Their client reconciles with OrderStatusRequest or
    OrderMassStatusRequest.
  - ADR-091's exactly-once client resends un-acked orders with the same ClOrdID after any disconnect. A drop
    is one of those disconnects.
- **A client that cannot keep up now loses its session,** and the gateway keeps its memory. Raise
  `FIX_MAX_QUEUED_WRITES` only together with the gateway heap (0.7 KB × sessions per step).
- **TCP backpressure moves lag into the client.** The gateway's own latency metrics no longer show it, so read
  client-observed latency for a FIX arm, as the LATENCY-03 finding already requires.
- **Excluded:** ADR-090's per-FIX-day order index (`FIX_EXACTLY_ONCE=jetstream`, opt-in). It keeps every stored
  order's sequence and raw message in the store's heap until the day resets. It belongs to ADR-090 and needs its
  own bound before that mode runs at rate.
