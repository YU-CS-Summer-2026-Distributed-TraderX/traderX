# Order-path latency measurement plan

The latency brief's L0: before choosing between core pinning / busy-spin (L1, L2) and less work per order (L3–L5),
split the order round trip into what stays flat across arrival rates (fixed cost) and what grows (queueing), on one
rig in one session. The wire floor comes first, from a bare Aeron ping-pong between nodes, so no software figure is
mistaken for distance. The client figure comes from a constant-arrival probe measured from the intended send time;
the gateway's own `/latency` cannot see an order waiting in its socket, because the binary acceptor answers one order
per session at a time. The segments come from every gateway's and the leader's `/latency` with the nanosecond
cluster clock. Each rate is measured twice in the session (blocks A and B) to get the run-to-run noise, and any
candidate cheap enough to flip on the rig without touching the members is A/B'd in the same session (block C, the
gateways' driver idle strategy).
