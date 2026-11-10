# RI35: the binary acceptor serialises a session, so throughput is sessions ÷ cluster RTT

Opened 2026-11-09 (trade-history-tiers lane, the GKE batching campaign, RI34 §1b). Status: open. The hour at 400K ran with 512 sessions as the workaround.

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
ceiling" of 185–300K was this. FIX sessions go through `FixGatewayAcceptor`; check whether it has the same shape.

## Fix

Pipeline the acceptor: decode and submit without waiting; complete acks in submit order per connection from the
owner thread's egress (the `PendingOrder`/future already exists for the FIX path's pipelined submit). Keep the
request/response order on the wire per connection (the binary protocol's clients match acks by clOrdId, so
out-of-order completion is also possible if the spec allows it: decide in the spec). Prove with the 128-session load
reaching the 512-session rate, and `/latency` `queue` staying flat.

## Until then

Size the load's sessions: at a 0.5–0.7 ms cluster RTT, 400K orders/s needs about 512 binary sessions.
