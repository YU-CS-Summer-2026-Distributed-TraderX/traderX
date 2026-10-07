# TraderX and risk-engine work queue

Updated: 2026-10-07. Maintainer: TraderX coordinator. Source changes below are integrated locally; runtime/financial qualifications remain explicit.

This is the maintained queue from the professor meeting, mentor resources, integration discussions and September 18 demo findings. It tracks work; the spec packs own implementation contracts. Creating this queue does not authorize implementation, deployment, data acquisition or cross-worktree edits.

## Priorities and dependencies

| ID | Workstream | Current status / next step |
|---|---|---|
| RI-01 | [Order types](01-order-types.md) | Seven types integrated and locally exercised; deployment-profile performance remains deferred to RI-13 |
| RI-02 | [Risk container](resolved/02-risk-containerization.md) | Done: original local packaging scope archived; current engine upgrade is RI15 |
| RI-03 | [Risk pipeline](03-risk-pipeline.md) | Synthetic single-portfolio milestone done; broader service integration and workers in RI-15/16 |
| RI-04 | [Contract](04-integration-contract.md) | RI16 EOD service corrections reviewed in isolated engine clone; engine handoff/activation and portfolio contract remain |
| RI-05 | [Market inputs](05-market-inputs.md) | FX readiness, engine spot/curve mapping, observation-time provenance and broader conventions remain open |
| RI-06 | [Recovery](06-recovery.md) | Managed identity, catch-up, automatic recovery and Desk wiring integrated locally; retained deployment/HA acceptance remains |
| RI-07 | [Acceptance/demo](07-acceptance-and-demo.md) | Local proofs delivered; maintain full-rig repeatability, second-person demonstration and broader risk coverage |
| RI-08 | [Build/deployment](08-build-and-deployment.md) | Local CI/container gates integrated; external-engine upgrade and cloud rollout verification open |
| RI-09 | [Reference resources](09-reference-resources.md) | TreasuryDirect/OCC field and convention research queued |
| RI-10 | [Docusaurus](resolved/10-documentation-refresh.md) | Done, delivered/published by the documentation lane; user-confirmed. Archived; its existing staged addition is preserved. |
| RI-11 | [Trader Desk](11-trader-workspace-ui.md) | Combined desk and managed order actions integrated5677b007; production auth/isolation, secondary tools/OTC migration and wider acceptance open |
| RI-12 | [Self-match prevention](resolved/12-cross-account-self-match-prevention.md) | Done: local engine/group implementation archived; production identity is RI11 |
| RI-13 | [GKE performance rebaseline](13-gke-performance-rebaseline.md) | Credits-dependent isolated C4D-or-better deployment and gateway scaling/latency/throughput campaign |
| RI-14 | [Large portfolio generator/load](14-large-portfolio-generation-and-load.md) | Seeded corpus/local driver integrated at a0d6da0b; massive CPU/GPU/cloud campaign waits for credits |
| RI-15 | [Portfolio service/results](15-portfolio-service-and-risk-results.md) | New request adapter, container upgrade and accepted portfolio analytics/UI queued |
| RI-16 | [Risk jobs/worker scaling](16-risk-job-lifecycle-and-worker-scaling.md) | Local identity/recovery foundation reviewed in separate engine clone; readiness/backpressure and worker scaling remain |
| RI-17 | [Interrupted proofs leave borrowed rig state behind](resolved/17-interrupted-proof-cleanup.md) | Done locally: journaled cleanup and retained rollback refusal; 24 combined checks passed. |
| RI-18 | [STP image freshness depends on file timestamps](resolved/18-stp-image-content-freshness.md) | Done locally: content/role/payload provenance replaces mtimes; 23 combined checks passed. |
| RI-19 | [Forward reconciliation proof mutates an out-of-window trade](resolved/19-reconciliation-proof-subject.md) | Done locally: attributable SQL/live subjects and owned orphan cleanup; 55 combined checks passed. |
| RI-20 | [Deploy and proof paths do not verify intended image contents](20-deployment-image-provenance.md) | Offline image admission integrated; immutable deployment pins/provenance and rollout verification open. |
| RI-21 | [Managed identities and SQL recovery need a coordinated deployment path](21-managed-recovery-deployment-and-dr.md) | Replay anchors integrated; managed startup, SQL DR/migrations and retained deployment open. |
| RI-22 | [OTC rejection audit and risk/admission readouts are missing](resolved/22-audit-and-admission-observability.md) | Done locally: gauges, admin member accounts and sequenced OTC refusal audit. |
| RI-23 | [Readiness, gateway saturation and feed reconnect need stronger contracts](23-readiness-and-failover-service.md) | Readiness observations integrated; quorum/routing/gateway/feed outage policy and live proof open. |
| RI-24 | [Venue breadth and projector work need measured memory bounds](24-bounded-runtime-memory.md) | OOM exit and measured book accounting integrated; full-engine/projector bounds, liveness and recovery open. |
| RI-25 | [Tape identity, availability status and replay universe need cleanup](25-market-data-identity-and-status.md) | Structured tape status integrated; source/ticker identity and universe work open. |
| RI-26 | [Retained upgrades across historical snapshot and capacity changes lack proof](26-retained-format-upgrade-proof.md) | Blocked on supported retained writer/reader boundary and upgrade barrier. |
| RI-27 | [NATS rebind recovers subscriptions but loses EOD messages](27-eod-message-durability.md) | Blocked on durable EOD storage/regeneration and replay semantics. |
| RI-28 | [Gateway unmatched-ack counter includes expected continuation fills](resolved/28-gateway-ack-metric-semantics.md) | Done locally: bounded ACK reason metrics; ambiguity remains explicit. |

