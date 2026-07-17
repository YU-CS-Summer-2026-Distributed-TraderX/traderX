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
