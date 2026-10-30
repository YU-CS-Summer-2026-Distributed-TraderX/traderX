# History store plan

## Goal

Keep every order event and every trade side from `TRADERX_FEED` at the full 400K orders/s (1M rows/s), for blotter
history, TCA and the regulatory/EOD export, beside MariaDB's current state.

## Workstreams

1. Bake-off of ClickHouse, QuestDB and KDB-X Community on one rig with one input and one feed (388ae266).
2. The feed prototype for the winner, ClickHouse: durable per-partition consumers, ack to the store's durable
   watermark, exactly once by the store's own max(seq) (388ae266).

## Key decisions

ClickHouse, for the reasons in `docs/engineering/history-store-bakeoff.md`. The store is the cursor; a restart
recreates each durable at cursor + 1 (FR-HST-03). Root-owned under `scripts/history-store-bakeoff/`; not rendered
or deployed.

## Exit Criteria

SC-HST-01 and SC-HST-02 in [spec](spec.md), met on the bake-off rig 2026-10-29.
