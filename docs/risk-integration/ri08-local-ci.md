# RI-08 local integration and CI gates

2026-09-24. Owner: Codex integration/CI lane (`codex/integration-ci`). Review needed.
Combined implementation: integration `3244f7eb` plus order-types `53bc3bec`, merge `5705d3f6`.
This delivery is local CI/container qualification. Hosted execution and deployment/rollback remain unverified.

## Audit and changes

The existing `engine-tests.yml` already renders the event SHA for integration pushes/PRs,
runs matcher correctness and allocation gates, six service modules, both source/generated EOD
suites with pinned jsonschema, fixture-byte checks, configuration checks and container-based
persistence tests. The typed allocation gate is already a dependency of YU18's `test` task;
adding another invocation would duplicate it. Existing workflow coverage is retained.

The missing checks added here are:

- `check-yu18-composition.py`: exact bytes for every YU18 runtime override, then nonempty,
  unskipped typed engine/service/boundary/baseline/allocation/read-model JUnit results. The hosted
  YU18 leg calls it after engine/service suites. Five negative-control tests exercise missing
  sources, changed sources, missing reports, and empty/skipped evidence, alongside a positive case.
- `yu18-console.yml`: event-commit Node reader tests, Angular tests, production build, actual
  console Docker build and status-runtime smoke. The console had no tracked lockfile, so clean
  `npm ci` failed; its generated lockfile is now tracked through a narrow `.gitignore` exception.
- `status-runtime-smoke.py`: uses the existing packaging script/Dockerfile, a uniquely tagged
  inspected local console image, and no replacement server. All EOD tests run against image-installed
  code with only tests mounted. A populated synthetic mock coordinator state traverses the actual
  Node/Python reader. Runtime is read-only and network-disabled; one mock job must be VERIFIED,
  with `usableForRisk=false`. BuildKit needs a tag rather than a bare local image ID; that tag is
  checked against the inspected ID after build. Initial failed build evidence is retained.
- `risk-container-smoke.py`: reusable explicit-input external packaging gate. It requires a full
  expected commit, a clean matching checkout and a new external evidence directory. It calls the
  owner's allowlisted builder and all-layer inspector, verifies Linux/amd64 and revision metadata,
  runs a bounded read-only CPU container without networking or published ports, requires readiness,
  both registered API paths, exact consumer schema bytes and HTTP 422 for a missing EOD bundle.
  Cleanup targets only its randomly named container. It performs no financial calculation.

No external engine source is vendored. The external packaging worktree has no tracked Actions
workflow at the verified revision. A hosted engine-build job needs the owner to supply an authorized
checkout at an agreed revision (and checkout credentials if private). No TraderX workflow assumes
an absolute workstation path or invents that access. The explicit-input gate is locally exercised;
upstream wiring remains a dependency.

## Reproduce

Use Java 21, Node 22, Python 3.11+ with the existing EOD requirements installed, Chrome and Docker.
These commands build/run only local images; no registry publication or cloud resource is involved.

```sh
TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh YU18-risk-integration
bash scripts/ci/engine-tests.sh hosted
bash scripts/ci/service-tests.sh
python3 -m unittest discover -s scripts/ci/tests -v
python3 scripts/ci/check-yu18-composition.py --junit
bash scripts/ci/assert-suites-executed.sh
bash scripts/test-state-YU18-risk-integration.sh
bash scripts/test-state-YU18-risk-integration.sh generated/code/target-generated/eod-risk-bundles
(cd generated/code/target-generated/trade-processor && ./gradlew --no-daemon integrationTest)
(cd web-front-end-console && npm ci && npm test -- --watch=false --browsers=ChromeHeadless && npm run build)
node --test web-front-end-console/{eod-jobs,risk-demo,treasury-demo}.test.mjs
docker build --platform linux/amd64 -t traderx-console:ci web-front-end-console
python3 scripts/ci/status-runtime-smoke.py --console-image traderx-console:ci --evidence "$NEW_STATUS_EVIDENCE"
python3 scripts/ci/risk-container-smoke.py --engine-source "$ENGINE_CHECKOUT" --revision "$REVIEWED_FULL_SHA" --evidence "$NEW_ENGINE_EVIDENCE"
```

Run full-suite assertions before any filtered exporter proof rewrites Gradle's test reports.
The delivery preserves full-suite XML separately. Container build steps may fetch dependencies;
network-disabled refers to the smoke containers, not the builds. The inherited console Dockerfile
uses mutable base tags and apt packages, so this is a reproducible procedure with recorded resolved
images, not a claim of bit-for-bit deterministic rebuilds or a fully pinned console supply chain.

## Measured local results

- Fresh full generation; 133/133 runtime files exact. Matcher 563 cases: 557 passed, six existing
  skips; all five allocation gates and two separate Epsilon gates passed under their declared flags.
- Six service modules: 221 passed. Real trade-processor MariaDB/NATS integration: seven passed.
- Source EOD 152 and generated EOD 152 passed plus CLI smoke. Fresh exporter reproduced three
  frozen cases and six mock runs. Real RI-03 HTTP pipeline passed with its unchanged accepted
  image `sha256:88ad80a618e51ecafaf0a0e6bd8e00f55bf661f2c3d3941ed39feae64cb93877`.
- Angular: 97 passed; Node readers: 13 passed; production build passed (638.77 kB initial bundle,
  500 kB warning). Actual current console image built for Linux/amd64 under ARM emulation.
- Status image: all 152 installed-code tests and the populated Node/Python bridge passed offline.
- External engine checkout `d55a93f8e3bf1dc87f815e7f6b2d1e5462ec36d8` built image
  `sha256:86ef76ff0707d2824d8069668f0d81abda35ecd884eed9ec007c3f75f2e5f2ca`.
  All 13 layers inspected; readiness, schema hash and negative HTTP smoke passed. This rebuild
  includes the qualification revision in provenance and has a different identity from RI-03's
  accepted image. The closed RI-03 profile is unchanged; this smoke does not approve a repin.
- Required frontmatter/root/readiness/coverage/component gates passed. New gate controls passed;
  a wrong external revision was rejected before build. No remote Actions run is claimed.

Durable logs, commands, XML, image inspection, source identities, owner-shadowing map, failed
attempts and hashes: `coordination/eod-integration/review-evidence/integration-ci-20260924/`
under the local parent lmax directory. No real portfolio or market data is used.

## Open boundaries

SC-OT35/NFR-OT06 is explicitly user-deferred and unverified until GKE credits. No more latency
campaigns ran. Four unchanged upstream contract blockers remain: cache submission binding,
overlapping duplicate execution, unsupported currency and unknown calculations. Their prior
failure evidence applies to the unchanged engine behavior at the verified qualification revision;
this lane did not rerun the proposed-contract suite or call it green.

Neither `/portfolio/price` simulation behavior nor full risk readiness is qualified by the EOD
pipeline or packaging smoke. Broader profiles, distributed recovery, authentication and deployment/
rollback remain outside this local milestone. Retained kind containers, dirty integration checkout
and external engine files are preserved. Coordinator review/incorporation is next; no automatic
push, publish, deployment or follow-on lane.
