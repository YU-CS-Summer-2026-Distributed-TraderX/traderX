# ADR-101: A production fresh epoch archives the old one and resets; it does not run the FR-RI08 transition

Status: accepted 2026-10-31 (yaakov: option B "fresh epoch" for the ADR-100 member image, then "archive-and-reset
route"). Pack: [production rig](../maintenance/production-rig/spec.md) (FR-PRG-29). Relates to ADR-087 (member
volumes), ADR-096 (the rig and its gate), ADR-100 (the member change that rolls with it), and the recovery-identity
transition (FR-RI08).

## Context

ADR-100's member image changes the apply path, so its members roll together and never mixed. yaakov chose to roll it
as a fresh epoch: the member disks are wiped and the cluster starts empty. The read model and the rig's gate then
have to start over with it.

The built path for a new epoch is the FR-RI08 transition (`recovery-identity/transition.py`): a managed epoch-v1 run
with its own projection scope, admitted only after the old epoch's full history is replayed and every event found in
SQL. It cannot serve this epoch:
- About 749M of the old epoch's trade sides were never projected (the 2026-10-29 bridges-off run and the 2026-10-30
  NATS loss, recorded with `rig.sh unprojected`). Its verification refuses any missing event.
- Its replay is capped by `RECON_FULL_HISTORY_MAX`, far below 753M trades.
- It needs the old and fresh clusters running side by side, and its peer transport accepts only `localhost`.
- The production render carries none of a managed run's settings (`RUN_DESCRIPTOR_PATH`, `CLUSTER_EPOCH`, the
  gateway's projection control), and a fresh epoch-v1 core starts with admission closed until that transition opens
  it.

## Decision

The new epoch keeps the legacy id scheme (no run descriptor), and the old one is archived whole before it starts:

1. **Member disks:** `rig.sh archive-members LABEL` takes a GCE snapshot of each member disk (`traderx-LABEL-m{0,1,2}`)
   with the members stopped. The old epoch's journal stays replayable from them (a disk restored from a snapshot is
   a member's storage root). `rig.sh fresh-epoch LABEL` refuses unless all three are READY and of exactly the
   current disks, then records the old gate ConfigMaps in `rig-epoch-LABEL-history`, marks `rig-epoch` wiped, and
   deletes the claims, waiting until no claim and no disk is left.
2. **Read model:** the next `rig.sh up`, with trade-processor stopped, moves the epoch tables (`trades`,
   `positions`, `orderbook`, `order_view_snapshot`, `projection_accounting`, `projection_cursor`,
   `projection_trade_days`, `projection_recon`) into `arc_LABEL_*` in one atomic `RENAME TABLE`, and recreates each
   empty with the same definition: `LIKE`, then every foreign key `LIKE` leaves out. The archive holds every row,
   the schema's seed rows included: the new epoch's engine starts empty, so its read model does too. Accounts,
   reference data and EOD history stay. New trade and order ids restart at 1 without meeting an old row.
3. **Gate:** while `rig-epoch` says provisioned, G-pre requires every member claim gone, G0 accepts only empty
   members (nothing applied, 0 trades), G1 and G2 have nothing to replay, G3 requires no open order rows, and G4
   requires no shutdown baseline. The gate's PASS marks the epoch consumed. Outside a provisioned epoch, a missing
   baseline is now refused: it never stands in for a first bring-up.

**Policy change:** this is the one fresh-epoch path that skips FR-RI08's full-history verification. What replaces it
is the gate that passed on the old epoch's last bring-up (G2's strict replay of every member, G3 over the projected
sides minus the recorded gaps) plus the archived disks and tables, kept as they were.

## Consequences

- Epoch-prefixed ids and FR-RI08's scoped history are not used; the old epoch is read from `arc_LABEL_*` and the
  snapshots, not from a live scope. A later move to managed runs still needs the work this ADR declines.
- FR-DRS-36 cursors start unbound at 0 in the new `projection_cursor`, which needs no attestation.
- Snapshot storage is billed until the snapshots are deleted.
- Tests: `test_rig.FreshEpoch` (11; every refusal fails against the rig.sh before FR-PRG-29) and
  `test_archive_read_model_mariadb` (mariadb:11.4 with the shipped schema: identical definitions, foreign keys
  included, empty live tables, a rerun that changes nothing; control: `LIKE` alone drops the foreign key).
