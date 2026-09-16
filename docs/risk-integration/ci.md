# Risk integration CI

`.github/workflows/engine-tests.yml` runs on pushes to `traderX-risk-integration`, pull requests, and manual dispatch. Events on, targeting, or originating from the integration branch select the event commit: the pushed SHA, or GitHub's proposed merge commit for a pull request. Each job uses that same revision; none of these integration runs checks out a moving YU15 branch head.

Integration runs render `YU18-eod-risk-bundles`. The hosted engine/allocation and composed service suites run against that generated tree. Baseline Java, reference-data and .NET suites use the same event checkout. The existing four Testcontainers jobs remain enabled and render YU18 where they need composed services.

The composed-extras job installs dependencies and tests reference-data, price-publisher and tick-store, and additionally runs:

- The YU18 EOD source and generated suites (133 tests each at setup, including provisional pricing acceptance).
- The real Git checkout/CRLF fixture preservation check.
- Comparison of generated counterparties.csv with its authoritative spec source.

The separate counterparty-reference job checks its failure controls and scans source manifests. These checks do not establish live cluster configuration. Historical YU13–YU17 events retain the existing branch matrix and YU15 baseline selection. The heavier cluster/timing/Epsilon job remains manual-only; CI configuration does not authorize deployment.

Local synthetic pricing fixtures are included. CI does not require Alex's private checkout, execute his engine, or claim financial validation. The real Alex demo remains a separate local compatibility check.

The workflow needs to be pushed before these new checks can run on GitHub. Local validation cannot establish a successful hosted run or required-check branch protection. Branch protection and notification settings are unchanged.
