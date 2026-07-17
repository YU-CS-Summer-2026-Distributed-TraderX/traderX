# ADR-046: Snapshot completeness covers every future-output generator

Status: Accepted

## Context

The parent state's recovery acceptance exposed a real correctness defect: a bundle-recovered
follower reported `12 orders warm, nextRef 8` and, once promoted, reissued `ord-013-0008`. The
root cause was architectural — the monotonic order-reference generator lived outside the
deterministic replicated state, so journal replay and follower injection advanced the book
without advancing the counter, and a later snapshot faithfully captured the inconsistency.
Matching-state equality between nodes did not prove snapshot completeness. A
`max(retained orders)` reconciliation is not a sufficient invariant because terminal-order
eviction can remove the highest historical reference from the book.

## Decision

Every value that generates future output or admits commands is part of the replicated,
snapshotted service state: `nextOrderRef`, trade counters, idempotency state, risk reservations
and balances, symbol-table identity, and control/policy versions. `onTakeSnapshot` persists this
state bound to exactly the service's applied log position; recovery loads the newest valid
snapshot, resumes strictly after its position, and asserts on load and on promotion that every
restored generator strictly exceeds every identifier in the restored state, failing closed on
violation.

Acceptance is adversarial: issue orders after a snapshot, recover from snapshot plus log tail,
promote the recovered member, and assert the next generated ID is strictly greater than every ID
ever issued — not merely every ID still retained in memory.

## Consequences

The ID generator becomes ordinary deterministic state advanced by the same committed messages on
every replica, so the parent state's three-seam repair (replay advance, injection advance, load
reconciliation) is unnecessary — the seams no longer exist. The snapshot format grows beyond the
visible book, and the recovery matrix (post-snapshot orders, corruption, interrupted install,
term change) is the completeness proof rather than state-hash equality alone.
