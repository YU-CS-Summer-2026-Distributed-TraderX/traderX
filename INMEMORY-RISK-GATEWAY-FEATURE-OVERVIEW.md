# In-Memory Risk Gateway Feature Overview

This document explains what the in-memory-risk-gateway feature is, how it fits into the LMAX architecture, and what is implemented so far.

## Core idea

TraderX keeps the LMAX-style single-writer BLP as the final authority, but moves slow producer-path validation inputs into local in-memory replicas so the system does not need blocking remote lookups before sequencing an order.

The feature exists to solve two problems at once:

- keep the order-admission path fast and local
- keep aggregate risk decisions deterministic and authoritative

## The two-layer model

The design has two separate layers with different responsibilities.

### Gateway layer

The Gateway keeps local in-memory replicas of the control-plane data needed for fast screening, such as:

- account status
- principal/account entitlements
- security status
- restrictions
- kill switches
- policy inputs
- reference price inputs

The Gateway uses those replicas to reject obviously invalid, stale, unauthorized, disabled, or fail-closed requests before they ever consume sequencer capacity.

### BLP layer

The BLP is still the single-writer authority.

Once an order passes Gateway screening, it is sequenced as an input event. The BLP then applies the exact same control state in deterministic global order and decides whether to accept or reject the command. The BLP also owns exact reservations and aggregate exposure accounting.

That separation matters because two different Gateway nodes can both have locally valid views and still admit commands that would conflict in aggregate. Only the single sequenced BLP can make the final atomic decision.

## End-to-end flow

The intended path is:

1. client submits order
2. Gateway checks the request against local replicas
3. if the request fails screening, it is rejected immediately
4. if the request passes screening, it is converted into a sequenced input event
5. the BLP applies the command against exact positions, reservations, limits, restrictions, and price state
6. the BLP emits one deterministic result: accept or reject
7. accepted orders enter the executable matcher/order book and downstream outputs
8. rejected orders remain in the journal for deterministic replay and audit, but do not create market-facing side effects

## How controls enter the system

The feature adds durable control-plane sources so the Gateway and BLP can warm and update their local state without calling remote services on the hot path.

The control owners publish versioned snapshots and deltas for things like:

- account status and entitlements
- security enablement/status
- policy
- restrictions
- kill switches

The Gateway consumes those feeds into local replicas for fast screening. The matcher/BLP also consumes the relevant control updates and applies them in sequence so recovery and replay stay deterministic.

## Recovery model

The implementation is designed so restart recovery does not depend on re-querying remote services.

Recovery now uses:

- full persisted snapshots of risk and matcher state
- journal-tail replay
- restoration of the last applied global input sequence
- read-model rebuild during recovery
- recovery that does not re-emit external side effects

That means the BLP can recover the exact authoritative in-memory state it needs and resume deterministic processing without turning external services into replay dependencies.

## What is implemented so far

Implemented on this branch:

- full snapshot restoration and journal-tail replay
- durable policy/restriction/kill-switch source in account-service with PostgreSQL-backed state, optimistic versioning, provenance, snapshots, deltas, and outbox publication
- durable security/status source in reference-data with persisted state, bounded outbox, snapshots, deltas, and publication
- control-stream consumption for account, entitlement, security, policy, restriction, and kill-switch updates
- gap/epoch invalidation, poison-event quarantine, automatic rebootstrap, and fail-closed stale-feed handling
- dynamic policy limits applied through sequenced BLP events
- recovery/projector catch-up path without replay-side external re-publication
- warm follower image path and promotability checks
- configured replica/entitlement capacity enforcement
- NATS ACL and production security hardening templates
- expanded metrics and test coverage for recovery, control propagation mechanics, follower equivalence, aggregate-limit correctness, and security guardrails

## What is not yet closed

The main remaining work is no longer core feature construction. It is:

- full container smoke completion
- end-to-end control-propagation proof
- final regeneration and post-generation gates
- final performance acceptance recording

The branch should be described as "core feature implemented, final verification still open", not "fully finished".
