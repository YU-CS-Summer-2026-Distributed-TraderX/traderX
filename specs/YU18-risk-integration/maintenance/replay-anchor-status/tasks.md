# Replay anchor status tasks

Status: local implementation and offline acceptance complete; coordinator review pending.

- [x] Reproduce accepted-base silent success and ambient targeting with retained fake traces.
- [x] Require explicit supported target, without modifying unrelated fetch-secret resolution.
- [x] Verify actual `/data` PVC backing, Bound claim/volume identity and valid creation timestamp.
- [x] Implement explicit disabled outcome and separate storage/producer statuses.
- [x] Check producer inspection, dry-run, apply, restart and rollout failures.
- [x] Verify caller propagation and make proof EXIT restamp failure nonzero.
- [x] Add component requirements and executable offline acceptance controls.
- [ ] Coordinator review and surgical integration with released RI17/RI18/RI20 candidates.
- [ ] Managed identities, GKE startup, engine/SQL recovery and retained migration (outside scope).

Evidence and final commands are in the lane's board READY report. No live or financial validation
is claimed. Shared indexes require coordinator-owned reconciliation.
