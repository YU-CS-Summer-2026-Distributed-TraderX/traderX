# Feature Pack: YU04-durable-control-feeds

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Implemented
Track: `architecture`
Lineage role: `optional`
Previous state: `YU03-in-memory-risk-gateway`

This pack adopts ADR-019's watermarked-snapshot-plus-buffered-deltas replica bootstrap protocol,
replacing YU03's one-shot REST bootstrap with real durable outbox feeds from `account-service` and
`reference-data` into NATS JetStream.

Primary intent:

- publish account and security control changes from a transactional outbox — written in the same
  transaction as the business record — to per-source durable JetStream streams,
- rewrite `order-matcher`'s `ReplicaBootstrap` to run the ADR-019 subscribe-buffer-snapshot-catchup
  protocol per source, marking the Gateway ready only once every source is caught up,
- detect and recover from gaps, version regressions, and epoch changes by quarantining and
  re-bootstrapping the affected source only,
- do all of the above without touching the BLP decision path, journal/replication wire format, or
  snapshot format.

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
- `system/adr-021-transactional-outbox-jetstream-feeds.md`
- `generation/generation-hook.md`
- `generation/implementation-status.md`

Target runtime behavior:

- `account-service` and `reference-data` each write a control outbox row alongside their business
  write and publish it, in version order, to their own JetStream stream.
- `order-matcher` bootstraps each source's replica image from a verified watermarked snapshot plus
  buffered deltas, then consumes live; a fault on one source quarantines and re-bootstraps that
  source only.
- Everything else (deploy/runtime harness, BLP admission pipeline, observability stack) is inherited
  unchanged from `YU03-in-memory-risk-gateway`.
