# Feature Pack: YU06-eod-price-production

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Implemented
Track: `architecture`
Lineage role: `optional`
Previous state: `YU05-post-trade-compliance`

This pack defines end-of-day price production and an overnight batch dependency chain on top of
the `YU05-post-trade-compliance` baseline.

Primary intent:

- produce an official, versioned, immutable closing-price snapshot per trading session,
- gate all downstream overnight jobs behind one durable event rather than a "read latest price"
  race,
- fail safe: hold back publication on a data-quality flag, hold back a consumer's account on a
  missing/flagged holding,
- drive one real downstream consumer (EOD position marks / P&L) end to end.

Core artifacts:

- `spec.md`
- `requirements/functional-delta.md`
- `requirements/nonfunctional-delta.md`
- `research.md`
- `data-model.md`
- `quickstart.md`
- `contracts/contract-delta.md`
- `system/architecture.model.json`
- `system/architecture.md`
- `system/runtime-topology.md`
- `system/messaging-subject-map.md`
- `system/adr-026-last-trade-close-versioned-immutable-snapshot.md`
- `system/adr-027-jetstream-event-chain-orchestration.md`
- `system/adr-028-producer-consumer-split-failsafe.md`
- `generation/generation-hook.md`
- `generation/implementation-status.md`

Target runtime behavior:

- Producer runs inside `trade-processor`, reading the existing price feed and writing its own
  versioned tables.
- Consumer runs inside `position-service`, subscribing to the gate event and writing its own
  immutable results table.
- Everything else (deploy/runtime harness, observability stack) is inherited unchanged from
  `YU05-post-trade-compliance`.
