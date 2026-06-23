# Feature Specification: In-Memory Risk Gateway

**Feature Branch**: `in-memory-risk-gateway`  
**Created**: 2026-06-22  
**Status**: Active implementation  
**Input**: Transition delta from `009b-lmax-sequencer-architecture`, derived from
`INMEMORY-RISK-GATEWAY-ARCHITECTURE.md` and `INMEMORY-RISK-GATEWAY-HANDOFF.md`

This state completes the producer side of the `009b` LMAX line. It replaces blocking remote
validation with event-fed, in-memory Gateway replicas and adds an authoritative deterministic
pre-trade decision to the single-writer BLP. The BLP checks and reserves exact aggregate exposure in
global sequence order before an order becomes executable. Rejected commands remain journaled for
audit and replay but never enter the executable book or produce a market-facing business event.

Requirement IDs use the `IMRG` namespace (`FR-IMRGxx`, `NFR-IMRGxx`, `SC-IMRGxx`). The inherited
no-GC requirements are extended in `requirements/no-gc-conformance.md`.

The SEC Market Access Rule is used only as a control-requirements baseline for pre-set limits,
erroneous-order checks, restrictions, and controlled policy administration. This state does not claim
production regulatory compliance or replace legal, supervisory, security, and operational review.

## User Stories

- As a trader, I want valid orders screened and decided without remote service lookups, so dependency
  latency and outages do not sit on the admission path.
- As a risk owner, I want account, security, limit, restriction, and kill-switch controls applied before
  an order becomes executable, so accepted orders are bounded by the installed policy.
- As a platform engineer, I want all decision-relevant control changes ordered with commands and prices,
  so replay reproduces the exact original acceptance or rejection.
- As an operator, I want replica readiness, lag, gaps, staleness, policy versions, and Gateway/BLP
  disagreement observable, so the system fails closed rather than silently using uncertain state.
- As a client developer, I want an idempotent command contract and stable rejection reasons, so retries
  cannot create duplicate orders and failures can be handled deterministically.
- As a maintainer, I want the inherited `009b` no-GC and latency gates extended across validation and
  risk decisions, so the new controls do not undo the hot-path work.

## Functional Requirements

### Gateway replica foundation

- **FR-IMRG01:** Order and market-trade admission SHALL make no synchronous REST, database, or other
  network lookup for account, entitlement, security, restriction, limit, position, or price validation.
- **FR-IMRG02:** Every admission Gateway SHALL maintain local replicas of security identity/status,
  account status, principal-to-account entitlements, restrictions, risk limits, kill switches, and
  last-price freshness metadata.
- **FR-IMRG03:** Every external replica record and delta SHALL carry a source epoch, aggregate key,
  monotonic source version, and decision-relevant value. A version gap, regression, or epoch change
  SHALL invalidate readiness for that replica.
- **FR-IMRG04:** Replica bootstrap SHALL subscribe and buffer deltas before fetching a complete snapshot,
  install the snapshot atomically at watermark `W`, apply buffered deltas greater than `W` in order,
  and discard duplicates at or below `W`.
- **FR-IMRG05:** A Gateway SHALL report ready only after every mandatory replica has installed a valid
  snapshot and reached the observed durable-stream high watermark. A partial snapshot or best-effort
  `GET all` followed by subscription SHALL NOT satisfy readiness.
- **FR-IMRG06:** Gateway screening SHALL validate payload shape, authenticated entitlement,
  known/enabled security, trading status, restrictions, kill switches, price freshness, order
  quantity/notional bounds, and obvious limit violations using only local state.
- **FR-IMRG07:** A Gateway pass SHALL be preliminary and SHALL NOT be represented as final acceptance.
  Mutable and aggregate-dependent checks SHALL be repeated by the BLP.
- **FR-IMRG08:** Security identifiers SHALL be assigned only from authoritative reference-data state.
  An unknown client-supplied ticker SHALL NOT create or mutate the symbol table.
- **FR-IMRG09:** Price state SHALL include fixed-point price, source timestamp, source sequence/version,
  and trading-status context. Missing or stale price SHALL be distinguishable from numeric price zero.