Current order: RI-17/18 proof reliability lanes are authorized now. Integrate the reviewed RI-16 engine foundation through an explicit handoff, then agree RI-15 portfolio contract. RI-19–28 record remaining triage work; RI-13 and large-scale RI-14/16 wait for credits and authorization. These priorities do not assign queued work.


## Resolved issues

Completed scopes are archived in [resolved](resolved/README.md). Broad workstreams remain here when sizing, policy, deployment or financial acceptance is still required. Older dated notes record their original state and are superseded by the current table.

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
- Shared spec bilateral approval remains unclaimed. RI-02 now has a built and locally exercised container; see its qualified acceptance below.

## Existing plans

- [Shared integration spec draft](../../docs/risk-integration/spec-kit-draft/README.md)
- [Earlier broad implementation backlog](../../docs/risk-integration/04-yaakov-backlog.md): reconcile items individually; do not restart completed work.
- [Local pricing acceptance](../../docs/risk-integration/local-pricing-acceptance.md)
- Container pack: sibling checkout `/Users/yaakov/dev/jax_risk_engine/risk-engine-container-spec/README.md`; it stays outside TraderX state generation.

September 23: proposed Codex RI-02 / Claude RI-01 assignments await user confirmation. No board assignment or implementation lane started.

2026-09-23 structure decision: feature specs now live under [YU18-risk-integration/components](../../specs/YU18-risk-integration/components/README.md). Shared files/generation stay in the state parent; external engine implementation remains outside TraderX.

Structure migration: [completed local verification](yu18-component-migration.md), including 138 source and 138 generated tests. New component feature implementations remain queued.

20260923T181555Z: RI-01 assignment posted to Claude with user authorization; awaiting claim/spec review. Codex RI-02 remains paused.

2026-09-23T18:27:49.242810+00:00: User authorized RI-02 start; task 01a0cf85-d1f0-7463-808e-29ae66caaf54 dispatched. This supersedes the earlier paused status.

