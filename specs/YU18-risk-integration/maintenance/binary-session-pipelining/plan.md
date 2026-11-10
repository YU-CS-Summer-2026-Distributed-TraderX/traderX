# Binary session pipelining plan

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
