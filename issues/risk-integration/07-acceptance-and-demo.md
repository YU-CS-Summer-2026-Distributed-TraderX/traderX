# RI-07 — Repeatable acceptance, demonstrations and UI clarity

Updated: 2026-10-07. Status: in progress: local acceptance/demo foundations delivered; full repeatability, second-person demo and broader validated risk coverage remain. Maintainer: coordinator; original delivery notes below retain their dates.

Evidence: September 18 focused pricing suite passed 11 tests; banner suite passed 5. Earlier live Treasury evidence is linked from [demo guide](../../docs/risk-integration/treasury-trade-demo.md). These are bounded checks, not complete risk certification. The book banner simplification was deployed September 18; do not reassign it.

- [ ] Create a concise maintained test/demo command guide with prerequisites, expected outputs and execution level.
- [ ] Make full-rig readiness explicit: new console, trading services, algo and observability.
- [ ] Isolate startup proof orders from manual trading. Today a proof sell filled one share of the user's resting 100-share IBM buy.
- [ ] Make clean-start versus retained-state behavior explicit and reproducible.
- [ ] Show a real trade/booking through original lineage to a validated risk result without re-entering economics.
- [ ] Clarify PRICE_MISSING for missing FX; clear or update the selected preset label after terms are manually changed.
- [ ] Add bounded recovery and negative cases to demos, not just successful responses.
- [ ] Keep unsupported analytics, assumed inputs and old results visible as such.

Next: Maintain the existing proofs and establish second-person/full-rig repeatability and broader validated risk coverage.

20260924T164105Z: User authorized split from66baef75: Codex RI-06 identity/recovery and Claude RI-07 local acceptance/demo. Dedicated checkout CLAIMs pending. Boundaries and evidence requirements: shared HANDOFF-CODEX-RECOVERY.md and HANDOFF-CLAUDE-DEMO-ACCEPTANCE.md. Local disposable proofs allowed; no retained-rig mutations/GKE/push/deploy. Coordinator owns final incorporation; latency deferred.

20260924T181131Z: RI-07 delivered5a436b0b; coordinator verified62 evidence hashes but requests proof corrections R1 known-gap exit0, R2 failed SQL as empty, R3 truncated digest and account ownership. Integration held. F1 typed gateway buffer64/96 blocker assigned narrowly to active Codex recovery owner; F3 ratchet egress remains open. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri07-coordinator-20260924/review.md`.

20260924T182201Z: F1 typed gateway scratch-buffer fix integrated `9931b3e5ab87bce2c554f72c77ffc1257f78689b` from narrow77f851b1. Independent3 encoding+1 actual consensus gateway tests and5 allocation gates pass on integration sources. RI-06 unfinished work excluded. RI-07 proof corrections R1-R3 and F3 remain open; Claude may rerun on committed fix after coordination. No push/deploy.

20260924T190207Z: RI-07 corrections R1-R3 reviewed and integrated `cbdd952bd55e439aa22a41509d13ec7bf7739cdb` fromab68b85a.13 control tests independently pass;33 hashes verified;21 files exact with13 prior files preserved. Supplied16 MariaDB controls and two30/30 ordinary live runs reviewed. F1 integrated, F3 remains open and script correctly exits3 INCOMPLETE. PRICE_MISSING/preset label and second-person reproduction remain open; no GKE/push/deploy.

2026-09-24 coordinator: F3 trailing-stop publication accepted and integrated at 4c3b9c59. Independent 7 engine/replay + 2 MariaDB tests passed. Supplied local-live 31/31 twice reviewed with temporary C1 manifest workaround; fresh unmodified YU18 startup remains blocked by C1, correction assigned to recovery lane. No deployment or latency claim.

2026-09-24 coordinator: C1 ConfigMap blocker corrected and integrated at 920a2fff; independent100 unit +2 generated-SQL MariaDB tests passed. Fresh/retained schema paths verified locally; Kubernetes startup not rerun. Supersedes earlier C1-open note; no deployment.

2026-09-24 coordinator: combined local acceptance reviewed; provenance+guide integrated at 30800162. Fresh trading readiness16/16, order cases31/31 and already-open trailing UI verified by lane. Risk flow passed only after provenance refresh, with original fixed synthetic bill; not live-trade pricing. Coordinator independently reran official exporter/frozen/golden/six-mock proof. Evidence: coordination/eod-integration/review-evidence/local-acceptance-920a2fff-20260924. Runtime cleaned; managed UI/event recovery remain open; GKE held pending credits/authorization.

2026-09-24 coordinator: managed UI integrated at 29ad72cb with R1-R4 fixes. Authoritative pointer, scoped/history reads, subscriptions, fail-closed timeout and Admin context covered;125 Angular+15node checks and build passed independently. Supplied27fixture/21real-transition browser checks reviewed, not rerun after small R4 correction. No retained/cloud activation; seeded-history attribution remains separate.

2026-09-25 coordinator: combined managed recovery/UI acceptance and timestamp precision correction integrated at 5ceb0be3. Independent102service+17MariaDB+127Angular tests passed; supplied58-check live proof reviewed. Initial unchanged SQL precision refusal preserved; corrected scenario includes outage catch-up, unchanged retry, selected-run transition/history and notifications. Three single-member disposable runs, not HA or full-stack/cloud deployment. Existing explicit-recovery limitations remain.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.
