# History store plan

## Goal

Keep every order event and every trade side from `TRADERX_FEED` at the full 400K orders/s (1M rows/s), for blotter
history, TCA and the regulatory/EOD export, beside MariaDB's current state.

## Workstreams

1. Bake-off of ClickHouse, QuestDB and KDB-X Community on one rig with one input and one feed (388ae266).
2. The feed prototype for the winner, ClickHouse: durable per-partition consumers, ack to the store's durable
   watermark, exactly once by the store's own max(seq) (388ae266).
3. The production path: the `history-feed` service on the per-partition streams, the FR-DRS-33 gap rule, the history
   queries over HTTP, a kill -9 IT and a kind smoke (ADR-098).

## Key decisions

[ADR-098](../../system/adr-098-history-store.md): ClickHouse; a separate single-writer service; the store is the cursor
(a restart or a delivery gap recreates each durable at cursor + 1, FR-HST-03); opt-in manifests, not on GKE.

## Exit Criteria

SC-HST-01..04 in [spec](spec.md): 01/02 met on the bake-off rig 2026-10-29, 03/04 locally and on kind 2026-10-30.
