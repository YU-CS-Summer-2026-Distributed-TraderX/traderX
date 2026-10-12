# RI31 test-suite profiles and executable guards

Status: local validation complete; canonical integration pending, October 11, 2026. Owner: RI31-SUITE-GUARDS, coordinator Codex chat `01a12b5c-4c93-7e62-b90a-3e56223dee78`. This maintenance pack owns test harnesses, build/test fixture setup and executable guard correctness.

Source owners: `scripts/ci/local-suite.py`, `scripts/lib/check-post-trade-auth.py`, the YU05/YU09/YU10 guard scripts, the local Kubernetes build launcher, its generated-runtime installer, the inherited YU10 FIX test fixture and the YU18 matcher/trade-processor test configuration, baseline UI spec fixtures and current-layer UI test overlays wired by the YU18 renderer. The new YU18 trade-processor build file preserves the operative YU02 dependencies/source sets and adds module access only to Test workers.

Dependencies: JDK 21, Node, .NET 9, a private Python environment with pinned EOD/tick dependencies, ChromeHeadless and Docker for ephemeral integration tiers. Production financial/engine/authentication contracts are unchanged. The launcher prepares fresh JVM artifacts before local Docker builds; no cloud deployment is part of this task.

Acceptance uses real guard entry points with adverse controls, full generated service reports and explicit inventories for skips and unexecuted live tiers. See [spec](spec.md), [plan](plan.md), [tasks](tasks.md), [execution guide](../../../../docs/testing/local-suite.md) and [bounded task](../../../../issues/risk-integration/tasks/ri31-suite-guards.md). Source integration requires separate user approval after candidate delivery.

The composed UI fixtures are explicitly owned at YU18 because state004 restores historical test snapshots. They preserve runtime implementation and the existing 10-percent coverage gates. See the RI31 record for the measured local profile, remaining disabled/live cases, the unproven historical HA cause and the generated People coverage gap.