September 23 review: RI-02 local packaging accepted; RI-01 spec returned with four corrections. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri01-ri02-20260923/review.md`. No merges, pushes or deployments.

20260923T195429Z: User authorized all seven professor-list order types together, including lifecycle instructions, in Claude RI-01. The previous stop/stop-limit-only milestone is superseded. Board: `20260923T195429Z-coordinator-order-types-full-scope.txt`.

20260923T214226Z: RI-01 expanded design at ecc40169 reviewed; earlier findings resolved in spec, four new correctness findings returned. No new order-type runtime delivered. See RI-01 review evidence.

20260923T231403Z: RI-01 design revision 3 reviewed; policy-independent implementation cleared with narrow corrections. No new runtime delivery yet. User asked to resolve STOP reservation policy.

20260923T231432Z: User approved stop-price reservation while pending and trigger-time revalidation (trailing: admission stop level), with documented legacy gap limitation. OPEN-D5 resolved; dependent-policy hold lifted. Claude may proceed with all seven types under r3 review requirements. Board: `20260923T231432Z-coordinator-stop-policy-approved.txt`.

2026-09-23: User authorized RI-03 Codex lane. App worktree/task creation queued as client-new-thread:cbd02f05-a6b5-40fe-a96c-3514e60b7851. Assignment HANDOFF-CODEX-RISK-PIPELINE.md; board 20260923T234357Z-coordinator-risk-pipeline-assignment.txt. Base d6ca3330. Single-portfolio local HTTP-container/intake/Risk-UI proof; four upstream contract failures retained; no cloud or parallel workers. Lane must post exact checkout CLAIM and directly message coordinator 01a0aa96-c6ad-7931-8ac5-bf456a7d750b on completion.

20260924T003145Z: RI-03 local synthetic single-portfolio milestone integrated as `3244f7eb889dd0c893926915785c6899f3224160`; broader distributed/service work remains open. See RI-03 acceptance evidence. No push/deploy.

20260924T010239Z: RI-01 implementation reviewed at0ede1370; three reproduced correctness defects and reported latency regression returned to Claude. Not integrated.

20260924T015029Z: User authorized dedicated Codex RI-01 latency lane from 88f3a27f. Isolated checkout/claim pending; scope controlled reproduction and safe optimization, then evidence to coordinator. Handoff: shared coordination/eod-integration/HANDOFF-CODEX-ORDER-TYPES-LATENCY.md. Integration remains held; no Claude assignment.

20260924T021245Z: Codex latency lane delivered documentation-only 53bc3bec; coordinator verified413 evidence hashes and paired runtime statistics. JDK21 overhead not reproduced, JDK25 inconclusive; no safe optimization retained. SC-OT35 still open, integration held. Next recommendation: controlled Linux/Temurin21 deployment-profile comparison with agreed bound. No new lane started. Evidence: `coordination/eod-integration/review-evidence/ri01-latency-20260924T015157Z/review.md`.

20260924T031900Z: User deferred GKE-profile latency until credits, leaving SC-OT35 unverified and clearing the local integration hold. Authorized new Codex lane: combine reviewed order-types53bc3bec with integration3244f7eb in isolated checkout, validate combined output, then audit/add missing CI/container checks (RI-08). Owner/CLAIM pending. Coordinator retains final integration checkout writes; all dirty files preserved. Handoff: shared coordination/eod-integration/HANDOFF-CODEX-INTEGRATION-CI.md.

20260924T034152Z: Coordinator reviewed and integrated lane2f8359bd at `61b4c45e04ac53a461a985088d46d19603cbc11f`; acceptance documentation `66baef7556943b4eff8c9d62b770824eaab5ccc6`. All400 evidence hashes verified, five CI gate controls and component validator independently pass;57 delivered files exact,11 pre-existing dirty/untracked files preserved through integration. RI-01 locally integrated; latency user-deferred/unverified. RI-08 local CI/container milestone accepted; hosted CI, external engine checkout wiring and deployment remain open. No push/deploy. Evidence: shared coordination/eod-integration/review-evidence/integration-ci-coordinator-20260924.

20260924T164105Z: User authorized split from66baef75: Codex RI-06 identity/recovery and Claude RI-07 local acceptance/demo. Dedicated checkout CLAIMs pending. Boundaries and evidence requirements: shared HANDOFF-CODEX-RECOVERY.md and HANDOFF-CLAUDE-DEMO-ACCEPTANCE.md. Local disposable proofs allowed; no retained-rig mutations/GKE/push/deploy. Coordinator owns final incorporation; latency deferred.

20260924T170053Z: RI-06 bounded receipt-directory/epoch custody fix locally accepted and integrated `1a25a72024e4d26c28c2a3d105c82d0d68b06901` (source5ba9d584). Independent9 recovery tests pass;28 evidence hashes verified;12 incorporated files exact and13 existing dirty/untracked files preserved. Overall PARTIAL: first attribution remains operator-supplied; trade-ID/retained-SQL collision, epoch-scoped positions and persisted platform identity unresolved. Migration proposal reviewed as design, not authorized activation. No new lane/push/deploy. Evidence: shared ri06-coordinator-20260924/review.md.

20260924T171148Z: User authorized coordinator-reviewed migration implementation. Assigned existing Codex recovery lane from5ba9d584; immutable run descriptor, legacy replay compatibility, conflict-aware dedup, epoch-scoped projections and crash-safe fresh transition required. Handoff: shared HANDOFF-CODEX-EPOCH-MIGRATION.md. Local disposable validation only; no production activation. Claude UI/demo ownership preserved; exact expanded CLAIM pending.

20260924T181131Z: RI-07 delivered5a436b0b; coordinator verified62 evidence hashes but requests proof corrections R1 known-gap exit0, R2 failed SQL as empty, R3 truncated digest and account ownership. Integration held. F1 typed gateway buffer64/96 blocker assigned narrowly to active Codex recovery owner; F3 ratchet egress remains open. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri07-coordinator-20260924/review.md`.

20260924T182201Z: F1 typed gateway scratch-buffer fix integrated `9931b3e5ab87bce2c554f72c77ffc1257f78689b` from narrow77f851b1. Independent3 encoding+1 actual consensus gateway tests and5 allocation gates pass on integration sources. RI-06 unfinished work excluded. RI-07 proof corrections R1-R3 and F3 remain open; Claude may rerun on committed fix after coordination. No push/deploy.

