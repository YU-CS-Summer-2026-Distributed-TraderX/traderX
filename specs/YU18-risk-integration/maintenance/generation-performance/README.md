# Generation performance maintenance

Status: integrated locally after direct human approval, 2026-10-09, from `c0140ef9`; bounded local scope done. Owner: Codex RI29-GENERATION-PERFORMANCE.

The runtime-harness installer batches selected script copies, Perl header normalization and executable-mode updates. It still selects and installs scripts separately for every ancestor state. Full generation, patches, pruning, dependency behavior and RI20 independent current-source derivation remain in place. No persistent output cache or receipt fast path is introduced.

Authoritative implementation: `pipeline/install-generated-runtime-harness.sh`. Focused controls: `python3 scripts/tests/generation-performance/test_runtime_harness.py`. Requirements, validation and evidence limits are in [spec.md](spec.md), [plan.md](plan.md) and [tasks.md](tasks.md).

The measurement base is accepted post-RI20 `d24078f0`. Detailed local raw samples, tool versions, source/output inventories, stage profile, comparison protocol and report are under `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri29-generation-performance-20261009/`. Shared RI29 issue/task/index updates are coordinator-owned. Local generation performance does not establish runtime, deployment or financial behavior.

Measured on the accepted post-RI20 source with existing filesystem caches and offline lockfile refresh disabled, three serial pairs gave median full-generation time 90.14 to 64.51 seconds (fresh output), 92.36 to 63.10 seconds (unchanged rerun), and private four-context derivation 90.46 to 65.30 seconds. Raw sample ranges and paired reductions are in the local report; these are local generation/admission timings, not runtime throughput or image-build measurements.

Full-output references match in YU18 and inherited 001/004/YU13/YU15. All 25 RI20 controls are covered (24 passed together, the missing historical-object fixture was fetched locally and the omission control rerun successfully); 23 RI18, 47 admission and six focused controls pass. A guard-disabled mutant fails two new controls. Actual STP preparation/pre-role parity and private cleanup pass. State 009 vendoring and the lockfile-enabled profile both refuse uncached npm metadata in original and candidate; those profiles remain inconclusive.
