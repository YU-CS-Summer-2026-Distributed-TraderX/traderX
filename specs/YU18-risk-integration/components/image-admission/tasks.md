# Image admission tasks

Status: implemented and tested offline, pending coordinator review. Owner: Codex RI20. Revision and evidence are in the owned RI20 delivery report; canonical integration has not occurred.

- [x] T-IA01 Reproduce baseline runner omission without rebuild and stale default-context GKE apply through fake commands (FR-IA07/08).
- [x] T-IA02 Define explicit expected slot/component owner and inspected/reviewed artifact contracts (FR-IA01/02/03/04/06).
- [x] T-IA03 Reuse accepted RI18 content primitives for intended fix artifacts; refuse pre and unsupported producers (FR-IA05).
- [x] T-IA04 Wire read-only baseline and rebuild node-reference gates before writes, preserving RI17 supervision/reset/compatibility fences (FR-IA07, NFR-IA01/02).
- [x] T-IA05 Wire explicit-target offline GKE render admission and snapshot apply separation (FR-IA08).
- [x] T-IA06 Add actual-entrypoint controls for matching/inherited/wrong/missing/malformed/mutable/ambiguous/refusal cases (SC-IA01/02/03/04).
- [x] T-IA07 Finish generated documentation parity, real local render evidence and required gates (SC-IA05).
- [ ] T-IA08 Coordinator review and controlled integration, preserving canonical user staging.
- [ ] T-IA09 Separately authorized real artifact provenance, registry/node/target verification and deployment/retained compatibility qualification.

Review evidence is local `coordination/eod-integration/review-evidence/ri20-image-admission-20261007/` in the parent workspace. Synthetic inspected records and review reports establish control discrimination only. No production image build, live Kubernetes/GKE query, node-store mutation, deployment, rollback, financial or performance acceptance is claimed.
