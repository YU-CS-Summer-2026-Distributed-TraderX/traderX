# TraderX and risk-engine work queue

Updated: 2026-09-23. Maintainer: TraderX coordinator. Implementation owners remain unassigned unless explicitly recorded in a task and on the shared board.

This is the maintained queue from the professor meeting, mentor resources, integration discussions and September 18 demo findings. It tracks work; the spec packs own implementation contracts. Creating this queue does not authorize implementation, deployment, data acquisition or cross-worktree edits.

## Priorities and dependencies

| ID | Workstream | Status | Suggested next action |
|---|---|---|---|
| RI-01 | [Order types and lifecycle](01-order-types.md) | Queued; market/limit foundation exists | Audit supported combinations; specify stop and stop-limit |
| RI-02 | [Risk containerization](02-risk-containerization.md) | Spec draft exists | Review latest engine, agree ownership, qualify local CPU image |
| RI-03 | [Operational risk pipeline](03-risk-pipeline.md) | Partial foundations | Connect one portfolio through the agreed service boundary |
| RI-04 | [Integration contract and engine reuse](04-integration-contract.md) | Rechecked; four contract gaps remain | Agree corrections; reconcile shared spec and acceptance |
| RI-05 | [Market inputs and coverage](05-market-inputs.md) | Partial; FX demo gap observed | Define explicit FX inputs and readiness checks |
| RI-06 | [Identity, recovery and reconciliation](06-recovery.md) | Queued hardening | Define epoch reset and read-model recovery behavior |
| RI-07 | [Acceptance, demo and UI](07-acceptance-and-demo.md) | Partial; existing tests/demo | Separate startup proof orders from manual trading |
| RI-08 | [Build and deployment automation](08-build-and-deployment.md) | Audit existing CI first | Inventory working CI, then add missing image/integration gates |
| RI-09 | [Instrument reference research](09-reference-resources.md) | Queued; links reviewed | Map TreasuryDirect/OCC material to instrument fields and tests |

Suggested critical path: RI-04 review → RI-02 local container → RI-03 one-portfolio proof → multiple-portfolio workers. RI-05/06 are prerequisites for claiming broader reliable service. RI-01 may be separately assigned; no parallel lane is started by this file. These priorities are recommendations, not deadlines.

## Maintenance contract

- Read this index before related work; consult the task and authoritative spec before editing.
- At claim, discovery, handoff and completion, update the relevant task and this index in the same change. Record date, status, actual owner/worktree, source revision, blockers, next action and evidence.
- Allowed statuses: queued, in progress, blocked (name dependency), review needed, done, deferred. Distinguish source-tested, generated-tested, exercised live and financially validated.
- Mark a checkbox complete only with evidence. Recheck old failures against a new engine revision; do not copy an old failing verdict forward.
- Keep completed items and their evidence; add dated notes rather than erasing history. Split new scope into a new RI ID and link it here.
- Link existing specs/issues instead of creating rival contracts. Older broad unchecked backlogs are context, not proof that work is absent. Keep this index and task statuses consistent.
- This is a repository maintenance workflow, not an automatic watcher. The agent/person doing the work must make the updates. Use the shared board for claims and delivery; this queue is not a mutex.

## Verified baseline and boundaries

- TraderX branch: `traderX-risk-integration`, HEAD `46e9bdd` when created; existing uncommitted edits preserved.
- Alex checkout: `992db30` rechecked September 23. Engine integration suite: 750 passed/1 skipped; proposed-contract starter: 1 passed/4 failed; TraderX pricing suite: 11 passed. See RI-04 for scope and evidence.
- Existing local acceptance, coordinator, market packaging and Risk UI are foundations, not proof of complete portfolio risk support.
- GKE was shut down September 18: all three pools zero, no node machines remaining. Restart needs a new user instruction.
- No claim here that the shared spec has bilateral approval or that a draft container has been built.

## Existing plans

- [Shared integration spec draft](../../docs/risk-integration/spec-kit-draft/README.md)
- [Earlier broad implementation backlog](../../docs/risk-integration/04-yaakov-backlog.md): reconcile items individually; do not restart completed work.
- [Local pricing acceptance](../../docs/risk-integration/local-pricing-acceptance.md)
- Container pack: sibling checkout `/Users/yaakov/dev/jax_risk_engine/risk-engine-container-spec/README.md`; it stays outside TraderX state generation.

September 23: proposed Codex RI-02 / Claude RI-01 assignments await user confirmation. No board assignment or implementation lane started.

2026-09-23 structure decision: feature specs now live under [YU18-risk-integration/components](../../specs/YU18-risk-integration/components/README.md). Shared files/generation stay in the state parent; external engine implementation remains outside TraderX.

Structure migration: [completed local verification](yu18-component-migration.md), including 138 source and 138 generated tests. New component feature implementations remain queued.
