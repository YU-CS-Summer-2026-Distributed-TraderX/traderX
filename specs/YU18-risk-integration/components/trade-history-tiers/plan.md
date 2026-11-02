# Trade history tiers plan

## Goal

Trade volume stops bounding the read model: trades live in time-series stores that grow onto more disks, machines and
GCS, and MariaDB holds only current state.

## Workstreams (in order)

1. **ClickHouse on GKE** (FR-THT-02/03): render `history-feed` and ClickHouse into the production manifests, rig
   start/stop and readiness, a GCS storage policy, then a production run with both SQL `trades` and ClickHouse
   written, compared per partition.
2. **Move the readers** (FR-THT-05/06): inventory every SQL `trades` reader, move each to the history store's HTTP
   queries (FR-HST-06), replace the gate's fingerprint; the inventory test goes green.
3. **Stop writing SQL `trades`** (FR-THT-01): trade-processor writes positions only; archive the table (ADR-101 style).
4. **KDB-X analytics lane** (FR-THT-04): replace the demo's synthetic HDB (`scripts/demo-kdb/demo-hdb.q`) with a
   feed-driven write-down; gateway over several HDB processes; GCS segments if available.
5. **Scale proof** (SC-THT-01..04) at 1.5B trade sides, then the rebuild proof (FR-THT-07).

Each workstream lands with its FR rows and tests in the same change, and goes to production only through the rig's
admission and a yaakov-confirmed bring-up.
