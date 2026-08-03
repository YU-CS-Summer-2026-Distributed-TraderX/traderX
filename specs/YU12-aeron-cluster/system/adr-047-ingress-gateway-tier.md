# ADR-047: A stateless-forward gateway tier terminates FIX/REST and follows the leader

Status: Accepted

## Context

Aeron Cluster clients speak the cluster's own protocol; counterparties speak FIX 4.4 and REST.
In the parent state the QuickFIX/J acceptor runs in-process with the BLP, so a counterparty's
FIX session dies with the pod — reconnect, re-logon, and sequence renegotiation cost seconds on
every failover regardless of election speed. Internal election time and client-observed failover
are different measurements, and the acceptance gate here is client-observed.

## Decision

A `fix-gateway` tier terminates counterparty FIX sessions and REST connections and forwards
order flow through the Aeron Cluster client, which routes to the current leader natively. On
leader change the gateway re-points internally while the counterparty session stays connected.
The tier is stateless-forward: FIX session state lives on the gateway instance, warm connections
to all members back the REST path, and the same tier is the fast routing layer that flips on the
leader signal.

## Consequences

Client-observed failover is bounded by Raft election plus gateway re-point rather than by TCP
session death, and the under-one-second gate is measured at this tier. The order path gains one
in-cluster hop. Gateway loss drops counterparty sessions to ordinary reconnect while cluster
order state is unaffected — recorded as TD-AC01.

## Horizontal scale-out (ingress throughput)

The tier is **stateful per instance but shares nothing** between instances: each gateway pod holds
its own AeronCluster session, its own single owner thread (which correlates acks FIFO), and its own
FIX sessions. That single owner thread is the ingress ceiling — one thread submitting pipelined
batches. So the tier **scales out horizontally**: N replicas open N independent cluster sessions =
N× parallel batch submission, and because order refs are assigned globally by the cluster there is
no cross-replica coordination. This is the burst-free path past the single-owner-thread ceiling
(~134k orders/s on one gateway); the eventual limit becomes the cluster's Raft commit rate.

REST is stateless per request, so the `order-matcher-gw` Service round-robins batches across
replicas freely. FIX sessions are stateful per CompID, so the Service carries `sessionAffinity:
ClientIP` to pin a FIX counterparty to one replica. Scheduling N replicas needs node capacity
(a node-pool resize) — recorded as an ops prerequisite, not an architectural constraint.
