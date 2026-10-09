# Generation performance specification

Status: integrated locally after direct human approval, 2026-10-09, from `c0140ef9`; local validation complete. Owner: Codex RI29-GENERATION-PERFORMANCE.

- FR-GP01: Preserve each state's selected script set, optional PowerShell companions, missing-optional handling and mandatory libraries. Copy selection may be batched only before the first destination consumer. Retain parent ordering, intermediate normalization, patch/prune behavior and fresh output generation.
- FR-GP02: Preserve shell and PowerShell guard predicates, first-header insertion, every other byte, file membership, executable modes and compatibility symlink targets. Each file must evaluate its own guard. Empty script selections must not pass literal glob names to tools. Required input failures remain nonzero.
- FR-GP03: RI20 must still independently derive current full ancestry for preparation, each role and each build entrypoint. Preserve full current-input/race checks, strict pre transformation and private artifact provenance. A caller receipt, HEAD, timestamp or output directory cannot waive admission.
- NFR-GP01: Reduce measured process/copy overhead without introducing a persistent cache, network install or new executable dependency. Bash arrays, cp, Perl and chmod are existing dependencies.
- NFR-GP02: Report original/candidate results from repeated serial matched fresh-output and unchanged-rerun pairs with tool/cache/load/source identities. Profile overhead is separate from end-to-end timing. Insufficient dependencies, failed commands, contention or unequal output make a profile inconclusive rather than successful.

Validation scenarios: mixed guarded/unguarded shell and PowerShell scripts, first-header-only insertion, spaces in filenames, unchanged reruns, empty selections, missing mandatory library refusal; independent full-output comparison in YU18 and inherited states; retained 25 RI20, 23 RI18 and applicable 47 admission controls.

Full-output comparison retains raw hashes and compares every deterministic byte, membership and mode. Exactly four existing UI/API-explorer JSON paths contain wall-clock `generatedAtUtc`; their single field values are recorded separately, and every other byte in those files still compares exactly. New maintenance documentation is an explicit source/output delta. Component build-input hashes have no such exclusion.
