# ADR-045: The consensus log is the only input path into the state machine

Status: Accepted

## Context

Replicated state machines diverge on any input that does not pass through the replicated log.
The parent state demonstrated the failure shape live: followers locally injecting NATS price
ticks into their own rings produced double-applied prices and mixed-space journals, and a
recovered follower's separately-fed gateway control replicas trailed the recovery boundary while
Kubernetes reported the pod ready. Price ticks and control-feed updates are inputs to matching
and risk state exactly as orders are.

## Decision

Every input to the deterministic core enters as a sequenced cluster ingress message: order
commands and cancels from the gateway tier, price ticks and control/policy updates from a feed
adapter that consumes the inherited NATS subjects and publishes through the cluster client. The
feed adapter conflates ticks per symbol to bound log volume. Inside the service no NATS
subscription, HTTP call, wall-clock read, or cross-thread mutable value participates in a state
transition; time-driven behavior uses cluster time via `onTimerEvent`.

Readiness carries two explicit signals: deterministic state recovered from the cluster
snapshot/log, and asynchronously refreshed gateway/control-feed admission state. Admission opens
only when both are valid at or beyond the recovery boundary.

## Consequences

Replica state is identical by construction, and recovery replays ticks and control updates in
the same order every replica applied them — the double-apply and trailing-replica defect classes
cannot recur. The consensus log carries market-data volume, bounded by adapter-side conflation.
The two readiness semantics that silently coexisted in the parent state become one explicit
contract.
