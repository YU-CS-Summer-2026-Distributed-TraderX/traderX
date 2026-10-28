# FIX fill reports

Status: integrated in code 2026-10-27/28 (`abc57a39`, `0326f733`), live-verified on the side namespace
`traderx-fixf`; on production `traderx` since roll 4 (2026-10-28, `00b31d33`; rollout record §13). Decision: [ADR-084](../../system/adr-084-fix-fill-reports.md).
Base requirement: YU10 FR-FIX05. Closes proof-audit round 2, suspect 8 (`scripts/proofs/COVERAGE.md`).
Seam: persistent sessions, [ADR-086](../../system/adr-086-persistent-fix-sessions.md) §3.

Sources:

- `generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/FixGatewayAcceptor.java`
  (`track`, `onOrderUpdate`, `applyUpdate`, `fillReport`, `fillReports`, `fillGaps`).
- `.../cluster/ClusterGatewayMain.java` (`startFillFeed`, `connectFillFeed`, `/metrics`).
- `scripts/gcp-compsci/prepare.py` (`FIX_FILL_FEED=jetstream` on the CompSci gateway, and the refusal).
- Tests: `FixGatewayFillReportTest`, `FixFillFeedIT`, `test_prepare.py`
  (`test_gateway_reports_fix_fills_from_the_stream`); live proof `scripts/proofs/yu18-fix-session.sh` step 4.

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
