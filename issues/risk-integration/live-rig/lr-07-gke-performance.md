# October11 measured CompSci baseline — qualification remains open

User-authorized initial benchmark completed on the running project-compsci-484414/us-east1-b/traderx-demo rig: three c4d-standard-8 engine nodes plus one e2-standard-4 support node. Matcher image dependency6156ad22 is unchanged. Engine requests=limits3CPU/8Gi, JVM1–3Gi; static CPU-manager/exclusive cpusets were not qualified. A2CPU load pod shared an existing C4D node; generators were not isolated physical capacity. The exact gateway Deployment spec was saved and restored; temporary load pod removed.

Constant-arrival REST sweep (3s warmup/10s measurement, latency from intended send, including queue delay):

| Gateways | Offered orders/s | Confirmed outcomes | Engine trade rows/s | Accepted p99ms |
| --- | ---: | --- | ---: | ---: |
| 1 | 100 | all1300 accepted | 100 | 14.62 |
| 1 | 500 | all6500 accepted | 499.7 | 27.70 |
| 1 | 1000 | all13000 accepted | 999.0 | 32.72 |
| 1 | 3000 | 38926 accepted/74 unknown sockets | 2987.0 | 1162.99 |
| 3 | 100 | all1300 accepted | 99.97 | 14.64 |
| 3 | 500 | all6500 accepted | 500.1 | 57.30 |
| 3 | 1000 | all13000 accepted | 998.8 | 94.57 |

500/s repeat p99: one gateway17.49ms, three30.00ms. Three gateways shared the E2 support node; these trials do not prove a gateway architectural ceiling. Keep one gateway pending a separately approved controlled placement/resource experiment before choosing engine tuning.

The counter increments once per filled side/trade row: two rows per matched pair. At3000/s, the first harness failed to retain IDs for74 socket failures. Aggregate committed delta equaled38926, but individual unknown outcomes cannot be established. No uncertain order was retried, and no fully successful3000/s or production ceiling claim is made. All members converged with empty queues after each trial.

Two named synthetic benchmark accounts65000/65001 accumulated±46763C4DBENCH at100. Human explicitly approved offsetting only these fixtures;936 bounded orders flattened both SQL-backed positions to zero. All three members agreed atseq95855/trade rows94466/queues0. Benchmark and offset history retained; no deletion or additional EOD cut.

Raw metric/histogram/configuration/receipt evidence: parent coordination `review-evidence/compsci-gke-20261011/c4d-baseline-20261011/`. Full RI13/LR07 isolation, workload mix, sustained load/soak, failover/recovery, FIX path, SQL-latency and production resource qualification remain open. Initial REST measurements are completed only for this profile.

--- Historical planning below ---

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
