# RI32 — Move trade history off MariaDB into time-series tiers (after the conference)

Updated: 2026-11-01. Status: open, not started. **Do not start before the 2026-11-05 conference**: the demo build stays
as it is. Owner: yaakov + coordinator. Spec: `specs/YU18-risk-integration/components/trade-history-tiers/`
(FR-THT-01..07), decision ADR-102.

Problem: every trade side is a MariaDB row. That append-only table is the read model's write ceiling (89.7M sides
unprojected on 2026-10-30; one feed stream ~250K acked/s) and grows without bound. The demo's KDB-X tab runs over a
synthetic 5M-trade history, not the system's trades.

Direction: MariaDB keeps current state (positions, open orders, accounts). ClickHouse (`history-feed`, ADR-098,
already exactly-once on kind) becomes the trade history's system of record on GKE, with older partitions tiered to
GCS. KDB-X becomes the analytics tier fed from the same streams. Both are projections of the cluster log.

Acceptance: the spec's SC-THT-01..04: 400K orders/s with MariaDB writing current state only; 1.5B trade sides
queryable in under 1 s cold per account in both stores; a GCS-moved partition answers identically; the restore gate
checks the history store and refuses a deleted or duplicated row. Every SQL `trades` reader moved before the table
stops being written. Production steps only through admission and a yaakov-confirmed bring-up.
