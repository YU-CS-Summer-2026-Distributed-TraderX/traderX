# Control feed republish tasks

- [x] `auditStream()` on first poll and every `SECURITY_OUTBOX_AUDIT_INTERVAL_MS` (FR-CFR-01).
- [x] Missing / fewer messages / last version below watermark → mark all unpublished (FR-CFR-02).
- [x] Stream ahead of the outbox → ALERT only (FR-CFR-03); complete stream untouched (FR-CFR-04).
- [x] Spec cases in `stocks-outbox-publisher.service.spec.ts`; A/B without the fix fails the two republish cases.
- [x] Production repaired by hand 2026-10-27 (rollout record §12): replica 533, Z8812 removed.
- [ ] Ship `3b10e7e2` in the next traderx roll; after it, a NATS store replacement republishes on its own.
- [ ] Verify a running gateway re-subscribes after a NATS store replacement; on 2026-10-27 it did not after a
      stream delete under a live connection (it needed a restart).