20260924T190207Z: RI-07 corrections R1-R3 reviewed and integrated `cbdd952bd55e439aa22a41509d13ec7bf7739cdb` fromab68b85a.13 control tests independently pass;33 hashes verified;21 files exact with13 prior files preserved. Supplied16 MariaDB controls and two30/30 ordinary live runs reviewed. F1 integrated, F3 remains open and script correctly exits3 INCOMPLETE. PRICE_MISSING/preset label and second-person reproduction remain open; no GKE/push/deploy.

20260924T190948Z: Migration dfd0a816 reviewed; integration held for M1 cross-scheme epoch/order-ID namespace reuse regression and M2 connected real consensus/SQL transition proof. 163 hashes verified,15 provisioning controls independently pass. Findings posted to existing recovery lane; no production activation or new cloud permission.

20260924T193156Z: RI-06 local run/projection migration accepted and integrated `72870ca9c466ebc9798db47ced9e9c03824fab45` after M1/M2 corrections. Independent15 Python+98 trade unit+19 realMariaDB tests pass;217 evidence hashes verified,66 files exact,13 prior files preserved. Connected single-member old/new migration and separate3member recovery evidence reviewed. Not activated: managed UI, retained rollout, missing-event repair and broader HA remain open; latency deferred. No push/deploy. Evidence: shared ri06-migration-coordinator/review.md.

2026-09-24 coordinator: F3 trailing-stop publication accepted and integrated at 4c3b9c59. Independent 7 engine/replay + 2 MariaDB tests passed. Supplied local-live 31/31 twice reviewed with temporary C1 manifest workaround; fresh unmodified YU18 startup remains blocked by C1, correction assigned to recovery lane. No deployment or latency claim.

2026-09-24 coordinator: C1 ConfigMap blocker corrected and integrated at 920a2fff; independent100 unit +2 generated-SQL MariaDB tests passed. Fresh/retained schema paths verified locally; Kubernetes startup not rerun. Supersedes earlier C1-open note; no deployment.

2026-09-24 coordinator: combined local acceptance reviewed; provenance+guide integrated at 30800162. Fresh trading readiness16/16, order cases31/31 and already-open trailing UI verified by lane. Risk flow passed only after provenance refresh, with original fixed synthetic bill; not live-trade pricing. Coordinator independently reran official exporter/frozen/golden/six-mock proof. Evidence: coordination/eod-integration/review-evidence/local-acceptance-920a2fff-20260924. Runtime cleaned; managed UI/event recovery remain open; GKE held pending credits/authorization.

2026-09-24 coordinator: O1/R1 explicit managed archive catch-up integrated at 3f8db417;129 independent generated unit/realSQL checks passed. Actual outage/restart proof reviewed from lane. Requires explicit migration/invocation, complete archive and quiet boundary; unknown balances/missing retained positions refuse. Not automatic/background or deployed. Authoritative active-run reader integrated; UI adaptation/review pending. Cloud hold unchanged.

2026-09-24 coordinator: managed UI integrated at 29ad72cb with R1-R4 fixes. Authoritative pointer, scoped/history reads, subscriptions, fail-closed timeout and Admin context covered;125 Angular+15node checks and build passed independently. Supplied27fixture/21real-transition browser checks reviewed, not rerun after small R4 correction. No retained/cloud activation; seeded-history attribution remains separate.

2026-09-25 coordinator: combined managed recovery/UI acceptance and timestamp precision correction integrated at 5ceb0be3. Independent102service+17MariaDB+127Angular tests passed; supplied58-check live proof reviewed. Initial unchanged SQL precision refusal preserved; corrected scenario includes outage catch-up, unchanged retry, selected-run transition/history and notifications. Three single-member disposable runs, not HA or full-stack/cloud deployment. Existing explicit-recovery limitations remain.

2026-09-25: Added user-requested RI-10 documentation refresh and RI-11 trader/admin workspace redesign. Preserve published performance numbers and the existing demo console. These entries record scope; no implementation lane or deployment started.

2026-09-25: Automatic recovery accepted locally and integrated as `785823af` from Claude b5b12bbb + ddc3f4cf. R1 cost-basis and R2 position-notification review defects corrected. Independent102 unit +53 SQL tests passed,316 delivery hashes verified, regenerated source parity and9 component packs checked. Supplied live crash/fencing/trading proof reviewed, not rerun by coordinator. Default off; no retained/GKE activation or push. Remaining: genesis replay per page, below-cursor corruption not rechecked, transition VERIFY max_allowed_packet limit. Evidence: coordination/eod-integration/review-evidence/automatic-recovery-coordinator/review.md (parent workspace).


