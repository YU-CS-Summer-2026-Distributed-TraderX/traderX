# Session pipelining plan (binary and FIX)

1. An asynchronous form of the gateway's pipelined submit on the seam: the window slot and the owner-queue entry
   are taken on the caller's thread (arrival order per connection), the answer completes on an executor the caller
   names (the connection's writer), with the synchronous path's budget, task retirement, reject log, root span and
   wedge accounting. Defaults on `OrderSubmitter` fall back to the synchronous call.
2. The acceptor: a semaphore per connection taken before a frame is read and returned after its ack is written; a
   writer thread per connection that runs the completions and writes the acks, flushing once per burst.
3. On EOF or a read error, wait for the connection's answers before stopping the writer and closing.
4. Unit tests with a submitter that answers late and out of order; kind proof at 16 and 64 sessions on one
   partition, the pre-fix gateway at 16 as the control.

A first cut ran each frame's synchronous submit on a virtual thread (no seam change). It was dropped: a
connection's virtual threads race into the owner queue, so a sequenced stream could reach the log out of order.

FIX (FR-BSP-02):

5. A String-keyed `submitOrderAsync` (typed or not) on the seam, called from `fromApp` on QuickFIX/J's session
   thread, so arrival order holds per session. A semaphore per session (half `FIX_MAX_QUEUED_WRITES`) taken before the
   submit; one report thread per session runs `answer` (reports, ClOrdID map, fill tracking) and FR-XOC-06's
   resubmission. F, G, H and AF wait for the in-flight order they name. Exactly-once sessions, cancels and replaces stay
   synchronous.
6. The inbound sequence: QuickFIX/J increments the target after `fromApp` returns (`Session.next`: `verify` then
   `incrNextTargetMsgSeqNum`, 2.3.1 bytecode). With ADR-086's store that would store a number past an undecided
   order, and a crash would lose the resend that answers it. The acceptor tells the store the lowest undecided
   MsgSeqNum and `K.seq` stores no target above it (FR-GFS-19); when it rises the store commits it at once.
7. Unit tests with a submitter that holds every order (bound, arrival order, completion-order answers, ambiguous,
   cancel of an order in flight), an IT for the held target in both store modes; kind proof at 4, 16 and 64 sessions,
   the pre-fix gateway at 4 as the control.
