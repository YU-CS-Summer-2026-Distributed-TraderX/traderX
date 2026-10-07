# RI-06 — Identity, restart recovery and reconciliation

Updated: 2026-10-07. Status: in progress: managed identities, catch-up, automatic projection recovery and Desk wiring integrated locally; retained deployment/HA and coverage remain. Maintainer: coordinator; original delivery notes below retain their dates.

September 18 evidence: engine state was fresh after zero-node shutdown; SQL retained prior records. Bring-up backed up and cleared the stale projection. The configured epoch still read 2026091601. Historical Risk results remained separately available. This identifies a lifecycle problem to design and test, not authorization to delete more state.

- [ ] Establish authoritative epoch/run identity and change rules across restart, recovery and intentional reset.
- [ ] Prevent order/trade/contract identity reuse from joining new events to prior results.
- [ ] Define persistence, checkpoint, replay and read-model rebuild policies; distinguish restart from fresh session.
- [ ] Reconcile engine fills/contracts, account positions, exports and risk results at a common boundary.
- [ ] Handle duplicate, missing, late and interrupted records without silent accounting loss.
- [ ] Clearly scope historical UI results by original run, account and valuation time.
- [ ] Exercise worker/coordinator restart and stale-result ordering using existing durable ledger mechanisms.

Next: Qualify retained deployment and broader HA/sink reconciliation; reuse the integrated managed recovery.

20260924T164105Z: User authorized split from66baef75: Codex RI-06 identity/recovery and Claude RI-07 local acceptance/demo. Dedicated checkout CLAIMs pending. Boundaries and evidence requirements: shared HANDOFF-CODEX-RECOVERY.md and HANDOFF-CLAUDE-DEMO-ACCEPTANCE.md. Local disposable proofs allowed; no retained-rig mutations/GKE/push/deploy. Coordinator owns final incorporation; latency deferred.

20260924T170053Z: RI-06 bounded receipt-directory/epoch custody fix locally accepted and integrated `1a25a72024e4d26c28c2a3d105c82d0d68b06901` (source5ba9d584). Independent9 recovery tests pass;28 evidence hashes verified;12 incorporated files exact and13 existing dirty/untracked files preserved. Overall PARTIAL: first attribution remains operator-supplied; trade-ID/retained-SQL collision, epoch-scoped positions and persisted platform identity unresolved. Migration proposal reviewed as design, not authorized activation. No new lane/push/deploy. Evidence: shared ri06-coordinator-20260924/review.md.

20260924T171148Z: User authorized coordinator-reviewed migration implementation. Assigned existing Codex recovery lane from5ba9d584; immutable run descriptor, legacy replay compatibility, conflict-aware dedup, epoch-scoped projections and crash-safe fresh transition required. Handoff: shared HANDOFF-CODEX-EPOCH-MIGRATION.md. Local disposable validation only; no production activation. Claude UI/demo ownership preserved; exact expanded CLAIM pending.

20260924T190948Z: Migration dfd0a816 reviewed; integration held for M1 cross-scheme epoch/order-ID namespace reuse regression and M2 connected real consensus/SQL transition proof. 163 hashes verified,15 provisioning controls independently pass. Findings posted to existing recovery lane; no production activation or new cloud permission.

20260924T193156Z: RI-06 local run/projection migration accepted and integrated `72870ca9c466ebc9798db47ced9e9c03824fab45` after M1/M2 corrections. Independent15 Python+98 trade unit+19 realMariaDB tests pass;217 evidence hashes verified,66 files exact,13 prior files preserved. Connected single-member old/new migration and separate3member recovery evidence reviewed. Not activated: managed UI, retained rollout, missing-event repair and broader HA remain open; latency deferred. No push/deploy. Evidence: shared ri06-migration-coordinator/review.md.

2026-09-24 coordinator: C1 ConfigMap blocker corrected and integrated at 920a2fff; independent100 unit +2 generated-SQL MariaDB tests passed. Fresh/retained schema paths verified locally; Kubernetes startup not rerun. Supersedes earlier C1-open note; no deployment.

2026-09-24 coordinator: O1/R1 explicit managed archive catch-up integrated at 3f8db417;129 independent generated unit/realSQL checks passed. Actual outage/restart proof reviewed from lane. Requires explicit migration/invocation, complete archive and quiet boundary; unknown balances/missing retained positions refuse. Not automatic/background or deployed. Authoritative active-run reader integrated; UI adaptation/review pending. Cloud hold unchanged.

2026-09-25 coordinator: combined managed recovery/UI acceptance and timestamp precision correction integrated at 5ceb0be3. Independent102service+17MariaDB+127Angular tests passed; supplied58-check live proof reviewed. Initial unchanged SQL precision refusal preserved; corrected scenario includes outage catch-up, unchanged retry, selected-run transition/history and notifications. Three single-member disposable runs, not HA or full-stack/cloud deployment. Existing explicit-recovery limitations remain.

2026-09-25: Automatic recovery accepted locally and integrated as `785823af` from Claude b5b12bbb + ddc3f4cf. R1 cost-basis and R2 position-notification review defects corrected. Independent102 unit +53 SQL tests passed,316 delivery hashes verified, regenerated source parity and9 component packs checked. Supplied live crash/fencing/trading proof reviewed, not rerun by coordinator. Default off; no retained/GKE activation or push. Remaining: genesis replay per page, below-cursor corruption not rechecked, transition VERIFY max_allowed_packet limit. Evidence: coordination/eod-integration/review-evidence/automatic-recovery-coordinator/review.md (parent workspace).

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.
