# Console runtime packaging tasks

- [x] Trace the seven-module static ESM closure and reproduce the omission in the original installed image (FR-CRP-01, SC-CRP-01).
- [x] Add desk-orders to the explicit runtime COPY list (FR-CRP-01, NFR-CRP-01).
- [x] Build the actual candidate and pass 14 nonempty checks, including graph/hash equality, boot and real HTTP assets/endpoints (FR-CRP-01/02, SC-CRP-01).
- [x] Preserve bridge executables and remove both owned test containers (NFR-CRP-01).
- [ ] Coordinator review and controlled candidate handoff to the live owner.
- [ ] Human-approved source integration into the canonical checkout and shared-index reconciliation.
- [ ] Live owner records the applicable LR-05 deployment acceptance; no local smoke result closes broader LR-05.

- [x] FR-CRP-03/SC-CRP-02: official stable manifest selected; native arm64 Node, shell, kubectl, Python and loader all report ELF machine 183; 19 installed-image checks pass.
- [x] Mislabeled 0ada3b1b artifact rejected before startup (all inspected ELF machine 62 despite arm64 config).
- [x] Retained legitimate 59bf0ed3 amd64 artifact passes 19 checks as amd64; earlier 14-check result is retained separately.
- [x] Owned containers, anonymous config volumes and temporary binary copies removed; candidate and controls retained.
- [ ] Revised Dockerfile amd64 build validation, if that target is later needed. Current native artifact qualification is arm64 only.
