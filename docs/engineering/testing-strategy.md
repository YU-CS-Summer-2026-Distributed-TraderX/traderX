---
title: Testing strategy and commands
---

# Testing strategy and commands

Commands below were checked against integration source `4c2c4eb2` on 2026-09-30. They are reproduction entrypoints, not a claim that the runtime suites were rerun during the docs refresh. Run from the repository root unless a command changes directory. Keep failed logs and identify the source and generated tree for every result.

## Prerequisites and generation

Use Java 21, Node 22/npm, Python 3.11+, Bash, `jq` and `rg`. Browser suites need Chrome; Testcontainers and packaging smoke need Docker. The EOD container requirements pin `jsonschema`. Historical q checks also require the appropriate kdb runtime and authorized datasets.

```sh
TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh YU18-risk-integration
python3 -m venv .venv-docs-check
.venv-docs-check/bin/pip install -r specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles/requirements-container.txt
```

Generation composes full-file overrides in lineage order. Do not test an old generated directory and label it current. The CI scripts below use the repository's default generated root. Run generation sequentially within a checkout. Generation and builds may download dependencies; they do not start a trading rig.

## In-process and generated checks

```sh
bash scripts/ci/engine-tests.sh hosted
bash scripts/ci/service-tests.sh
bash scripts/ci/assert-suites-executed.sh
python3 -m unittest discover -s scripts/ci/tests -v
python3 scripts/ci/check-yu18-composition.py --junit
PATH="$PWD/.venv-docs-check/bin:$PATH" bash scripts/test-state-YU18-risk-integration.sh
PATH="$PWD/.venv-docs-check/bin:$PATH" bash scripts/test-state-YU18-risk-integration.sh generated/code/target-generated/eod-risk-bundles
python3 scripts/test-state-YU18-checkout.py
python3 -m unittest discover -s scripts/ri07 -p 'test_*.py'
```

The engine runner's hosted mode excludes the three-member/timing classes and runs isolated allocation tasks. YU18's typed allocation task is already a dependency of `test`. Gates use specific warmup and JVM flags; they are not universal no-allocation claims. Service tests cover the generated modules named by the runner. The composition check requires exact override bytes and nonempty, unskipped typed-order JUnit reports.

`assert-suites-executed.sh` checks that expected compiled suites produced results. A green Gradle task reporting `NO-SOURCE` proves no behavior. Save full-suite XML before filtered tests overwrite it.

EOD checks exercise immutable bundle validation, result identity/coverage, bad input refusals, custody and restart behavior. Checkout checks protect byte-sensitive fixtures from line-ending conversion.

## Browser and server checks

```sh
npm ci --prefix web-front-end-console
npm test --prefix web-front-end-console -- --watch=false --browsers=ChromeHeadless
npm run build --prefix web-front-end-console
node --test web-front-end-console/eod-jobs.test.mjs web-front-end-console/risk-demo.test.mjs web-front-end-console/treasury-demo.test.mjs
npm ci --prefix web-front-end-console-combined-prototype
npm test --prefix web-front-end-console-combined-prototype -- --watch=false --browsers=ChromeHeadless
npm run build --prefix web-front-end-console-combined-prototype
(cd web-front-end-console-combined-prototype && node --test test-desk-session.mjs test-server.mjs)
```

These target the original console and the selected Desk, not the older `web-front-end` or independent design prototype. Unit/browser fixtures check presentation and refusal behavior. They do not prove engine admission, fills or production authorization. Inspect rendered pages for account switching, price age, accepted/refused/unknown outcomes, historical read-only state and unavailable risk.

## Disposable containers

```sh
(cd generated/code/target-generated/trade-processor && ./gradlew --no-daemon integrationTest)
bash scripts/ri07/test-startup-probe-sql.sh
```

The first uses Testcontainers MariaDB/NATS and shipped schema; the second owns a named disposable MariaDB control container. Check for collisions first. These verify real persistence/transaction behavior and refusal paths, not cluster HA.

The explicit-input packaging scripts require a new evidence directory and a reviewed image or external checkout:

```sh
python3 scripts/ci/status-runtime-smoke.py --console-image "$LOCAL_CONSOLE_IMAGE" --evidence "$NEW_STATUS_EVIDENCE"
python3 scripts/ci/risk-container-smoke.py --engine-source "$ENGINE_CHECKOUT" --revision "$REVIEWED_FULL_SHA" --evidence "$NEW_ENGINE_EVIDENCE"
```

