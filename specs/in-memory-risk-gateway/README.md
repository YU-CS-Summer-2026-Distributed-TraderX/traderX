# Feature Pack in: In-Memory Risk Gateway

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Draft  
Track: `architecture`  
Previous state: `009b-lmax-sequencer-architecture`

This pack defines the next optional architecture state after `009b`. It closes the producer-side
validation gap by replacing blocking account/reference lookups with versioned, event-fed in-memory
Gateway replicas and by placing the authoritative aggregate pre-trade decision inside the sequenced,
single-writer Business Logic Processor (BLP).

The Gateway performs fast preliminary screening and readiness protection. The BLP repeats every
mutable or aggregate-dependent check against exact positions, open-order reservations, limits,
restrictions, and price state at the command's global sequence. This prevents multiple Gateways from
overshooting a shared limit without adding a lock or synchronous risk-service hop.

Design sources (repo root):

- `INMEMORY-RISK-GATEWAY-ARCHITECTURE.md` — verified current path, target topology, ownership,
  replica inventory, freshness model, failure rules, and implementation order
- `INMEMORY-RISK-GATEWAY-HANDOFF.md` — scope and reference direction inherited from the `009b` work
- `LMAX-SEQUENCER-ARCHITECTURE.md` and `LMAX-BLP.md` — sequencer, single-writer, deterministic replay,
  and no-external-call invariants inherited from `009b`

Primary intent:

- remove synchronous REST/database validation from order and market-trade admission,
- make account, entitlement, security, restriction, limit, kill-switch, and price inputs local,
  versioned, warmable, and measurable,
- retain Gateway screening as an optimization while making the BLP the final aggregate-risk authority,
- journal every decision-relevant control change in the same total order as commands and prices,
- add atomic exposure reservation, idempotency, stable rejection reasons, and deterministic replay,
- preserve the `009b` input/output rings, matching semantics, NATS subjects, UI behavior, and
  read-model projections for accepted business events,
- keep the change spec-first; no risk engine or output-disruptor redesign begins before this pack is
  reviewed.

Core artifacts:

- `spec.md`
- `requirements/functional-delta.md`
- `requirements/nonfunctional-delta.md`
- `requirements/no-gc-conformance.md`
- `research.md`
- `data-model.md`
- `quickstart.md`
- `contracts/contract-delta.md`
- `system/architecture.md`
- `system/architecture.model.json`
- `system/runtime-topology.md`
- `system/messaging-subject-map.md`
- `system/adr-018-two-stage-validation.md`
- `system/adr-019-watermarked-replica-bootstrap.md`
- `system/adr-020-control-events-in-global-journal.md`
- `generation/generation-hook.md`
- `generation/implementation-status.md`
- `tests/smoke/README.md`
