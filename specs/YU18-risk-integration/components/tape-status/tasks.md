# Tape status tasks and evidence

Owner: codex-tape-status. Date: 2026-10-07. Status: implemented and locally verified; coordinator review pending.

- [x] Reproduce actual missing/corrupt ambiguity with private synthetic fixtures (SC-TS-01).
- [x] Inventory owner layer and consumer paths; post isolated checkout/path claim.
- [x] Add stable producer state/reason fields and readable refusals (FR-TS-01/02).
- [x] Prefer structured consumer codes, preserve legacy fallback and existing labels (FR-TS-03).
- [x] Add source fixture and producer-to-consumer acceptance; 46 replay tests and 4 consumer tests passed.
- [x] Source focused replay/print suite 72/72; full generated publisher suite 127/127; generated consumer 4/4; exact module/test SHA-256 parity (SC-TS-03).
- [x] Original producer substitution: full composed suite 107 pass/20 fail, all new status cases refuse. Original classifier substitution: 1 pass/3 fail; legacy test remains green. TypeScript noEmit, component layout, frontmatter, root gates, readiness and spec coverage passed.
- [x] Prepare scoped commit and READY_FOR_REVIEW evidence for coordinator; integration pending.

Evidence: `/private/tmp/ri25-evidence/`; synthetic local module acceptance only. Permission EACCES is injected at the filesystem boundary so privileged CI cannot bypass chmod; directory unreadability exercises a real filesystem refusal. Full dependency-free replay tests exercise actual gzip/JSON parsing. No tape fetch, live service, deployed image, cluster, financial or stored-object integrity validation.

Full generated suite used existing read-only dependency installation via NODE_PATH: express 5.2.1, nats 2.29.3, yahoo-finance2 2.13.3. No install or network was required. Bare override `npm test` initially failed because dependencies and inherited option-quotes are absent there; the composed suite is the complete acceptance, not that incomplete directory.

R1 review correction (FR-TS-04), locally verified, review pending:

- [x] Actual-module five-fixture before/after records: unrepresentable day/open/window timestamps and duration overflow formerly crash; compression/day-index overflow formerly reports finished. Corrected statuses refuse with existing schema/clock codes and prices return null.
- [x] Ten producer regression cases added: source replay/print 82/82, full generated publisher 137/137. Five consumer cases pass against source and generated producer, including actual timestamp/overflow outcomes and unchanged legacy compatibility.
- [x] Prior f7678382 module in isolated composed-suite copy: 129 pass/8 fail (the eight new failure-domain cases fail; prior 127 plus two valid boundary controls stay green). Consumer prior-module control: 4 pass/1 fail.
- [x] Private sequential YU18 generation, exact updated module/test parity, component/frontmatter/root/readiness/coverage/diff gates pass.
- [x] No rig, cloud, financial validation or canonical/peer edits. Return extra scoped commit to coordinator; no self-integration.

R1 private evidence: `/private/tmp/ri25-r1-evidence/`; final review delivery copies logs and source hashes to the owned board evidence directory.
