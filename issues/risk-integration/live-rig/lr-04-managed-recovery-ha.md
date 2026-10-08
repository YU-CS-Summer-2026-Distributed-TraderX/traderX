# LR-04 — Managed projection recovery across actual failover

Updated: 2026-10-07. Status: blocked on supported recovery profile. Owner: unassigned validation lane; coordinator maintains queue.

Parent work: [RI-06/21/26](../06-recovery.md). This entry tracks acceptance evidence; it is not a separate implementation contract.

## Target and revision

Rig: Fresh owned local three-member rig with disposable SQL/archive storage.

Candidate: Current compatible all-member build; exact supported writer/reader and run profile must be recorded. Pin the exact tested commit, generated source, image and configuration before execution.

## Existing evidence

Earlier disposable single-member recovery, real SQL and Desk proofs were reviewed. They do not establish three-member HA or arbitrary retained upgrade compatibility.

## Required live scenario

Under the supported profile, book attributable orders, lose SQL/API/member separately, recover to the chosen run and reconcile trades/positions/cost basis/cursor. Promote a recovered follower and fail over again; check stale worker fencing and Desk run attribution.

## Acceptance and failure controls

Nonzero pre-fault fixtures and exact post-recovery IDs/state required. Record snapshot plus journal tail, applied sequences, authoritative run identity, SQL cursor and positions. Equal empty books or unreachable members cannot pass. No destructive reset of retained user rigs.

## Dependencies

RI21/26 supported persistence/upgrade profile and recovery procedure must be agreed before fault execution; fresh fixtures do not prove historical retained migration.

## Result record

- Claim/worktree/runner: pending.
- Tested revisions, images and rig identity: pending.
- Commands, actual outcomes, artifacts and negative controls: pending.
- Verdict: not executed for this entry.
- Cleanup and remaining qualification: pending.

Keep failed and inconclusive attempts with their evidence. Complete only the scenario actually exercised; record any untested tier separately.
