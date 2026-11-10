# RI35: the binary acceptor serialises a session, so throughput is sessions ÷ cluster RTT

Opened 2026-11-09 (trade-history-tiers lane, the GKE batching campaign, RI34 §1b). Status: **binary half fixed on `binary-acceptor-pipelining` (FR-BSP-01), unit tests and kind, 2026-11-10; not on GKE or production; FIX half open.** NFR-BSP-01's rate holds on kind; its `queue` p99 < 50 µs does not on kind, for the pre-fix gateway either (below). The hour at 400K ran with 512 sessions as the workaround.

## What

`BinaryGatewayAcceptor` runs one thread per TCP connection: `readFully` a frame, `dispatch` it (which returns only
with the committed ack in hand, ~540 µs p50 at 300K), write the ack, read the next frame. A client's pipelining is
therefore queued in TCP: a session carries one order at a time and the gateway's throughput is the number of
sessions divided by the cluster round trip, whatever the gateway count or its CPU.

Measured on GKE (24 gateways, 3 members c4d-standard-8): 128 sessions 285–300K orders/s (the same with 18 gateways;
nothing CPU-bound; every load session at its in-flight cap; `GET /latency` p50 residence 553 µs of which `cluster`
541 µs and `queue` 0.45 µs); 256 sessions 344–374K; 512 sessions 400–405K inside the sending windows, with the
leader then at 6.6 of 7 cores. Evidence: shared notes `bridge-batching-gke-20261109/run4-24gw`, `run6-hyperdisk`,
`run7-256sessions`, `run8-512sessions`.

## Why it matters

A client with a few sessions cannot reach the engine's rate however hard it pipelines; the earlier campaigns' "member
ceiling" of 185–300K was this. FIX sessions have the same shape: QuickFIX/J's `fromApp` runs on one dispatcher thread per session and calls
`submitOrder` synchronously (`FixGatewayAcceptor.java:676`), so a FIX session also carries one order at a time.
The plan: issues/risk-integration/tasks/prod-450k-plan-20261110.md §3.

## Fix

Pipeline the acceptor: decode and submit without waiting; complete acks in submit order per connection from the
owner thread's egress (the `PendingOrder`/future already exists for the FIX path's pipelined submit). Keep the
request/response order on the wire per connection (the binary protocol's clients match acks by clOrdId, so
out-of-order completion is also possible if the spec allows it: decide in the spec). Prove with the 128-session load
reaching the 512-session rate, and `/latency` `queue` staying flat.

## Until then

Size the load's sessions: at a 0.5–0.7 ms cluster RTT, 400K orders/s needs about 512 binary sessions.

## Binary half: pipelined (2026-11-10, kind)

