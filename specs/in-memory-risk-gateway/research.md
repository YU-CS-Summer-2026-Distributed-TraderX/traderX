# Research: In-Memory Risk Gateway

## Objective

Define an optional child state of `009b-lmax-sequencer-architecture` that removes producer-side
blocking validation and supplies deterministic, replayable pre-trade controls without reintroducing
network hops, locks, database access, or allocation into the LMAX hot path.

## Inputs Reviewed

- `INMEMORY-RISK-GATEWAY-ARCHITECTURE.md` and `INMEMORY-RISK-GATEWAY-HANDOFF.md`.
- `LMAX-SEQUENCER-ARCHITECTURE.md`, `LMAX-BLP.md`, and the full
  `specs/009b-lmax-sequencer-architecture/` pack.
- Current `009b` runtime overrides for `trade-service`, `OrderMatcherService`, `SymbolTable`,
  `LmaxEngine`, and `MatchingEngine`.
- Account-service and reference-data templates, APIs, and persistence/loading behavior.
- Martin Fowler, *Event Sourcing*: https://martinfowler.com/eaaDev/EventSourcing.html
- Martin Fowler, *CQRS*: https://martinfowler.com/bliki/CQRS.html
- SEC Rule 15c3-5 final rule: https://www.sec.gov/rules/final/2010/34-63241.pdf

## Verified Current-State Findings

1. `trade-service/TradeOrderController` performs blocking `GET /stocks/{ticker}` and
   `GET /account/{id}`, followed by blocking `RestTemplate.postForEntity` to order-matcher.
2. `OrderMatcherService.validateCreateRequest` validates only presence, enum, and positive numeric
   fields. It does not establish account existence/entitlement or reference-data membership.
3. `SymbolTable.idFor` registers a client ticker on first sight, so an arbitrary non-empty ticker can
   become a hot-path security id.
4. `MatchingEngine` owns exact positions and last prices but has no credit/position limits, restriction
   state, open-order exposure reservation, idempotency, or price-freshness rule.
5. `009b` FR-09B12 calls for warmed event-fed validation caches, but those caches are not implemented in
   the current runtime overrides.
6. Account writes are database-backed REST mutations without a durable versioned change feed.
7. Reference data is loaded from CSV and exposed through REST without source epoch/version or snapshot
   watermark semantics.
8. Missing market-trade price currently falls back to zero, which is unsuitable for notional/risk
   validation.

## Key Decisions

1. **Child of `009b`, not a sibling.** This state depends on the `009b` sequencer, journal, response
   events, fused positions, and output ring. Catalog `previous` is `009b-lmax-sequencer-architecture`.
2. **Named state and requirement namespace.** The state id and branch are
   `in-memory-risk-gateway`; requirement ids use `IMRG` rather than assigning a new numeric state.
3. **Two-stage validation.** Gateway replicas reject cheap/obvious failures and protect readiness;
   the BLP repeats mutable and aggregate checks as final authority. See ADR-018.
4. **BLP check-and-reserve.** Multiple Gateways cannot safely authoritatively consume the same credit
   headroom from independently lagging replicas. The existing single-writer BLP can check and reserve
   exact exposure atomically without locks.
5. **Submitted command is not executable acceptance.** A command may be journaled before decision for
   audit/replay. Only the BLP acceptance inserts into the executable order book.
6. **Watermarked snapshot plus buffered deltas.** Subscribe-before-snapshot closes the race between a
   `GET all` response and later subscription. Version/epoch proves completeness; TTL only detects
   silence. See ADR-019.
7. **Decision-relevant controls enter global sequence.** Replay must use the account/security/policy
   values effective when the original decision occurred, not today's external state. See ADR-020.
8. **CQRS read model stays non-authoritative.** The output-fed database/UI projection can lag and is
   optimized for queries. It must not drive command acceptance.
9. **Durable control delivery required.** NATS may carry the feed only with retention/replay/consumer
   position (for example JetStream) or an equivalent authoritative log/outbox. Core best-effort NATS
   alone is insufficient.
10. **No separate synchronous risk microservice.** Risk administration may be separate, but command
    decisions use installed local state only.
11. **Fail closed for risk increase.** Missing/gapped/stale mandatory state rejects new risk. Cancel and
    explicitly defined risk-reducing operations can remain available under versioned policy.
12. **Control baseline, not compliance claim.** Rule 15c3-5 motivates pre-set credit/capital thresholds,
    erroneous price/size/duplicate checks, restricted-security checks, and controlled policy changes.

## Alternatives Rejected

- **Remote synchronous risk service:** recreates latency, timeout, and availability coupling.
- **Gateway-only aggregate risk:** concurrent Gateways can both observe and spend the same headroom.
- **BLP-only validation:** correct but wastes ring/journal capacity on trivial malformed/unauthorized
  traffic and provides weak edge readiness behavior.
- **Output read-model as risk store:** projection lag makes it neither exact nor replay-authoritative.
- **TTL-only freshness:** does not prove that no versions were missed.
- **Fetch snapshot then subscribe:** loses updates in the handoff gap.
- **Unbounded maps for idempotency/policy:** violates capacity and no-GC invariants.
- **Zero as missing price:** conflates unknown state with a real numeric value.

## Risks and Mitigations

- Risk: BLP policy complexity grows enough to threaten latency/determinism.
  - Mitigation: stable check pipeline, primitive precompiled policy structures, bounded feature scope,
    per-stage HdrHistogram, and no dynamic expression engine.
- Risk: source services cannot atomically publish data and a watermark.
  - Mitigation: transactional outbox/change-log sequence per source plus snapshot transaction at an
    outbox watermark.
- Risk: Gateway and BLP consume control updates at different times.
  - Mitigation: Gateway is preliminary only; BLP versioned decision is authoritative; mismatch measured.
- Risk: reservations drift on lifecycle edge cases.
  - Mitigation: invariant/property tests, exactly-once release markers, snapshot/replay comparison.
- Risk: idempotency storage fills.
  - Mitigation: configured capacity and deterministic retention frontier; explicit capacity rejection.
- Risk: stale price rejects otherwise valid traffic.
  - Mitigation: explicit source-specific max-age policy and observable price-feed readiness; never
    silently fail open.
- Risk: high-cardinality risk telemetry overloads Prometheus.
  - Mitigation: bounded enum labels only; detail goes to sampled structured audit records.
- Risk: scope expands into an enterprise risk platform.
  - Mitigation: explicit exclusions for VaR, margin optimization, and remote synchronous risk.

