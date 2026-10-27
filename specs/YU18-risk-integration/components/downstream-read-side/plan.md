# Downstream read side plan

## Goal

Make the trade-processor projection keep up with the engine without losing events, and make lag
recoverable instead of fatal.

## Workstreams

1. Bound, count and batch the existing core-NATS projection (dddcdc86).
2. Replayable partitioned feed and in-memory order view (79b754f7).
3. Append-only trade store: JetStream log plus SQL snapshots and `projection_cursor` (33589539).

## Key decisions

[ADR-080](../../system/adr-080-downstream-read-side.md). Every path is opt-in by env; the defaults keep
today's behaviour.

## Exit Criteria

SC-DRS-01..03, measured on a dedicated c4d-standard-16 (`docs/engineering/downstream-at-400k.md` §4, §7).
