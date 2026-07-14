# Feature Pack: YU03-in-memory-risk-gateway

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Implemented
Track: `architecture`
Lineage role: `optional`
Previous state: `YU02-lmax-kubernetes`

This pack adds a two-tier pre-trade risk admission gate to the LMAX BLP on top of the
`YU02-lmax-kubernetes` baseline, forward-porting the pre-k8s `in-memory-risk-gateway` design onto
the YU02 runtime.

Primary intent:

- screen every order, batch, and market trade at an in-process Gateway replica with no synchronous
  REST/DB lookup on the admission path,
- make the authoritative decision in the single-writer BLP — an ordered pipeline that checks and
  reserves exact aggregate exposure in global sequence order,
- keep control events (account, security, policy, restriction, kill switch) in the global journal
  so every decision replays deterministically from snapshot + journal,
- surface stable, specific rejection reasons through the API and the UI.

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
- `system/architecture.md`
- `system/runtime-topology.md`
- `system/messaging-subject-map.md`
- `system/adr-018-two-stage-validation.md`
- `system/adr-019-watermarked-replica-bootstrap.md`
- `system/adr-020-control-events-in-global-journal.md`
- `generation/generation-hook.md`
- `generation/implementation-status.md`

Target runtime behavior:

- Gateway screening and the authoritative BLP decision both run inside `order-matcher`; all changes
  are order-matcher runtime overrides.
- The `/risk/control/*` admin API administers versioned controls; restricting a security cancels its
  resting orders via sequenced CANCEL events.
- Everything else (deploy/runtime harness, database, observability stack) is inherited unchanged
  from `YU02-lmax-kubernetes`.
