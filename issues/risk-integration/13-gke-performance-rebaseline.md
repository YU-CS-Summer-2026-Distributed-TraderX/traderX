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

# RI-13 — GKE throughput and latency rebaseline

Updated: 2026-10-07. Status: deferred until credits and execution authorization. Owner: unassigned. Requested by Yaakov. Local planning can precede credits; this entry does not authorize provisioning.

## Outcome

Measure the current trading system on the intended isolated deployment after the many changes since the earlier measurements. User reports the last representative measurement was almost three months ago; identify its exact revision, workload and hardware before comparisons. Preserve historical published figures and add a dated new baseline.

## Deployment profile

- Interpret the requested “CD4” as C4D pending confirmation; evaluate C4D or a measured better fit at execution time. Check region/GKE compatibility, quota, pricing and granted credits. No unmeasured “better” hardware claim.
- Dedicated placement for each consensus/order-matcher member and other latency-critical services that require it; separate load generators, gateways, SQL/projections, market feeds and observability from those cores. Identify actual service/thread needs before allocating.
- Specify Guaranteed resources and supported static CPU-manager/full-core policies, CPU affinity, SMT siblings, NUMA, OS/IRQ housekeeping and memory. Verify actual cpusets/topology, not just YAML requests. A dedicated pod, exclusive guest CPU and sole-tenant physical host are different guarantees; record exactly which applies.
- Exercise the supported maximum gateway configuration plus a scaling sweep (one, increasing counts, maximum). Derive the real limit from session/routing/configuration constraints. More gateways do not multiply a single ordered consensus engine's capacity.
- Pin source/image/JDK/OS/machine/network/storage versions. Use the current three-member architecture; do not resurrect the retired single-BLP rig as the default.

## Workload and measurements

- Reuse and audit existing benchmark tools first. Distinguish in-process engine, gateway/consensus acceptance, HTTP/FIX ingress where supported, execution and SQL projection completion.
- Predeclare mixes of seven order types, cancels/replaces, partial fills, book depth, instruments/accounts, hot-symbol contention and balanced flow. Use dedicated synthetic accounts and explain risk/STP rejections separately from capacity failures.
- Constant offered-rate sweeps with intended-send timestamps, warmup, repeated runs and unsaturated/saturation/overload rows. Report p50/p90/p99/p99.9/max, offered/accepted/executed/projected throughput, errors/rejections, generator drops, queue depths, CPU/GC/allocation/network/disk and uncertainty. Do not hide coordinated omission or omit failed requests.
- Run generators on separate capacity near the system, not through kubectl port-forward. Verify the generator is not limiting results. Keep microbenchmarks distinct from complete path measurements.
- Check agreement, no lost/duplicate trades and correct recovery before and after soak/restart cases. Ordinary throughput and degraded/failover performance have separate results.
- Compare old/new code on matched hardware where compatible; label hardware changes separately. Establish an explicit acceptance bound for the deferred RI-01 SC-OT35 performance question.
- Preserve raw evidence and a repeatable command manifest. Define budget/duration/cleanup before provisioning; no standing permission to delete retained state or the risk-extract bucket.

Next: design matrix, determine supported gateway ceiling and CPU placement, inventory prior evidence, estimate cost. Cloud run waits for credits and approval.

References: https://docs.cloud.google.com/compute/docs/general-purpose-machines ; https://docs.cloud.google.com/kubernetes-engine/docs/how-to/node-system-config ; https://kubernetes.io/docs/tasks/administer-cluster/cpu-management-policies/
Related: RI-01, RI-06, RI-07, RI-08; separate engine workload campaign RI-14.
