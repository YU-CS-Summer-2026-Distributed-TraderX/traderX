# Control feed republish

Status: integrated in code 2026-10-27 (`3b10e7e2`), on production `traderx` since roll 4 (2026-10-28, `00b31d33`; rollout record §13); before it, production was repaired by
hand (rollout record §12). Decision: [ADR-085](../../system/adr-085-control-stream-audit.md). Base requirement:
YU04 FR-IMRG36.

Sources:

- YU04 `generation/runtime-overrides/reference-data/src/stocks/stocks-outbox-publisher.service.ts`
  (`auditStream`), `jetstream-control-feed-publisher.ts` (`streamState`), `stocks-control-outbox.repository.ts`
  (`publishedCount`, `markAllUnpublished`), `control-feed-publisher.interface.ts`.
- `stocks-outbox-publisher.service.spec.ts`: the stream-audit cases.

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
