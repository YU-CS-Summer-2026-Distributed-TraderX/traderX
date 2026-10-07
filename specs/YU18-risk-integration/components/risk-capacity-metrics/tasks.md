# Risk-capacity metrics tasks

Owner: Codex RI22. Local implementation; review and integration pending.

- [x] Reproduce nonzero accounting with missing gauges on the original actual HTTP handler.
- [x] Identify YU18 last-wins ClusterNodeMain and BlpRiskState, trace units/update sites and FX valuation.
- [x] Implement cold observational formatter using existing accessors only, explicit availability, zero retention and bounded account labels.
- [x] Exercise source-composed production formatter/handler controls.
- [x] Verify final canonical generated parity, accounting regressions and nonzero allocation reports.
- [x] Prepare hashed local evidence and scoped commit for READY_FOR_REVIEW delivery.
- [ ] Coordinator review and controlled integration, including shared index links.

Source-composed tests are local synthetic accounting evidence. Generated and allocation results must be recorded after execution; no cluster or financial validation is inferred.

Verification (2026-10-07): original HTTP baseline 1/1; source-composed RiskCapacityMetricsTest 14/14; canonical generated accounting/snapshot regressions 100/100; five isolated allocation tasks each executed one non-skipped passing case under the configured Java 21 C2/-Xbatch profile. Original handler and missing-as-zero controls each failed one actual HTTP assertion at runtime as expected. Component check, four repository gates, generation and exact source/generated parity passed. Evidence: `coordination/eod-integration/review-evidence/ri22-risk-gauges-20261007/` in the parent workspace. Review/integration is the remaining task.