### Sequenced authoritative decision

- **FR-IMRG10:** Commands passing Gateway screening SHALL enter the inherited input stream as
  `ORDER_SUBMITTED` or `TRADE_SUBMITTED` and SHALL be journaled before the authoritative BLP decision.
- **FR-IMRG11:** Account, entitlement, security-status, restriction, risk-policy, kill-switch, and other
  decision-relevant changes SHALL enter the same global input sequence as versioned control events.
- **FR-IMRG12:** For every submitted command, the BLP SHALL execute a stable ordered validation pipeline
  against state effective at that global sequence. The pipeline order and stable rejection precedence
  SHALL be part of the contract.
- **FR-IMRG13:** The BLP SHALL own exact positions and open-order reserved exposure. Check plus reserve
  SHALL execute as one single-threaded operation before adding an order to the executable book.
- **FR-IMRG14:** New commands SHALL carry a required `clientOrderId`. The BLP SHALL map each idempotency
  key to one immutable original decision within the configured retention frontier; a retry SHALL return
  that decision without creating or reserving a second order.
- **FR-IMRG15:** The BLP SHALL emit exactly one accepted or rejected decision per new idempotency key.
  Every decision SHALL carry command sequence, stable reason code, policy version, relevant control
  versions/watermarks, and decision latency correlation fields.
- **FR-IMRG16:** Fill SHALL convert reserved exposure to executed position; cancel, reject, and expiry
  SHALL release the applicable reservation exactly once. Reservations SHALL never become negative.
- **FR-IMRG17:** Price-dependent decisions SHALL use the last valid sequenced price and event-carried
  source time. The BLP SHALL never substitute zero for missing price state.
- **FR-IMRG18:** Gateway and BLP SHALL fail closed for new risk-increasing commands when mandatory
  control state is missing, invalid, gapped, or stale. Any risk-reducing exception SHALL be explicitly
  defined by versioned policy.
- **FR-IMRG19:** When Gateway screening and the BLP decision disagree, the BLP SHALL win. The mismatch
  SHALL produce a bounded-cardinality metric and an auditable diagnostic event.
- **FR-IMRG20:** A synchronous external API SHALL report success only after the BLP acceptance decision.
  Rejection SHALL return a stable 4xx response. Any future asynchronous mode SHALL return `202` with a
  command identifier rather than a premature `200` acceptance.

### Lifecycle, recovery, and control administration

- **FR-IMRG21:** The inherited BLP snapshot SHALL include risk policies, account/security control state,
  restrictions, kill switches, exact reservations, idempotency state/frontier, price freshness fields,
  and their source watermarks.
- **FR-IMRG22:** Snapshot plus journal replay SHALL reproduce identical decisions, reason codes,
  reservations, accepted order state, and outputs without querying current external state.
- **FR-IMRG23:** Rejected commands SHALL remain in the authoritative journal but SHALL NOT enter the
  executable book, reserve exposure, or emit accepted trade/order/position business events.
- **FR-IMRG24:** A policy change SHALL explicitly define treatment of already-resting orders: retain,
  mark reduce-only, or cancel through explicit sequenced cancellation events. No policy update may
  silently delete or mutate resting orders.
- **FR-IMRG25:** Multiple Gateways MAY screen concurrently, but only the active BLP SHALL authoritatively
  check and reserve shared aggregate exposure. Gateway replicas SHALL require no cross-Gateway lock.
- **FR-IMRG26:** Replica, policy, restriction, idempotency, account, security, and reservation stores
  SHALL have bounded configured capacities. Exhaustion SHALL reject or apply bounded backpressure;
  it SHALL NOT bypass controls or allocate an unbounded fallback structure.
- **FR-IMRG27:** The BLP decision path SHALL make no external call and SHALL publish no NATS/DB side
  effect directly. Its only side effect channel remains the inherited output ring.
- **FR-IMRG30:** Risk-policy, restriction, and kill-switch updates SHALL be authenticated, attributable
  to an authorized operator/source, versioned, and auditable.