Set every variable to the intended local artifact. The external engine remains separately owned. Packaging smoke checks image contents, schema/readiness and refusal behavior; it does not validate financial models. The closed synthetic risk flow is `scripts/ri07/risk-flow-local.sh NEW_DIRECTORY`; inspect its image, external-launcher and exporter prerequisites before execution. `scripts/ri03-acceptance.py` checks actual HTTP intake and immutable custody; `scripts/ri03-recovery.py` checks uncertain/recovered attempts.

## Live and recovery proofs

These commands mutate a rig or create disposable infrastructure. Reserve an isolated environment and preserve retained data before running them. They were not run for the documentation refresh.

| Entry point | Preconditions | Evidence and boundary |
|---|---|---|
| `scripts/ri07/rig-ready.sh` | Explicit matcher/console URLs and scoped kubeconfig | Full-rig readiness includes persisted probe trades, algo and tracing; Pods Running is insufficient |
| `scripts/ri07/startup-probe.sh` | Reserved probe accounts and empty generated probe book | Refuses existing unrelated accounts; checks non-probe rows are unchanged |
| `scripts/ri07/order-types-live.py OUTPUT.json` | Explicit console URL, later business date, disposable rig | Seven order types, TIF, trigger/ratchet/peg/iceberg, cancel/replace and DAY expiry at book/SQL effect end; exit 0 pass, 1 mismatch, 2 precondition refusal, 3 incomplete |
| generated `recovery-identity/test-live-automatic-recovery.sh` | Java 21, Docker, reserved named containers/ports, `RI06_AUTO_RECOVERY_PROOF=1` | Lease/fencing, outage catch-up and SQL controls; single-member local proof, not multi-member HA |
| `scripts/ci/engine-tests.sh dedicated` | Quiet reserved hardware, generated tree | Three-member cluster, snapshot timing and Epsilon gates; timing failure requires diagnosis, not removal of assertions |
| `scripts/proofs/` and `scripts/yu11/` | Read each script's context, images, reset and retention requirements | Historical FIX, control-feed, EOD, replication and recovery proofs; some target retired/cloud tiers and need explicit adaptation |

Automatic projection recovery remains default off. Managed identity, complete archive and additive migrations are prerequisites, not steps to apply silently to a retained installation.

## CI and documentation checks

`engine-tests.yml` tests the event SHA as YU18 for integration events. Historical state pushes use their branch matrix. `yu18-console.yml` runs console and packaging checks. Hosted workflow configuration is not an independently verified hosted run; this review does not attach a run URL/SHA.

```sh
bash tools/validate-frontmatter.sh
bash pipeline/speckit/validate-root-spec-kit-gates.sh
bash pipeline/speckit/validate-speckit-readiness.sh
bash pipeline/verify-spec-coverage.sh
python3 scripts/check-state-components.py specs/YU18-risk-integration
TRADERX_SITE_ROOT=/traderX bash pipeline/refresh-state-docs.sh
npm ci --prefix website
npm run build --prefix website
python3 tools/check-public-docs.py
```

See [test inventory and counting](test-coverage.md) and [site source/route map](site-source-map.md). This website build does not start the trading services or qualify a deployment.

## Attribution while the venue is active

Historical-tape order flow is another writer. A venue-wide trade or order-reference delta cannot be attributed to one proof while that flow is running. Use the probe's own order/trade identities or the operator-scoped counters implemented in YU17. Those counters exclude external replay flow; they do not isolate one operator from another simultaneous human or test writer.

Five counters have operator-scoped forms: order references, trades, self-trade cancellations, band reanchors and stranded-order cancellations. The order/trade external portions survive snapshots; the other operational counters are process-local. Check reset semantics before comparing across a rebuild. For order-loss assertions, compare acknowledged references with the owned book/rows rather than widening a global-count tolerance.

A cross-member reading must also sample a coherent boundary. Equal numbers sampled at unrelated times do not establish identical state. Scope the comparison to owned state, or use a labelled lower bound when only a floor is justified.

Destructive recovery proofs inspected in `scripts/proofs/` refuse without `DESTRUCTIVE=1`; irreversible paths additionally require an explicit target context. This is a precondition, not permission to run them. A refusal/partial run cannot count as a successful recovery drill. No destructive proof ran during this documentation work.
