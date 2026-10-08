# LR-07 — GKE throughput, latency and resource isolation rebaseline

Updated: 2026-10-07. Status: deferred: credits and cloud authorization. Owner: unassigned validation lane; coordinator maintains queue.

Parent work: [RI-13](../13-gke-performance-rebaseline.md). This entry tracks acceptance evidence; it is not a separate implementation contract.

## Target and revision

Rig: GKE deployment with agreed C4D-or-better isolated CPUs and gateway scale.

Candidate: Reviewed compatible release at campaign time; historical numbers remain dated baselines. Pin the exact tested commit, generated source, image and configuration before execution.

## Existing evidence

No recent representative GKE performance campaign. Unit/allocation/local rig results do not replace deployment-profile measurements.

## Required live scenario

Follow RI13: isolated matcher/member CPUs, gateway scaling, calibrated load, throughput and coordinated-omission-safe latency across sustained load and recovery conditions. Measure bottlenecks and memory with exact resource/configuration records.

## Acceptance and failure controls

Record image IDs, CPU isolation/machine/node/network/JVM configuration, workload and achieved rates, histogram methodology, failures and trial variability. Preserve historical published measurements with their dates; no extrapolation from localhost.

## Dependencies

Credits, explicit cloud provisioning/deployment authorization and agreed campaign/resource profile. No GKE start is authorized by this queue.

## Result record

- Claim/worktree/runner: pending.
- Tested revisions, images and rig identity: pending.
- Commands, actual outcomes, artifacts and negative controls: pending.
- Verdict: not executed for this entry.
- Cleanup and remaining qualification: pending.

Keep failed and inconclusive attempts with their evidence. Complete only the scenario actually exercised; record any untested tier separately.
