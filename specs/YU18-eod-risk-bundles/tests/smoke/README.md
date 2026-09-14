# EOD Bundle Smoke Tests

Run `bash scripts/test-state-YU18-eod-risk-bundles.sh` from the repository root. The suite validates synthetic source exports, builds a private temporary bundle, validates it through the CLI, and invokes the mock consumer. A shell trap removes only the test-created temporary directory.

Pass a generated component directory as the script's first argument to run the same tests against generated code. Assertions cover mixed cuts, schema errors, duplicate IDs, non-finite values, corrupt artifacts, manifest path changes, publication conflicts, empty portfolios and all six exported instrument types. Mock success is transport evidence, with zero priced coverage.