[FR-BSP-01](../../specs/YU18-risk-integration/maintenance/binary-session-pipelining/spec.md). The connection thread
decodes each frame and calls `OrderSubmitter.submitOrderAsync` (or the sequenced, cancel or replace form), which takes
the window slot and queues the order for the owner thread before it returns, so a connection's orders reach the log in
the order they arrived. One writer thread per connection runs the completion (reject log, root span, wedge accounting:
`ClusterGatewayMain.submitPipelinedAsync`, the synchronous path's pieces extracted unchanged) and writes the ack. At
most 4,096 frames per connection are read and not yet acked; past that the connection stops reading. Acks may leave
out of order; the clOrdId correlates (binary-load.mjs and bin-multi.mjs already match by it). A first cut that ran
each frame's synchronous submit on a virtual thread was dropped: a connection's virtual threads race into the owner
queue, so a sequenced stream could reach the log out of order and be refused as a gap.

**kind proof** (`scripts/proofs/kind-binary-pipelining.sh`, one partition, one gateway, `kind-yu18-rig.py --history
--binary --throughput`, one arm per fresh rig, every arm offered 15,000 orders/s with 2,048 in flight in total, 70 s;
rate = the gateway's accepted counter, median of 5 s windows inside the sending windows). New = `traderx/cluster-node:bsp`
(this branch), old = `:bat` (eeedef16, the same order-matcher source without FR-BSP-01). Every arm: the load ended clean
(0 rejected, 0 ambiguous, 0 unanswered, book balanced), G3h ok over every side it booked.

| Sessions | Gateway | Orders/s | queue p50 / p99 (µs) | cluster p50 (ms) | total p50 / p99 (ms) | Accepted |
|---|---|---|---|---|---|---|
| 4 | old | 3,349 | 5.8 / 90 | 0.71 | 0.77 / 9.2 | 196,228 |
| 4 | **new** | **14,778** | 5.0 / 280 | 1.71 | 1.82 / 90.6 | 873,194 |
| 16 | old | 13,332 | 1.2 / 84 | 0.71 | 0.78 / 14.6 | 724,862 |
| 16 | **new** | **14,573** | 1.4 / 359 | 1.78 | 1.96 / 128.3 | 863,882 |
| 64 | old | 14,181 | 1.3 / 272 | 0.99 | 1.09 / 23.8 | 776,496 |
| 64 | **new** | **14,294** | 1.2 / 211 | 1.47 | 1.60 / 123.1 | 846,646 |
| 16 at 5,000/s | new | 4,903 | 1.4 / 160 | 1.29 | 1.38 / 38.5 | 286,346 |

16 sessions reach the 64-session rate (14,573 vs 14,294/s, both at the offer). On kind the cluster round trip is
~0.7 ms, so 16 serialised sessions already carry ~13K: the discriminating control is 4 sessions, where the old gateway
is held to 3,349/s (~1.2 ms per order per session, the client's wire included) and the new one takes the whole offer (4.4×). The new gateway's
higher `cluster` p50 is Little's law: more orders in flight per session wait in the same pipeline.

`queue` p99 is 160–359 µs on the new gateway against the 50 µs target; p50 stays at 1.2–1.4 µs (5 µs at 4 sessions).
The old gateway misses the target too at the same rate: 272 µs at 64 sessions, where it never has more than 64 orders
in flight. So the tail is this rig's owner thread at ~14K/s (four CPUs shared with the connection, writer, Aeron and
egress threads, not pinned), not the pipelining depth. A third of the rate (5,000/s) still read 160 µs. Measure it on
GKE, where the gateway is a pinned guaranteed pod (FR-PRG-06), before calling NFR-BSP-01's `queue` half met or missed.

**Found on the way** (each made the first runs fail, and the script now refuses or records it):
- Member egress is best-effort (`offerEgress`/`flushCoalesced`: 20 attempts, then the ack is dropped, uncounted).
  Without egress coalescing one pipelined gateway session's ~14K acks/s overran it: 7 of 720,819 acks dropped, each
  reaped after 12 s and answered ambiguous (correctly). Production renders coalescing only with
  `prepare.py --throughput-members` (FR-PRG-15): a pipelined gateway needs it on, and the members have no drop counter.
- kind's gateway at a 1-CPU limit was CFS-throttled in 470 of 2,803 periods (queue p99 22.7 ms).
  `kind-yu18-rig.py --throughput` now gives it production's 4 guaranteed CPUs.
- kind's NATS (768Mi, file store, no `GOMEMLIMIT`) was OOM-killed at ~29K orders/s, and again ~1.3M orders into a
  second arm at 15K/s. With NATS down the gateway's wedge detector fired repeatedly and rebuilt the session, which
  drained the window as ambiguous. That is the downstream back-pressuring consensus, outside this change.

Evidence: shared notes `binary-pipelining-kind-20261110/` (per-arm load logs, 1 s samples, `/latency` dumps, G3h,
and the three superseded runs). kind cluster `traderx-bsp` deleted after the runs.

**For the FIX half.** Use the same seam: an asynchronous `submitOrder`/`submitTypedOrder`/`submitReplace` taking the
String clOrdId (not yet on `OrderSubmitter`), called from `fromApp` on QuickFIX/J's session thread, so arrival order
holds per session (FIX_SEQUENCED's stream is safe for the same reason the binary sequenced NEW is). The
ExecutionReport is sent from the completion executor; give each session one, so reports for one session are not sent
concurrently (sequence numbers are assigned at send, so ordering by completion is fine; interleaving is not).
`fromApp` returning before the commit means QuickFIX/J advances its inbound sequence before the order is decided:
check that against FR-XOD and the session store (ADR-086) before turning exactly-once on. Bound each session's
in-flight count as the binary connection does, by blocking `fromApp` (QuickFIX/J then stops reading that session).
Run FIX with egress coalescing on (above).

