# Live-rig validation queue

Updated: 2026-10-07. Maintainer: TraderX coordinator.

This queue records behavior that still needs an actual service, container or cluster proof after implementation review. Source tests, generated tests, live execution, financial validation and deployment performance are separate evidence. A resolved code issue may still have an open live acceptance entry; link both rather than reopening completed code work.

| ID | Proof | Status | Parent work |
|---|---|---|---|
| [LR-01](lr-01-gateway-lifecycle.md) | Gateway timeout and permit lifecycle | queued | RI-23 |
| [LR-02](lr-02-feed-reconnect.md) | Feed reconnect and symbol registration | queued:459de827 integrated; established live proof open | RI-23 |
| [LR-03](lr-03-risk-container-pipeline.md) | Risk container lifecycle and TraderX connection | Stage A passed for corrected C8 image80c885f8/profile; owned integration done; Stage B blocked/unexecuted | RI-15/16 |
| [LR-04](lr-04-managed-recovery-ha.md) | Managed projection recovery across actual failover | blocked on supported recovery profile | RI-06/21/26 |
| [LR-05](lr-05-deployment-artifacts.md) | Intended image and deployment artifact verification | Local content/endpoint milestone passed for e5adf936; mismatch/managed/cloud gates open | RI-20/08 |
| [LR-06](lr-06-memory-workloads.md) | Representative engine and projector memory workloads | queued for bounded local campaign; production sizing deferred | RI-24/13 |
| [LR-07](lr-07-gke-performance.md) | GKE throughput, latency and resource isolation rebaseline | deferred: credits and cloud authorization | RI-13 |

Start with LR-01. LR-02 follows the feed delivery; LR-03 Stage A can be satisfied by the container lane's reviewed actual-container evidence. Full connected portfolio acceptance, recovery compatibility and GKE have separate dependencies. Prior proofs remain useful for their exact revisions and profiles; these rows do not claim none have ever existed.

## Maintenance rule

At every delivery and coordinator review, decide whether a live proof remains. Add or update the matching entry, or record `Live rig not required` with a concrete reason. Reuse an existing entry for the same behavior; allocate the next unused LR number for a new scenario. Include an accepted candidate revision, parent RI/spec, rig tier, observed local evidence, required faults/positive and negative controls, prerequisites and owner. Use [the template](TEMPLATE.md). The delivering lane proposes its entry; the coordinator reconciles shared indexes and records review acceptance.

Statuses: queued; waiting for reviewed candidate; blocked (named dependency); in progress; review needed; passed for named revision/profile; failed; inconclusive; deferred. Results can contain several milestones/tier verdicts. Keep completed entries and dated failed attempts; never erase proof history or relabel incomplete execution as passed. Source changes require a documented applicability check of older evidence, not automatic re-running every proof or treating all old evidence as current.

Before execution, claim the exact rig/context and inputs. Verify loaded artifacts and compatibility. Use nonempty attributable fixtures and prove actual order/price/job/SQL effects; unreachable or empty agreement cannot certify success. Save commands, raw reports/logs, timestamps/run identity, artifact hashes, negative controls, cleanup and remaining limits. Coordinator review closes only the measured scenario and revision/profile. `Pods Running`, a mocked result or passing unit tests cannot close a live entry.

This is a maintained repository queue, not an automatic watcher or authorization to start rigs, change retained data, deploy, use GKE or spend credits. Each run needs task-specific scope. Prefer fresh owned disposable local rigs where sufficient; cloud and retained-state operations remain separately authorized.

Research documents and narrow collection-copy correctness do not automatically need a rig. LR-06 exists for representative memory/sizing claims, not to invalidate the proven capped-list fix. Vendor identity mapping and financial conventions require their own contract/data validation, which a running cluster cannot supply.

## October 8 approved integration

The user approved console candidate `8f4623d26251292a629b2e6a1c40dd9632e428ad` (including `fcba84f4`) and archival proof candidate `055581fdef5b08bf24757384c8167723384b7d97` into `traderX-risk-integration`. The console packaging task is [done and archived](../resolved/lr05-console-packaging.md); the proof drivers remain historical fixtures for the closed October 8 lease. This entry supersedes earlier awaiting-approval/source-not-integrated statements for these two candidates only. Independent full-ancestry generation, source/render parity and scoped checks are recorded under parent `coordination/eod-integration/review-evidence/review36-20261008/integration/`. No rig/container was restarted. Existing native image/connected evidence applies to unchanged build-context bytes. Broader RI/LR release, authenticated Desk, revised amd64, cloud, retained recovery, financial and sizing obligations remain open.

## October 9 local artifact and audit milestone

The [completed bounded audit corrections](../resolved/live-audit-fixes-20261009.md), approved candidate e5adf9361a35d75ab2e6233d8fba7dc90e24bd84, were exercised on fresh local kind cluster traderx-auditfix-20261009-01a11e05 with synthetic data and new PVCs. Eighty-four node-image comparisons, twenty-eight pod-image comparisons and ten served-payload hashes matched. Nineteen native console-image checks passed. Valid/forbidden FIX, encoded admin mutation, legacy epoch joins, delayed authentication startup, partial PnL and eleven healthy selected metrics targets had attributable controls. Raw hashes, failed instrument attempts and successful corrections are preserved in parent coordination/eod-integration/live-rig-evidence/20261009-review36-fixes/.

The rig was exported and removed; original fourteen containers and twenty-nine volumes were preserved. This passes the named local content/endpoint milestone. Deliberate mismatched-image admission controls, a separately accepted managed profile, revised amd64 and GKE rollout qualification remain open, so LR05 as a whole is not closed. Runtime bytes were unchanged by approved integration and its documentation closeout.


October 9: LR03 Stage A accepted for corrected image80c885f8/C8 profile, and approved owned engine target now equals e770f912. Connected Stage B is unexecuted; this does not close the whole entry or authorize a new rig. See [LR03](lr-03-risk-container-pipeline.md).
