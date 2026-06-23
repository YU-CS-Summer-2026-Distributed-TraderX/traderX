# Implementation Status: in-memory-risk-gateway

**Status:** Admission, controls, reservations, expiry, versioned persistence, and durable account
transport are implemented and clean-generation verified. Full crash replay and production control-plane
hardening remain open.  
**Parent:** `009b-lmax-sequencer-architecture`  
**Branch:** `in-memory-risk-gateway`

## Implemented

- Named-state generation, renderer, lifecycle/test delegates, numeric-base handling, deterministic
  generated timestamps, UI/catalog registration, and captured overlay patchset.
- Account-service transactional JDBC account/entitlement outbox, scheduled JetStream ack-before-delete
  drainer, and watermarked snapshot/delta API; reference-data watermarked security snapshot/delta API;
  file-backed JetStream stream and durable pull consumer with seven-day retention.
- Versioned Gateway replicas with atomic snapshot installation, high-watermark readiness, gap/epoch
  fail-closed behavior, durable account/entitlement/security delta application, authoritative security
  ids, local entitlement/account/security/restriction/price/policy screening, and no remote admission
  lookup.
- BLP-owned account/security/entitlement/restriction/policy state, checked fixed-point notional, exact
  directional reservations, credit/position/concentration limits, fill conversion, cancel/expiry
  release, explicit restriction-driven resting-order cancellation, bounded idempotency with a
  deterministic retention frontier, stable precedence, and synchronous acknowledgements.
- Required bounded external `clientOrderId` with UI/trade-service generation; versioned/checksummed
  input-journal records, explicit 009b/pre-version legacy upcasters, corruption diagnostics, and a
  locked SBE contract artifact.
- Atomic versioned BLP risk snapshots covering controls, restrictions, prices, reservations,
  entitlements, exposures, limits, and idempotency state. Snapshot corruption and incompatible
  capacities fail startup; compose persists snapshot and journal data in a named volume.
- Stable `422`/`503` rejection body, command correlation, authenticated/audited control mutations,
  bounded Prometheus metrics, alerts, and provisioned Grafana dashboard.
- Epsilon allocation coverage, risk unit/integration tests, race-free read snapshots, risk latency
  benchmark, and inherited output latency/topology regression runs.

## Verification Evidence (2026-06-22, macOS arm64)

- Full clean generation with lockfile refresh:
  `TRADERX_GENERATED_ROOT=/private/tmp/traderx-imrg-final bash pipeline/generate-state-in-memory-risk-gateway.sh`
- Clean generated order-matcher: `./gradlew --no-daemon test` — passed.
- Clean generated account-service: `./gradlew --no-daemon test` — passed (`NO-SOURCE`).
- Clean generated order-matcher: `./gradlew --no-daemon noGcTest -Dgate.steadyStateEvents=100000`
  — passed under Epsilon GC.
- Generated Angular production bundle — passed with the bundled LTS Node runtime; inherited bundle-size
  and legacy selector warnings remain.
- Snapshot round-trip/corruption and journal v2/legacy/corruption tests — passed in the full suite.
- Risk latency (20,000 iterations): Gateway p99 `375 ns`; BLP decide/reserve p99 `416 ns`.
- Inherited output latency/topology tasks — passed. In-process create p99 was `167,959 ns` in this
  non-perf-profile run; this exceeds the `<150 us` target and requires a controlled perf-profile rerun.
- Spec expressiveness, root Spec Kit gates, state-doc consistency, UI metadata, and `git diff --check`
  passed. The broad coverage gate still reports the pre-existing state-004 convergence-rationale delta.

## Explicit Remaining Production Gaps

- Wire a durable security-status publisher and a transactional risk-policy/restriction/kill-switch
  owner/outbox. The consumer and direct authenticated sequenced administration path exist, but those
  two source owners are not yet end-to-end durable publishers.
- Wire decoded journal tail replay into startup and persist the complete matching/position/expiry image
  with the risk image. The reader, legacy upcasters, journal checksums, and risk snapshot codec are
  implemented, but crash recovery is not yet complete to the last journaled sequence.
- Add NATS subject ACL credentials and at-rest protection for production deployment; the demo stream is
  retained and durable but intentionally runs on the inherited unauthenticated local broker.
- Complete bootstrap overflow/retention-loss/reorder tests, mixed snapshot-plus-tail byte-equivalence,
  and follower promotion.
- Run the containerized state smoke including NATS/WS/UI paths and record a same-host `009b` admission
  baseline plus perf-profile rerun.

These gaps remain unchecked in `tasks.md`; class presence is not treated as proof of completion.
