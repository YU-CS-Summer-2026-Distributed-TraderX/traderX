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

Evening offline coverage milestone (FR-TS-05/06), locally verified, coordinator review pending: own standalone diagnostic, pinned actual reader bridge and independent synthetic inventory; source CLI acceptance, omission/miscalculation mutants, dry run, prior status regressions and generated reader parity are required before review delivery. No runtime/console/config/data changes or universe widening.

- [x] 18 actual CLI tests against source and generated publisher reader roots, including explicit/default declaration, nonempty independent identity inventory, source-role/strict-JS clock semantics, missing/invalid/duplicate/empty/bounded inputs, hashes/determinism and private output.
- [x] Actual CLI implementation mutants: union instead of intersection fails one inventory test; omitted exclusion field fails two inventory checks. Both full 18-case runs exit 1; committed implementation remains green.
- [x] Synthetic dry run: four declared keys, six tape keys, three overlap, one declaration without tape and three excluded tape keys; optional supplied directory and print relations match independent fixture inventory. No real tape/engine observation.
- [x] Prior source replay/print 82/82 and consumer 5/5 preserved. Private sequential YU18 generation and exact main/tape/print/reference reader parity, component/frontmatter/root/readiness/coverage/diff gates pass.
- [x] Standalone repository diagnostic only; no runtime/service/config/data/core/clock/financial changes, production sizing or universe widening. Return isolated commit for coordinator review/integration; shared index proposal only.

Private evidence: `/private/tmp/ri25-universe-evidence/`; synthetic inputs only, no retained process/rig. Source and generated test counts overlap and are not unique-case totals. Prior status integration remains distinct from acceptance of the new coverage tool.

Source identity follow-up (FR-TS-07/08): locally verified, coordinator review pending on clean reviewed81eb3184 with that explicit dependency. Own bounded source-identity CLI/SELECT child, two invented CT/CQ fixtures and independent inventory; required current-query/lineage evidence, filter/null/case/repetition controls, omitted-suffix/wrong-projection mutants, prior diagnostics/status preservation and generated ingester parity. Canonical mapping/storage decisions remain open.

- [x] 13 actual CLI/SELECT cases pass with cached DuckDB1.5.4 against source and generated ingester roots, including independently inventoried CT/CQ collisions/repeats/case/null values, current filter/type scope, provenance/determinism, missing/empty/declaration-only/incompatible source/data and budget refusals.
- [x] 20-second stalled child control refuses incomplete proof and is reaped; no import/install/ingest/COPY/Parquet/GCS action. Full source/projection schemas are recorded, while only identity-bearing columns are materialized.
- [x] Full13-case diagnostic mutants: omitted suffix causes two tuple-count failures plus one dependent inventory error; wrong uppercase-root projection causes three independent/current-query failures. These are private fault-injection variants, never production projections.
- [x] Current fixture proof: raw16 observations/13 tuples; eligible CT12/9 and CQ13/10; three lost tuple distinctions in each, three repeat-extra observations separately. Actual current SELECT and annotated lineage agree; exact generated ingester byte/hash parity passes.
- [x] Previous coverage18/18, status/print82/82 and consumer5/5 preserved; component/frontmatter/root/readiness/coverage/diff gates pass. No broad unchanged-code/runtime/financial suite claim.
- [x] Return extra isolated commit dependent on reviewed81eb3184, no canonical/peer/ingester/schema/normalized key/storage/universe/financial/core/UI/cloud/rig changes or self-integration. Mapping questions remain unresolved.

Private source-identity evidence: `/private/tmp/ri25-identity-evidence/`; only synthetic CSV is read, no actual TAQ conversion or stored artifact observation.
