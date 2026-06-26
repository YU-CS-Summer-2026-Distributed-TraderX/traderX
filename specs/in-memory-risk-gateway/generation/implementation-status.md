# Implementation Status: in-memory-risk-gateway

**Status:** Core implementation is present. Startup recovery, durable control sources, replica consumption,
BLP-side policy enforcement, and core tests were implemented. Final container verification, regeneration,
and performance acceptance remain open.  
**Parent:** `009b-lmax-sequencer-architecture`  
**Branch:** `in-memory-risk-gateway`

## Implemented

- Named-state generation, renderer wiring, catalog/runtime registration, and state scaffolding for the
  `in-memory-risk-gateway` line.
- Full startup recovery through persisted snapshots plus journal-tail replay, including restoration of
  matcher state, risk state, positions, expiry state, read-model rebuild, and continuation from the next
  global input sequence.
- Durable account-service control ownership for policy, restriction, and kill-switch state with
  PostgreSQL-backed persistence, optimistic versioning, operator provenance, snapshot/delta APIs, and
  outbox-driven JetStream publication.
- Durable reference-data security/status ownership with persisted state, bounded outbox, snapshot/delta
  APIs, and JetStream publication.
- Gateway/matcher control consumption for account, entitlement, security, policy, restriction, and
  kill-switch updates.
- Gap and epoch invalidation, poison-event quarantine, automatic rebootstrap, stale-feed handling, and
  fail-closed admission behavior.
- Dynamic policy-limit sequencing through the BLP journal.
- Recovery-safe projector catch-up path that avoids replay-side external re-publication.
- Warm follower state/image path and follower promotability checks.
- Configured replica and entitlement capacity enforcement.
- NATS subject ACL wiring, production TLS/auth template, and production at-rest security preflight.
- Metrics for Gateway validation latency, BLP decision latency, reservations, replica rebootstrap, and
  control-update rejection.
- Expanded tests for aggregate-limit correctness across Gateways, follower equivalence, stale-control-feed
  handling, gap/epoch recovery, mixed-path zero allocation, recovery correctness, and production security
  requirements.

## Verification Evidence

Previously demonstrated on this branch:

- clean generated Java suites passed
- `noGcTest` passed
- account-service tests passed
- reference-data production build passed
- risk latency benchmark passed comfortably
- output latency and topology benchmarks passed in non-perf-profile runs
- NATS ACL config passed broker validation
- full Docker runtime built and started
- matcher restart recovery was demonstrated
- a durable policy mutation committed successfully to the authoritative outbox
- manual risk-aware order submission returned `201`

Representative benchmark notes from prior runs:

- Gateway p99 previously recorded at `625 ns`
- BLP p99 previously recorded at `459 ns`
- the last noted demo-profile end-to-end p99 was `271,625 ns`, so end-to-end perf acceptance remains open

## Still Open

- finish the full container smoke, starting with the unresolved order-create `400` discrepancy
- complete the remaining smoke stages after order-create succeeds:
  cancel/fill, projector convergence, NATS events, WebSocket delivery, UI checks, and risk assertions
- verify durable control propagation end to end:
  policy, security-status, restriction enforcement, kill-switch enforcement, and NATS ACL denial behavior
- rerun clean generation after the late changes to runtime, config, and smoke files
- rerun the full post-generation gates
- capture the final same-host `009b` baseline and controlled perf-profile rerun
- refresh publication artifacts and docs:
  `tasks.md`, final status docs, overlay patchset, and `git diff --check`

## Caution

This branch was interrupted due to usage limits while late verification work was still in progress.
Treat current code as largely implemented but not fully re-verified for publication until the remaining
smoke, regeneration, and acceptance steps are completed.
