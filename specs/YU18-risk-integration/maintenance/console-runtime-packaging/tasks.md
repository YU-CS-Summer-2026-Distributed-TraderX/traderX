# Console runtime packaging tasks

- [x] Trace the seven-module static ESM closure and reproduce the omission in the original installed image (FR-CRP-01, SC-CRP-01).
- [x] Add desk-orders to the explicit runtime COPY list (FR-CRP-01, NFR-CRP-01).
- [x] Build the actual candidate and pass 14 nonempty checks, including graph/hash equality, boot and real HTTP assets/endpoints (FR-CRP-01/02, SC-CRP-01).
- [x] Preserve bridge executables and remove both owned test containers (NFR-CRP-01).
- [ ] Coordinator review and controlled candidate handoff to the live owner.
- [ ] Human-approved source integration into the canonical checkout and shared-index reconciliation.
- [ ] Live owner records the applicable LR-05 deployment acceptance; no local smoke result closes broader LR-05.
