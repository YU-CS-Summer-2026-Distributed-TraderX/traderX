# LR-01 — Gateway timeout and permit lifecycle

Updated: 2026-10-07. Status: queued. Owner: unassigned validation lane; coordinator maintains queue.

Parent work: [RI-23](../23-readiness-and-failover-service.md). This entry tracks acceptance evidence; it is not a separate implementation contract.

## Target and revision

Rig: Fresh disposable local three-member Aeron rig; actual HTTP/binary gateway.

Candidate: bf1d13e9 (76071dc8 + 62bf333a source deliveries). Pin the exact tested commit, generated source, image and configuration before execution.

## Existing evidence

58 focused generated gateway cases pass; actual encoded mock transport, ACK/reaper and semaphore witnesses. No real consensus/election proof.

## Required live scenario

Run real submissions with a controllably stalled owner, caller timeout/interruption and bounded saturation. Prove unstarted abandoned work never appears in the applied book or audit, healthy later requests recover capacity, and successful commits reconcile by identity. Exercise election during pending work; begun/offered timeouts remain uncertain until observation.

## Acceptance and failure controls

A successful order and nonempty applied/audit witness are prerequisites. Distinguish queued retirement from start-winning uncertainty; no duplicate release, runaway queue or lost capacity. Never infer nonexecution from caller timeout or absent HTTP reply. Record actual member/gateway image IDs and all-member compatible versions.

## Dependencies

Reviewed compatible disposable rig and owned bounded fault controls; rig execution not yet assigned.

## Result record

- Claim/worktree/runner: pending.
- Tested revisions, images and rig identity: pending.
- Commands, actual outcomes, artifacts and negative controls: pending.
- Verdict: not executed for this entry.
- Cleanup and remaining qualification: pending.

Keep failed and inconclusive attempts with their evidence. Complete only the scenario actually exercised; record any untested tier separately.
