# Risk pipeline plan

Owner: Codex RI-03. Status: review needed; local milestone implemented and tested.

1. Audit accepted base, image and producer/API/intake/UI contracts (done).
2. Add separate container adapter/profile in YU18 eod-risk-bundles, preserving ancestor layers and old profiles. Add UNCERTAIN terminal hold and no-retry policy to existing coordinator.
3. Reuse current result schema as a pinned snapshot; validate semantic bindings locally. Keep independent Decimal checks in proof driver only.
4. Add read-only price summary to existing job status/Risk page; isolated server imports existing route implementation, with no cloud calls.
5. Generate YU18 sequentially; run source/generated suites and fresh exporter proof. Exercise real container supported/refused/restart paths plus unchanged external red gate; capture actual UI.
6. Deliver scoped commit(s), exact evidence and board READY_FOR_REVIEW; coordinator incorporates queue changes.

Runtime ownership: YU18 is last-wins owner of eod-risk-bundles; no earlier layer of those Python paths. Console Risk page is root source. Current renderer already copies complete EOD component; no renderer change required. Matcher/gateway/read-model/api.ts remain Claude-owned.

Evidence and reproduction: [RI-03 local container proof](../../../../docs/risk-integration/ri03-local-container.md). Broader distributed readiness remains open.
