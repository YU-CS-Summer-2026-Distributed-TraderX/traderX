# No-GC Conformance Delta: In-Memory Risk Gateway

Parent profile: `specs/009b-lmax-sequencer-architecture/requirements/no-gc-conformance.md`

This state inherits every `009b` no-GC rule and expands the measured hot-path boundary. It does not
replace or weaken the parent profile.

## Covered Nodes

- Gateway request normalization after framework deserialization.
- Local replica lookups and preliminary validation.
- Submitted-command/control-event encode into inherited input slots.
- BLP control-event apply.
- BLP validation, exposure calculation, reservation, idempotency lookup, and decision emit.
- Reservation conversion/release on fill/cancel/reject/expiry.

Framework HTTP/JSON parsing remains an edge allocation boundary unless a later state replaces it. No
allocation created after normalized command entry may be attributed to validation or BLP risk logic.

## IMRG Allocation Rules

- **NGC-IMRG01:** Replica stores SHALL be pre-sized primitive arrays/maps or immutable snapshot images
  swapped outside the per-command path; no per-command object graph traversal or copy.
- **NGC-IMRG02:** Stable reason/decision/policy enums SHALL be numeric constants internally; strings are
  rendered only at the HTTP/audit edge.
- **NGC-IMRG03:** Idempotency storage SHALL use fixed-capacity preallocated records and primitive/hash
  keys with deterministic eviction/frontier semantics; no `UUID`, boxing, or unbounded map.
- **NGC-IMRG04:** Exposure and notional math SHALL use checked fixed-point `long` arithmetic with
  explicit overflow rejection; no `BigDecimal` in Gateway/BLP risk code.
- **NGC-IMRG05:** Restriction/policy evaluation SHALL use precompiled primitive structures; no streams,
  lambdas, regex, reflection, dynamic expression evaluation, or temporary collections per command.
- **NGC-IMRG06:** Metrics SHALL use pre-registered bounded label combinations; no label/string creation
  on the decision path.
- **NGC-IMRG07:** Audit detail SHALL be copied into preallocated output/event slots or sampled off-thread;
  synchronous string/JSON construction is forbidden in the BLP.
- **NGC-IMRG08:** Snapshot install/re-bootstrap MAY allocate off the live command path, but publication
  into the Gateway SHALL be atomic and SHALL NOT pause or mutate a live replica image in place.

## Gates

- Extend the Gradle `noGcTest` source set to include Gateway validator, replica lookup, BLP risk,
  reservation, and idempotency fixtures.
- Run under Epsilon GC with pre-touched fixed heap and sufficient warm-up; heap growth or exhaustion is
  failure.
- Extend the banned-API/constant-pool scan across the new hot-path packages.
- Include mixed accepted/rejected/duplicate/control-update event distributions, not accepted commands
  only.
- Report exact allocated bytes/event plus event count and heap delta; pass criterion is zero steady-state
  allocation attributable to the covered nodes.
- Re-run inherited output-handler allocation gates to prevent boundary regression.

## Required Fixtures

1. Known account/security accepted under limits.
2. Unknown, restricted, stale-price, and limit rejection reasons.
3. Duplicate idempotency key returning original decision.
4. Reservation create, partial fill, full fill, cancel, and release.
5. Policy/control event application followed by command decision.
6. Gateway/BLP mismatch diagnostic emission.
7. Capacity exhaustion explicit rejection without fallback allocation.

