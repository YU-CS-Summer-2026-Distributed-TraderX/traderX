# Risk pipeline specification

Updated: 2026-09-23. Status: review needed; local milestone implemented and tested. Owner: Codex RI-03, isolated codex/risk-pipeline at d6ca3330.

## Responsibilities and boundary

The existing sequenced TraderX service and RiskExtract exporters own positions, contracts and cut receipts. shared_examples/bridge validate the receipt and assemble a hash-addressed bundle with explicit synthetic reference terms. Bundle validation owns input custody. Coordinator owns private snapshots, SQLite jobs, exclusive single-host lock and attempts. The new container adapter stages these exact bytes under a read-only /data/inputs mount and calls the accepted synchronous /eod/price API. Engine 992db30 owns pricing; EOD terminal attempts live in its external store. Consumer intake owns schema, identity, coverage and provenance validation plus immutable original HTTP bytes. Existing readEodJobs and RiskPage own read-only display.

/portfolio/price accepts a separate simulation portfolio and has process-local asynchronous job lookup. It is not an interchangeable bundle endpoint and is not used. No hand-transcription of TraderX economics into that API. One synthetic exported bill portfolio, two signed positions, is the closed supported milestone. The dated original fixture identity is pinned; additional portfolios/observed markets require a separately reviewed profile.

## Requirements

- FR-RP01: Rebuild the supported bundle from original TraderX exporter CSVs and explicit terms. Validate cut/receipt provenance and retain hashes. Reject other economics; never substitute synthetic inputs for a supplied real job.
- FR-RP02: Separate container profile pins engine revision, image identity, result schema, mapping and named assumed flat-3pct-v1 curve. Prior mock/W0/pricing profiles retain behavior. Unsupported currency/calculation options fail locally before any POST.
- FR-RP03: Mount staging read-only and external engine results read-write; endpoints are literal loopback HTTP. Container paths derive from bundle identity, never arbitrary remote upload claims. Staged bytes must match coordinator custody.
- FR-RP04: Persist immutable request intent with consumer attempt/submission ID and workload key before POST. Preserve raw response and attempt lookup bytes before validation. Reconcile only by GET after uncertain submission; never auto-resubmit. Unknown after restart becomes UNCERTAIN, not never-ran. Cached results must prove matching submission binding through the attempt endpoint. No exactly-once guarantee.
- FR-RP05: Validate pinned published schema, result input identities, full item ordering, calculations/coverage counts, assumed provenance and attempt binding before accepting. Require finite numeric fields, nonzero NPV with the signed bill-face direction, zero structural-zero accrual, and consistent explicit USD amount units; reject unreviewed calculation extensions without implementing discounting. Store raw HTTP envelope unchanged and separately extracted result with hash linkage. Revalidate offline at every status read. Reject mismatches/corruption without display promotion.
- FR-RP06: Expose QUEUED/RUNNING/FAILED/UNCERTAIN/CONTAINER_PRICING_VALIDATED honestly. Only verified intake permits price rows. Keep usableForRisk=false and portfolioRiskAvailable=false; display unsupported calculations and synthetic/assumed labels. No worker call from UI reads.
- FR-RP07: Restart consumer without creating a second attempt; reuse completed custody offline. Container terminal attempt recovery is verified with external store retained. Lost active state remains uncertain. Manual retry is disabled for this profile until a reviewed reconciliation contract exists.

## Nonfunctional requirements

- NFR-RP01: Single host, one lock, no parallel workers or distributed durability claims. Private consumer directories outside Git; only synthetic fixture responses may be tracked. No cloud dependency, push, registry or engine source changes.
- NFR-RP02: Preflight schema/dependency availability before state/staging/submission; CI and packaged status runtime install the pinned requirement. Bounded HTTP time/bytes, no redirects/proxies, fsync intent before submission. Container uses accepted nonroot/read-only/resource-bounded launcher settings and task-specific ports/name.
- NFR-RP03: Financial calculations stay in canonical engine; independent Decimal checks run only in acceptance tooling. Engine four failing acceptance cases remain a separate red gate (cache binding, overlap, unsupported currency, unknown calculation).

## Observable acceptance cases

| ID | Input/action | Expected |
|---|---|---|
| SC-RP01 | Fresh in-process TraderX bill export, explicit assumed curve, real image | hash-valid bundle, one request/attempt, priced long/short, exact HTTP custody, verified UI rows |
| SC-RP02 | Missing terms/artifact/market profile; unsupported scope/options | local or engine refusal; no invented economics or accepted result |
| SC-RP03 | Wrong bundle/item/workload/submission identity, schema/provenance/coverage mutation | rejection; original bytes retained; no pricing display |
| SC-RP04 | Consumer dies after persisted intent or response | GET-only recovery; known bound completed result accepted; unknown becomes UNCERTAIN; no second POST |
| SC-RP05 | Container replaced with same external store | completed/failed EOD attempt GET survives; consumer accepted bytes unchanged |
| SC-RP06 | Corrupt accepted result, failed job, uncertain request | read-only API/UI clears prices and shows failure/uncertainty |
| SC-RP07 | Existing independent bill pricing reference | signed NPV within USD 1e-8; no broader numerical risk claim |
| SC-RP08 | Unchanged upstream proposed-contract starter | four known failures recorded, not skipped or adapter-fixed |

Source tests, generated parity, actual container HTTP, exporter execution and browser-rendered existing UI are separate evidence levels. Full live consensus/trading stack and real-market valuation are outside this synthetic in-process export milestone.

Evidence and reproduction: [RI-03 local container proof](../../../../docs/risk-integration/ri03-local-container.md). Broader distributed readiness remains open.
