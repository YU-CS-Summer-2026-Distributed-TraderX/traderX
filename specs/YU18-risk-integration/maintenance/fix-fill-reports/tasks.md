# FIX fill reports tasks

- [x] Track FIX orders after the direct ack; report cumulative growth as `ExecType=F` (FR-FFR-01..04, `abc57a39`).
- [x] Every gateway reads every update; early-fill ring for a feed that beats the ack (FR-FFR-05, 06).
- [x] JetStream ordered consumers on `TRADERX_FEED`; `jetstream` waits for the stream (FR-FFR-07, `0326f733`).
- [x] Gap ALERT and `traderx_fix_fill_reports_total{sent,gap}` (FR-FFR-08).
- [x] `FIX_FILL_FEED` modes; `prepare.py` requires `jetstream` on CompSci (FR-FFR-09).
- [x] Bounded, expiring tracked set (FR-FFR-10); restart contract with ADR-086 (FR-FFR-11).
- [x] `FixGatewayFillReportTest`, `FixFillFeedIT` and the A/Bs; live on `traderx-fixf` including a mid-cross drop.
- [ ] Ship in the next `traderx` roll and re-run `yu18-fix-session` step 4 there.