- **FR-IMRG31:** Risk administration MAY run as a separate control-plane component, but the command path
  SHALL consume only installed local state; loss of the administration UI/service SHALL not trigger a
  synchronous lookup or erase the last proven policy.
- **FR-IMRG32:** Account-service and reference-data SHALL expose complete watermarked snapshots and
  durable versioned deltas for the fields used by admission.
- **FR-IMRG33:** The control feed SHALL provide retention, replay, consumer position, and gap detection.
  Core best-effort messaging without an equivalent retained log SHALL NOT be the sole delivery model.
- **FR-IMRG34:** On invalid/out-of-order control updates, consumers SHALL quarantine the update, retain
  the last proven version, alert, and fail closed wherever correctness cannot be established.
- **FR-IMRG35:** Startup and degraded-mode behavior SHALL follow the explicit matrix in
  `system/runtime-topology.md`; no generic fail-open switch is permitted for risk-increasing commands.

### Compatibility and observability

- **FR-IMRG40:** The `009b` input/output disruptor topology, journal-before-BLP gate, matching policy,
  output handlers, projection model, and trade/order/position NATS subjects SHALL remain unchanged
  except for the new input/control/decision fields specified here.
- **FR-IMRG41:** The output-fed relational/UI read model SHALL remain a CQRS query projection and SHALL
  NOT be used as authoritative admission or aggregate-risk state.
- **FR-IMRG42:** Accepted commands SHALL retain the external `009b` order/trade/position payloads and UI
  journeys. The required `clientOrderId` and rejection body are the only intentional admission API
  deltas in this state.
- **FR-IMRG43:** Gateway readiness, replica versions/lag/gaps, decision latency, decisions by stable
  reason, reservations, policy version, idempotent retries, and Gateway/BLP mismatches SHALL be exposed
  through Prometheus metrics defined in `requirements/nonfunctional-delta.md`.
- **FR-IMRG44:** UI header/About/status metadata SHALL identify the active state as
  `in-memory-risk-gateway`; all other inherited UI behavior remains unchanged.
- **FR-IMRG45:** This state SHALL NOT redesign output-ring sequencing, backpressure, handler topology,
  or downstream fan-out.

## Non-Functional Requirements

- **NFR-IMRG01:** On the `perf` profile, Gateway screening p99 SHALL be `< 25 us`, authoritative BLP
  decision plus reservation p99 `< 25 us`, and Gateway ingest through decision/output emit SHALL remain
  within the inherited `009b` in-node p99 `< 150 us` budget.
- **NFR-IMRG02:** Gateway screening, input/control decode, BLP decision/reservation, and output decision
  emit SHALL allocate zero bytes per event after warm-up and pass the inherited Epsilon-GC and banned-
  API gates extended by `requirements/no-gc-conformance.md`.
- **NFR-IMRG03:** Identical snapshot and journal input SHALL reproduce byte-equivalent decision outputs
  and identical final risk/order state.
- **NFR-IMRG04:** The BLP SHALL preserve single-writer discipline: no locks, blocking calls, shared-state
  atomics, wall-clock reads, random identifiers, or unordered decision iteration.
- **NFR-IMRG05:** Recovery SHALL restore the complete risk/order state to the last journaled sequence and
  complete inherited JIT warm-up inside the `009b` `< 1 minute` recovery target.
- **NFR-IMRG06:** Admission readiness SHALL be false until all mandatory replicas are complete and the BLP
  has replayed to the durable high watermark. Liveness MAY remain true for diagnostics and safe shutdown.
- **NFR-IMRG07:** Control-stream disconnection, version gaps, invalid policies, or stale price/control
  state SHALL be detected within configured bounds and SHALL activate the specified fail-closed mode.
- **NFR-IMRG08:** Observability SHALL retain all `009b` metrics and add the bounded metric set in
  `requirements/nonfunctional-delta.md`, with Grafana panels and alerts for readiness, lag, gaps,
  rejections, mismatch, and decision latency.
