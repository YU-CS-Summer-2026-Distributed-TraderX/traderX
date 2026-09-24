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
