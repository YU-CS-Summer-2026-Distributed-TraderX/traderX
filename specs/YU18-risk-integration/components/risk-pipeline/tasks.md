# Risk pipeline tasks

Owner: Codex RI-03. Base: d6ca333079ffa3c68f2a5697344fb647e9eaf0de. Status: review needed for local one-portfolio milestone.

- [x] Verify clean isolated checkout/base and image; post scoped CLAIM.
- [x] Audit responsibility boundaries; specify FR-RP/NFR-RP/SC-RP cases before implementation.
- [x] Implement separate service adapter, immutable custody, uncertainty handling and local driver.
- [x] Extend existing status/UI with honest container states and validated price display.
- [x] Prove source/generated cases, fresh exporter, real HTTP/refusal/restarts and browser UI.
- [x] Record unchanged four upstream red tests, numerical checks and limits.
- [x] Maintain backlog and prepare scoped review delivery. Board READY_FOR_REVIEW and coordinator notification are recorded externally after the commit.

Verification: 148 source + 148 generated tests, 56 exporter tests, 10 Risk-page tests, actual container/refusal/replacement and browser probes. [Evidence and limits](../../../../docs/risk-integration/ri03-local-container.md). Four upstream acceptance failures remain red; parallel workers are outside this delivery.

Coordinator corrections (2026-09-24):

- [x] P1: install schema dependency in composed-extras and supported status image; include schema/requirements in package; diagnose missing dependency before submission.
- [x] P2: reject consistently bound finite/sign/structural-zero/unit contradictions without a pricer; keep rejected raw bytes and prevent promotion.
- [x] Fresh 3.11/3.14 source+generated entrypoints each 152 tests/CLI smoke; actual packaged component 152 tests and real offline readEodJobs route pass. Correction delivery is review needed; original live-container/UI evidence remains separately dated.

## Local CPU packaging and export mapping, 2026-10-15

- [x] Coordinate bounded isolated implementation; preserve canonical and Alex checkouts.
- [x] Maintain platform packaging, pinned dependencies, installed verification and empty-EOD-directory repair.
- [x] Map nonempty synthetic USD bill account with explicit terms, units, curve and account/run/cut identity.
- [x] Verify real installed CPU execution and retained connected intake; reject identity/custody mutations.
- [x] Verify source/generated contracts, fresh exporter and frozen golden; record exact bounded evidence.
- [x] Coordinator final review and explicit canonical integration approval; candidate `1a037a47` integrated October 15. See [bounded closeout](../../../../issues/risk-integration/resolved/risk-cpu-local-export-20261015.md).
- [x] Qualify bounded mapped CPU cloud build receipt and actual Batch UID handoff; retained artifacts verified.
- [ ] Integrate connected status/UI and durable authorized run selection/replay policy.

Evidence: [CPU export platform](../../../../docs/risk-integration/cpu-export-platform.md). Historical cloud pilot and EOD/UI evidence remain separate.

## Local Batch result ingestion, October 15

- [x] Implement explicit local Batch import, copied custody, replay/conflict handling and offline status.
- [x] Display verified account/run/cut/NPV in the existing Risk tab, with honest unavailable measures.
- [x] Exercise real saved artifacts and built UI with missing/tampered evidence refusals.
- [x] Coordinator review and human approval complete; `860ad0d0` and four dependencies integrated. See [closeout](../../../../issues/risk-integration/resolved/risk-batch-result-ingestion-20261015.md).
