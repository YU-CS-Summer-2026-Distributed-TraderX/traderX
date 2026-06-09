# Feature Pack 009b: LMAX Sequencer Architecture (Trading Hot Path)

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Draft
Track: `architecture`
Previous state: `009-order-management-matcher`

This pack defines a sibling branch state off `009-order-management-matcher`. It re-architects the
trading hot path around the LMAX architecture — a global sequencer, an input disruptor with parallel
journal/replicate/un-marshal handlers, a single-threaded in-memory Business Logic Processor (BLP), an
output disruptor with NATS fan-out and an async read-model projector, and a cross-cutting no-GC (zero
steady-state allocation) conformance gate — while preserving every external contract from `009`.

Design sources (repo root):

- `LMAX-SEQUENCER-ARCHITECTURE.md` — the full hot-path redesign and latency budget
- `LMAX-INPUT-DISRUPTOR.md` — ingestion ring, claim protocol, sequence barrier, ring sizing
- `LMAX-BLP.md` — the single-threaded, in-memory, event-sourced matching engine
- `LMAX-OUTPUT-DISRUPTOR.md` — egress ring, NATS bridge, async read-model projector
- `LMAX-NO-GC-JAVA.md` — allocation discipline and the Epsilon-GC conformance gate

Primary intent:

- collapse `009`'s multi-hop match path (poll tick -> lock -> REST -> JPA) into one sequenced,
  event-sourced, single-writer in-memory path,
- make the journal the authoritative store with Postgres/H2 demoted to an async read-model,
- preserve all `009` REST/WS/NATS/UI/schema contracts verbatim (the migration is invisible at the edge),
- enforce zero steady-state allocation on the hot path via an Epsilon-GC CI gate,
- add replication + warm-standby failover, demo-right-sized (single replica in the `demo`/`C2` profile),
- capture explicit requirement deltas for this transition,
- define architecture/runtime topology updates for this state,
- keep generation fully spec-first,
- publish a reproducible generated snapshot branch when implemented.

Core artifacts:

- `spec.md`
- `requirements/functional-delta.md`
- `requirements/nonfunctional-delta.md`
- `requirements/no-gc-conformance.md`
- `research.md`
- `data-model.md`
- `quickstart.md`
- `contracts/contract-delta.md`
- `system/architecture.model.json`
- `system/runtime-topology.md`
- `system/adr-014-input-disruptor-over-poll-and-lock.md`
- `system/adr-015-single-thread-in-memory-blp.md`
- `system/adr-016-async-read-model-over-inline-persistence.md`
- `system/adr-017-no-gc-conformance-and-epsilon-gate.md`
- `generation/generation-hook.md`
- `tests/smoke/README.md`
