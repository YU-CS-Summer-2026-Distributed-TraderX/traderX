# FIX fill reports tasks

- [x] Track FIX orders after the direct ack; report cumulative growth as `ExecType=F` (FR-FFR-01..04, `abc57a39`).
- [x] Every gateway reads every update; early-fill ring for a feed that beats the ack (FR-FFR-05, 06).
- [x] JetStream ordered consumers on `TRADERX_FEED`; `jetstream` waits for the stream (FR-FFR-07, `0326f733`).
- [x] Gap ALERT and `traderx_fix_fill_reports_total{sent,gap}` (FR-FFR-08).
- [x] `FIX_FILL_FEED` modes; `prepare.py` requires `jetstream` on CompSci (FR-FFR-09).
- [x] Bounded, expiring tracked set (FR-FFR-10); restart contract with ADR-086 (FR-FFR-11).
- [x] `FixGatewayFillReportTest`, `FixFillFeedIT` and the A/Bs; live on `traderx-fixf` including a mid-cross drop.
- [ ] Ship in the next `traderx` roll and re-run `yu18-fix-session` step 4 there.
- [x] FR-FFR-15/16 (2026-11-10, after the 450K acceptance run's 13.5%): early fills by ref, bounded, counted lost;
      fill reports sent on the session's report thread. Unit tests fail on the pre-fix acceptor; bench in the spec.
- [x] FR-FFR-17 (2026-11-10, after acceptance run 2's 44M unreported STP cancels): venue cancels and rejects are
      reported, early ones after the New; a client cancel once. Unit tests fail on the 316b269e acceptor.
- [x] FR-FFR-18 (2026-11-10, after acceptance run 3's 10.9M lost at ~150K orders/s): orders tracked at their ack,
      updates held on the order until its New. Unit tests fail on the ff684979 acceptor.
- [ ] Re-run the acceptance stage 1 with `traderx_fix_fill_reports_total{lost}` and FR-GFS-20's commit metrics read, and `traderx_fix_end_reports_total`.