2026-09-25: RI-11 combined desk connected to local rig at e848f71e; original UI retained as More → Demo console. See 11-trader-workspace-ui.md for validation and remaining authentication/rig compatibility work.


2026-09-25 workspace update: b72d1398 adds username workspaces, server-checked optional admin password, new SQL accounts with engine admission, fresh-price autofill and sortable Markets columns.31 UI+8 server/control tests pass; live browser verified account65000 creation/admission. Existing-account repair awaits explicit approval after automatic review rejection; replacing old YU17 rig for typed orders awaits reset/recovery decision. No existing-account repair/reset/cloud/push performed.

2026-09-25 local rig update: user-approved fresh YU18 and eight-account admission completed; e20f1027 integrated. Seven types pass31live cases;32Angular+6Node and16readiness pass. UI4320 and Demo console4321 running. Existing-order mutations still require managed run identity. Evidence: coordination/eod-integration/review-evidence/desk-yu18-rig/.


2026-09-30: Desk managed order management accepted and integrated at 5677b007 (43ceaf9e plus R1 suspended-cancel correction). Account/run checked server route and receiving-gateway identity fence; stale reads/context guarded. Independent 16 Node + 40 Angular tests pass; 87 evidence hashes and source/generated parity verified. Reviewed 79 generated tests and 29 live checks covering partial fill, replace/cancel, retained restart, archive catch-up and managed transition; coordinator did not rerun live proof. Single-member only, not HA/full-platform readiness. Limit-only inline replacement; production authentication excluded. Retained unmanaged rig not activated. All rigs remain stopped per lane shutdown evidence. No push/cloud. Evidence: coordination/eod-integration/review-evidence/desk-managed-recovery-20260930 and desk-managed-recovery-r1-20260930 in parent workspace.

2026-10-07: Reconciled summary with accepted5677b007 and RI-10 delivery awaiting review. Added RI-13–16 per user request and Alex audit. Historical notes below/above retain their original dates; runtime statements there are not current observations. No cloud provisioning, new lane, push or commit authorized by this backlog edit.

2026-10-07 correction from Yaakov: RI-10 was delivered and published by the other task. This supersedes the earlier pending-review/unpublished status. Publication is user-confirmed; no deployed revision or merge ancestry independently verified in this correction.

2026-10-07: Added RI-17–28 from the 45-file open-issue triage. Related reports are grouped without restarting completed work. See [the full mapping and evidence limits](open-issue-triage-2026-10-07.md). User authorized Sol 6.1 High lanes for RI-17 and RI-18 only; remaining new entries are queued. Existing staging preserved.

2026-10-07 lane routing: RI-17 chat `01a1177e-9ffb-75b1-b0e7-5a53ea808483`; RI-18 chat `01a1177f-0d06-79f1-90ab-43b103f4cca3`; both `gpt-6.1-sol` / `high`, owned-checkout claims pending. Coordinator reviews delivery before integration.


2026-10-07 class-window local review:13 isolated delivery slices are reviewed and await controlled integration. Canonical remains `a0d6da0b`; staging and user files are preserved. Scope includes proof cleanup, image freshness/admission, reconciliation subjects, replay anchors, tape status, ack metrics, risk gauges, readiness diagnostics, account readouts, OTC refusal audit, projector OOM exit and book-memory accounting. Exact commits/dependencies, focused evidence and remaining limitations are in `/Users/yaakov/dev/lmax/coordination/eod-integration/CLASS-WINDOW-20261007.json`. These are local/source or generated checks, not deployment, HA, throughput or financial acceptance. No implementation lane remains active; storage/upgrade decisions and live/deployment-profile work stay queued.

2026-10-07 integration: all 13 reviewed code/spec deliveries landed in `b0b4c332`. Combined555 focused cases and the console production build passed. RI10, RI17, RI18, RI19, RI22 and RI28 are archived; RI20/21/23/24/25 remain partial, RI26/27 await decisions. No push, deployment, retained rig or financial validation was performed. Existing staged queue additions remain pending, including RI10 at its new resolved path.

October 7 accounting correction: eight scoped entries are archived (RI02/10/12/17/18/19/22/28). The initial six omitted the older completed container and self-match scopes. RI01 has all seven functional types integrated but retains explicit performance/HA acceptance; RI03/06/11/14/16 likewise contain delivered milestones. These are workstreams rather than 28 independent unfixed bugs. Stale top headers/next actions are reconciled; historical planning lists remain dated context.