- **NFR-IMRG09:** Policy/control update paths SHALL require authenticated transport and authorized
  provenance; journals, snapshots, and diagnostic events SHALL not contain credentials or secrets.
- **NFR-IMRG10:** Metrics SHALL not create unbounded labels for account, client-order, security, or
  principal identifiers. High-cardinality details belong in sampled structured audit records.
- **NFR-IMRG11:** `C2` build/publish, LGTM observability, NATS/UI contracts, deployment bundle, and
  dependency/CVE gates inherited through `009b` SHALL remain intact.
- **NFR-IMRG12:** New durable-stream/client and data-structure dependencies SHALL be version-pinned,
  CVE-clean, justified in research/ADRs, and excluded from the per-event allocation path where possible.
- **NFR-IMRG13:** The inherited `noGcTest`, `outputLatencyBenchmark`, `outputTopologyBenchmark`, and
  `009b` state smoke suite SHALL show no material regression under the same environment and fixture.

## Success Criteria

- **SC-IMRG01:** Generation hook and state-native lifecycle/test entrypoints are defined and runnable.
- **SC-IMRG02:** No admission validation path contains `RestTemplate`, `WebClient`, JPA, JDBC, or direct
  database access for account/reference/price/risk checks.
- **SC-IMRG03:** Cold start with snapshot/delta overlap proves gap-free bootstrap at watermark `W` and
  readiness remains false until high-watermark catch-up.
- **SC-IMRG04:** Unknown/disabled account, unauthorized principal, unknown/disabled security,
  restriction, kill switch, missing/stale price, price collar, size/notional, credit, position, and
  concentration failures each return the specified stable reason.
- **SC-IMRG05:** Two Gateways concurrently submit against one remaining aggregate limit; the BLP accepts
  and reserves at most the allowed command with no overshoot.
- **SC-IMRG06:** Duplicate `clientOrderId` retries return the original decision and do not create an
  additional order, reservation, trade, position update, or accepted output.
- **SC-IMRG07:** Fill/cancel/reject/expiry scenarios prove reservation conversion/release exactly once
  and never produce negative reservation state.
- **SC-IMRG08:** Snapshot plus replay of mixed control/price/command events reproduces byte-equivalent
  decisions, reservations, accepted orders, and final state.
- **SC-IMRG09:** Gateway/BLP disagreement fixture proves the BLP decision wins and mismatch telemetry is
  emitted without leaking high-cardinality command data into metrics.
- **SC-IMRG10:** Control-stream loss, version gap, epoch change, invalid policy, and stale price fixtures
  activate the documented readiness/fail-closed behavior.
- **SC-IMRG11:** Accepted order, market-trade, fill, cancel, trade, position, REST, WS, NATS, and UI
  journeys remain compatible with `009b` except for the intentional admission contract delta.
- **SC-IMRG12:** Epsilon-GC allocation and banned-API gates cover Gateway screening and BLP risk code and
  pass at zero steady-state allocation.
- **SC-IMRG13:** Risk decision and admission latency reports include p50/p99/p99.9/max and meet
  NFR-IMRG01 on the documented `perf` profile.
- **SC-IMRG14:** Required readiness/risk/replica metrics are scraped by Prometheus and represented in a
  provisioned Grafana dashboard with actionable alerts.
- **SC-IMRG15:** `outputLatencyBenchmark`, `outputTopologyBenchmark`, and the inherited `009b` smoke
  suite pass without material regression.
- **SC-IMRG16:** State metadata, catalog lineage, generated docs, lifecycle names, and UI metadata use
  `in-memory-risk-gateway` consistently with parent `009b-lmax-sequencer-architecture`.
- **SC-IMRG17:** Documentation clearly labels SEC Rule 15c3-5 as a requirements baseline rather than a
  compliance certification.

## Constraints and Out of Scope

- No output-disruptor redesign or deeper output-ring tuning.
- No synchronous remote risk microservice on the command path.
- No portfolio VaR, scenario analytics, margin optimization, or enterprise collateral model.
- No generic fail-open mode for risk-increasing orders.
- No replacement of unrelated UI/read-side services.
- No legal or regulatory compliance certification.
