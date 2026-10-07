# RI-21 — Managed identities and SQL recovery need a coordinated deployment path

Updated: 2026-10-07. Status: in progress (replay anchors integrated; managed deployment and SQL DR remain). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Current finding

Managed RI06 code exists, but legacy GKE bring-up still parses bare numeric IDs and deletes projection rows. GCS restore rolls engine storage back without bringing SQL to the same point. Replay clock stamping still succeeds without a PVC.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [ ] Define managed GKE startup/provisioning and retained migration using explicit run descriptors, compatible binaries and additive schema migrations.
- [ ] Remove numeric-ID/wipe assumptions from the managed path while preserving a documented legacy boundary.
- [ ] Coordinate engine backup/restore and SQL projection scope/rebuild to prevent post-backup SQL orders appearing as live venue orders.
- [ ] Derive replay anchors from a persistent run identity on emptyDir tiers; refuse absent/ambiguous anchors.
- [ ] Keep legacy Spring ddl-auto decisions and older-state schema coherence distinct; preserve the existing do-not-flip ruling.

## Acceptance

- [ ] Disposable local migration/restore fixtures retain old rows and verify selected scope and run identity.
- [ ] Recovery at a backup boundary reconciles SQL beyond the engine point; forward catch-up alone is insufficient.
- [ ] No cloud activation, retained wipe or disaster restore authorized by this queued entry.

## Original reports

- [a-successful-gcs-restore-leaves-the-read-model-holding-orders-the-engine-dropped.md](../open/a-successful-gcs-restore-leaves-the-read-model-holding-orders-the-engine-dropped.md)
- [nothing-enforces-that-an-epoch-bump-and-a-db-wipe-happen-together.md](../open/nothing-enforces-that-an-epoch-bump-and-a-db-wipe-happen-together.md)
- [schema-migration-fires-only-on-pod-creation.md](../open/schema-migration-fires-only-on-pod-creation.md)
- [stamp-replay-epoch-cannot-work-on-a-tier-without-member-pvcs.md](../open/stamp-replay-epoch-cannot-work-on-a-tier-without-member-pvcs.md)
- [order-matcher-issues-ddl-against-the-shared-database.md](../open/order-matcher-issues-ddl-against-the-shared-database.md)

Next: Specify managed startup/SQL DR and migration procedures; validate the retained deployment separately.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

2026-10-07 class-window dispatch: missing/invalid storage anchor refusal, explicit target and checked stamper failures only. Retained GKE/SQL DR/migration remains queued. Handoff shared HANDOFF-CODEX-REPLAY-ANCHOR-20261007.md; exact checkout CLAIM pending.

## Class-window review, October 7

Replay-anchor subsection locally reviewed at `7e7ae3cc`: 22 coordinator-run offline helper/caller tests pass; explicit targets and mounted PVC/PV evidence required, disabled mode distinct, restoration failures visible. Controlled integration pending; no live/retained recovery or cloud proof. Broader deployment, identity, migration and SQL DR remain open.

## October 7 integration outcome

Replay stamping requires an explicit context/namespace and the mounted writable member-0 PVC with a matching bound PV. Disabled stamping is an explicit non-anchor outcome. Dry-run/apply/restart/rollout and EXIT-restoration failures are visible. Combined tests: 22 passed. GKE startup, run identity, SQL recovery/migrations and DR remain open.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.
