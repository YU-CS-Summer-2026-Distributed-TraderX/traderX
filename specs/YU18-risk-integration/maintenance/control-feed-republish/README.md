# Control feed republish

Status: integrated in code 2026-10-27 (`3b10e7e2`), not yet deployed; production was repaired by hand the same
night (rollout record §12). Decision: [ADR-085](../../system/adr-085-control-stream-audit.md). Base requirement:
YU04 FR-IMRG36.

Sources:

- YU04 `generation/runtime-overrides/reference-data/src/stocks/stocks-outbox-publisher.service.ts`
  (`auditStream`), `jetstream-control-feed-publisher.ts` (`streamState`), `stocks-control-outbox.repository.ts`
  (`publishedCount`, `markAllUnpublished`), `control-feed-publisher.interface.ts`.
- `stocks-outbox-publisher.service.spec.ts`: the stream-audit cases.

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
