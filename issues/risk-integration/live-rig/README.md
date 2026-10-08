# Live-rig validation queue

Updated: 2026-10-07. Maintainer: TraderX coordinator.

This queue records behavior that still needs an actual service, container or cluster proof after implementation review. Source tests, generated tests, live execution, financial validation and deployment performance are separate evidence. A resolved code issue may still have an open live acceptance entry; link both rather than reopening completed code work.

| ID | Proof | Status | Parent work |
|---|---|---|---|
| [LR-01](lr-01-gateway-lifecycle.md) | Gateway timeout and permit lifecycle | queued | RI-23 |
| [LR-02](lr-02-feed-reconnect.md) | Feed reconnect and symbol registration | queued: candidate459de827 reviewed; established live proof open | RI-23 |
| [LR-03](lr-03-risk-container-pipeline.md) | Risk container lifecycle and TraderX connection | Stage A passed for image24206883/profile; Stage B blocked | RI-15/16 |
| [LR-04](lr-04-managed-recovery-ha.md) | Managed projection recovery across actual failover | blocked on supported recovery profile | RI-06/21/26 |
| [LR-05](lr-05-deployment-artifacts.md) | Intended image and deployment artifact verification | blocked on reviewed immutable artifacts | RI-20/08 |
| [LR-06](lr-06-memory-workloads.md) | Representative engine and projector memory workloads | queued for bounded local campaign; production sizing deferred | RI-24/13 |
| [LR-07](lr-07-gke-performance.md) | GKE throughput, latency and resource isolation rebaseline | deferred: credits and cloud authorization | RI-13 |

Start with LR-01. LR-02 follows the feed delivery; LR-03 Stage A can be satisfied by the container lane's reviewed actual-container evidence. Full connected portfolio acceptance, recovery compatibility and GKE have separate dependencies. Prior proofs remain useful for their exact revisions and profiles; these rows do not claim none have ever existed.

## Maintenance rule

At every delivery and coordinator review, decide whether a live proof remains. Add or update the matching entry, or record `Live rig not required` with a concrete reason. Reuse an existing entry for the same behavior; allocate the next unused LR number for a new scenario. Include an accepted candidate revision, parent RI/spec, rig tier, observed local evidence, required faults/positive and negative controls, prerequisites and owner. Use [the template](TEMPLATE.md). The delivering lane proposes its entry; the coordinator reconciles shared indexes and records review acceptance.

Statuses: queued; waiting for reviewed candidate; blocked (named dependency); in progress; review needed; passed for named revision/profile; failed; inconclusive; deferred. Results can contain several milestones/tier verdicts. Keep completed entries and dated failed attempts; never erase proof history or relabel incomplete execution as passed. Source changes require a documented applicability check of older evidence, not automatic re-running every proof or treating all old evidence as current.

Before execution, claim the exact rig/context and inputs. Verify loaded artifacts and compatibility. Use nonempty attributable fixtures and prove actual order/price/job/SQL effects; unreachable or empty agreement cannot certify success. Save commands, raw reports/logs, timestamps/run identity, artifact hashes, negative controls, cleanup and remaining limits. Coordinator review closes only the measured scenario and revision/profile. `Pods Running`, a mocked result or passing unit tests cannot close a live entry.

This is a maintained repository queue, not an automatic watcher or authorization to start rigs, change retained data, deploy, use GKE or spend credits. Each run needs task-specific scope. Prefer fresh owned disposable local rigs where sufficient; cloud and retained-state operations remain separately authorized.

Research documents and narrow collection-copy correctness do not automatically need a rig. LR-06 exists for representative memory/sizing claims, not to invalidate the proven capped-list fix. Vendor identity mapping and financial conventions require their own contract/data validation, which a running cluster cannot supply.
