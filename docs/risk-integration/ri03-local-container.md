# RI-03 local container pipeline

Review needed, 2026-09-24 UTC. The local one-portfolio milestone is implemented and exercised. A fresh synthetic TraderX bill portfolio passed through the existing sequenced exporter, bundle producer, coordinator, accepted engine HTTP container, immutable validated intake and existing Risk page. Broader RI-03 distributed/service readiness remains open.

## What ran

The in-process `SharedEodExamplesTest` uses the sequenced TraderX service and production CSV/cut/receipt exporters. The existing `shared_examples.py` validates their receipt, counts, hashes and cut witness and supplies explicitly synthetic instrument terms. `ri03-acceptance.py` rebuilds the bundle from those fresh exporter artifacts with `bundle.build`; it asserts byte equality. No second portfolio is entered into the risk engine.

`ContainerAdapter` stages the coordinator's private snapshot at `/data/inputs/<bundleId>` and calls synchronous `/eod/price`. The unrelated asynchronous `/portfolio/price` simulation API has a different schema and process-local lookup and is not used. No live consensus cluster or real market inputs were required or claimed.

The accepted Linux/amd64 image under ARM/Rosetta is:

`sha256:88ad80a618e51ecafaf0a0e6bd8e00f55bf661f2c3d3941ed39feae64cb93877`

Engine: `992db307f40c1b19de19a0ae43bf653ede3a5bda`. Packaging: `8de78f08e5ed8511e3c9314e9ad70e69b93e47a7`; qualification: `d55a93f8e3bf1dc87f815e7f6b2d1e5462ec36d8`. The proof inspected the actual image, mounts and loopback binding. The schema snapshot `container-result-schema.json` is a byte-identical consumer pin of the accepted container's `/eod/schemas/result`; it is not a competing schema definition.

The closed profile admits only the original dated synthetic bill export (`c3211337…25d`), with two signed USD 100,000 positions and explicitly named assumed `flat-3pct-v1`. Other inputs are rejected, never replaced. Supporting broader exports requires a reviewed input/profile expansion. The old mock, W0 and e7246e1 pricing profiles are unchanged.

## Identity, custody and recovery

The existing coordinator owns SQLite jobs, the exclusive host lock, input snapshots and consumer attempts. Before sending, the adapter publishes and fsyncs a write-once intent containing the profile, workload key and consumer attempt ID used as `submissionId`. It sets `reuseExistingResult=false` and sends no unsupported calculation/currency overrides. It never relies on upstream idempotency to resend.

The raw HTTP response is kept unchanged. A separate GET `/eod/attempts/<id>` must bind the engine attempt to the exact submission and workload and match the result. Intake verifies the published schema, bundle/epoch/date, each item identity/order, full coverage counts, assumed curve and accrual provenance. Accepted custody contains `http-response.json`, `attempt-response.json`, `request.json`, extracted `results.json` and hash-linked `acceptance.json`. Failed validation leaves raw untrusted response journals outside accepted results. Every status read revalidates offline; no UI request calls the engine.

After an abrupt consumer exit, recovery uses GET only. Unknown/missing binding or unavailable submission outcomes enter terminal `UNCERTAIN`; retry is disabled for this profile, including manual coordinator retry. This deliberately sacrifices automatic liveness where execution cannot be excluded. Operator investigation is required; a new state directory is not a safe automatic retry mechanism. Completed custody remains readable with the container stopped. Active engine state is not durable and no exactly-once claim follows from the local lock.

The existing `/eod/jobs` route now exposes a small price summary only for this explicitly synthetic profile with verified integrity. The existing Risk page shows prices, provenance, unsupported measures and uncertainty. `usableForRisk` and `portfolioRiskAvailable` remain false. Production authentication is not established by loopback/image inspection.

## Recorded results

| Evidence level | Result |
|---|---|
| Source EOD suite | 148 tests passed |
| Generated EOD suite | 148 tests passed; source/generated parity verified |
| Existing exporter suites | Shared examples 3, EOD bundle 1, risk extract 23, swap booking 29; all 56 passed, no skips |
| Generated exporter proof | Three fresh cases reproduced frozen bytes; six inherited mock custody checks passed |
| Actual container/intake | One supported bill bundle; one consumer attempt; original HTTP SHA-256 `c85e1adf62b0edd67da5e31f21174c7ed743e08a209ea1537474619c3855ef1c` |
| Independent existing Decimal check | Long NPV `98507.14563826029`, short negative; absolute difference `1.760470379811945178295e-14` USD, within `1e-8` USD |
| Real HTTP refusals | Missing bundle 422; unresolvable assumed profile 400; missing market and unsupported SOFR returned 200 with unpriced outcomes |
| Abrupt consumer exit | Exit 75 immediately after real POST response, before local response publication; replacement consumer recovered using two GETs, one consumer attempt |
| Container replacement | Seven completed/failed attempt documents preserved; original consumer integrity remained VERIFIED |
| Actual UI | Built existing Risk page visibly showed ±98,507.15; corrupt disposable custody removed price rows; real unavailable-worker state showed Submission uncertain / Automatic retry disabled |
| UI checks | Angular build passed; 10 Risk-page headless Chrome tests passed |
| Spec checks | Front matter, root spec gates, readiness, coverage and whitespace checks passed |
| Unchanged upstream red gate | 1 passed / 4 failed: cache binding, overlapping duplicate execution, unsupported currency, unknown calculation |

The restart proof initially compared HTTP response bytes and failed because JSON key order changes after persisted records are loaded. The corrected assertion compares every parsed field and HTTP status, while consumer original-response bytes remain immutable. Both the initial failure log and final evidence are retained. This is not a byte-identical HTTP serialization claim.

The first sandbox suite lacked permission to bind test sockets and Angular workers aborted; reruns with local execution permission passed. Generation's optional npm lockfile refresh stalled; it was stopped and full generation rerun with supported `TRADERX_SKIP_LOCKFILE_REFRESH=1`. No source compatibility shim was used. Allocation tasks ran as exporter prerequisites; no new allocation/performance determinism claim is made.

Evidence root on this machine: `/private/tmp/traderx-ri03-work`; durable handoff copy: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri03-20260924`. `browser-proof.md` distinguishes actual rendered UI from intake-only assertions. The original accepted consumer state and separate corrupt/uncertain probe states are retained outside Git.

## Reproduce

Use an isolated checkout at this delivery, Java 21, Docker, Node and a Python 3.11 virtual environment. Install `generation/runtime-overrides/eod-risk-bundles/requirements-container.txt` from the YU18 pack into that environment (jsonschema 4.26.0). Put its `bin` directory on PATH so inherited test scripts use it. Existing numerical checks need no engine import. Engine packaging stays in its external checkout.

```sh
TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh YU18-risk-integration
bash scripts/test-state-YU18-risk-integration.sh
bash scripts/test-state-YU18-risk-integration.sh generated/code/target-generated/eod-risk-bundles
bash scripts/demo-state-YU18-shared-examples.sh
```

Use the printed fresh exporter directory's `shared` child as `EXPORTS`. Prepare a new private directory outside Git as `RUN`, with `staging` and `engine-store` children. For this synthetic-only fixture, make staging readable by the container's UID 10001. Follow the accepted RI-02 runner's platform-specific store ownership instructions; do not loosen private result permissions.

```sh
python3 /Users/yaakov/dev/jax_risk_engine/JAX_Risk_Engine-containerization/container/run.py \
  --image sha256:88ad80a618e51ecafaf0a0e6bd8e00f55bf661f2c3d3941ed39feae64cb93877 \
  --name traderx-ri03 --inputs "$RUN/staging" --store "$RUN/engine-store" --port 18303
python3 scripts/ri03-acceptance.py --root "$RUN" --exports "$EXPORTS"
python3 scripts/ri03-recovery.py --root "$RUN"
python3 scripts/ri03-pipeline.py --state "$RUN/state" --staging "$RUN/staging" --status-only
```

The acceptance runner checks a real mounted container and reads the published schema; it is not a fake transport harness. `ri03-recovery.py` replaces only a task-named, image/mount-verified container and preserves its store. Run in a fresh directory. Its `--case` supports a distinct, explicitly chosen recovery probe; it never reuses an existing recovery state.

Build `web-front-end-console` normally. Then run from the repository root:

```sh
EOD_COORDINATOR_STATE="$RUN/state" \
EOD_STATUS_SCRIPT="$PWD/generated/code/target-generated/eod-risk-bundles/job_status.py" \
EOD_PYTHON="$(command -v python3)" PORT=18304 node scripts/ri03-console.mjs
```

Open `http://127.0.0.1:18304/risk`. This host serves the existing Angular bundle and imports the existing read-only `readEodJobs` implementation. It has no cluster/cloud proxy or order-entry endpoint. Unrelated Treasury demo and historical reference-comparison artifact remain visibly unconfigured. The pre-existing console shell's “rig connected” badge means any HTTP response (`app.ts`, status > 0), not a running matcher; this lane does not change shared shell/API ownership or claim a live rig.

Run the unchanged upstream gate separately, expect its nonzero result and review its four assertions:

```sh
python3 docs/risk-integration/spec-kit-draft/acceptance/check_current_api.py \
  --engine /Users/yaakov/dev/jax_risk_engine/JAX_Risk_Engine-containerization
```

The upstream gate needs its existing engine test dependencies. The adapter does not fix or skip those defects. On completion stop/remove only your RI-03 container and stop the local UI host. Retain external state/evidence for review.

## Owning layers and next work

All adapter/coordinator/intake changes are in the existing YU18 EOD runtime owner; the current renderer already copies that component. Risk page source remains at repository root. No ancestor, shared renderer, state-level generation file, matcher, gateway, order read-model, ticket/blotter or `api.ts` changes were needed. No peer/engine checkout edits, push, cloud operations or broad propagation occurred.

The app-created worktree initially belonged to the parent `lmax` Git repository. The exact TraderX base was fetched locally into that clean worktree and `codex/risk-pipeline` created there. The coordinator's TraderX repository therefore needs a local fetch of the delivery commit before cherry-picking; its existing uncommitted queue updates must be preserved and combined with only the RI-03 changes.

Remaining external/service work: four upstream contract defects, broader input profiles and observed markets, bilateral contract agreement, active-state durability, authenticated boundaries, native sizing, distributed workers/leases/backpressure/cancellation and production risk qualification. None is marked completed by this local milestone.


## Coordinator corrections, 2026-09-24

P1 and P2 were independently reproduced and corrected after review of the initial delivery.

- P1: the clean baseline's 10 adapter tests failed with 13 missing-jsonschema errors. The workflow now installs the existing pinned requirements before both EOD suites. The supported `package-state-YU18-risk-runtime.sh` now includes the pinned schema and requirements, and `Dockerfile.risk-runtime` installs them in `/opt/traderx/status-venv`. `EOD_PYTHON` selects that interpreter. Adapter construction validates dependency/schema availability before state/staging/submission; a missing dependency names the installation requirement and creates no state or input staging.
- P2: a consistently bound negative long-bill NPV and nonzero structural-zero accrual previously reached VERIFIED price display. Intake now rejects non-finite numerical fields, NPV with the wrong sign or zero for nonzero bill face, nonzero structural-zero accrual, contradictory explicit amount currency/units and unreviewed computation fields. Optional explicit amount annotations must be USD; arbitrary extensions cannot silently redefine units. These are consistency checks, not another discounting implementation. Independent discounting remains acceptance-only.

Correction verification: fresh Python 3.11 and 3.14 environments, installed from the declared requirement, each passed both supported source/generated entrypoints (152 tests each plus CLI smoke). The 14 focused tests include 11 consistently bound outcome mutations, overflow `1e999` with preserved raw bytes, finite-number cases, valid explicit USD units and early missing-dependency diagnosis. Rejected attempts do not create accepted result directories or price summaries.

The actual packaging script built local `traderx/ri03-status:review-corrections`. Installed component tests passed all 152 inside that Linux/amd64 image with no network and read-only source mounts. Its real `/app/eod-jobs.mjs` `readEodJobs()` entrypoint returned HTTP-style status 200, VERIFIED and pricingAvailable=true for the retained good result, using image-configured EOD_PYTHON and EOD_STATUS_SCRIPT. The schema and dependency version were inspected. This used an existing local console base solely to test the status packaging layer, not to claim a rebuilt/deployed Angular UI. No hosted Python-3.12 Actions run or new engine execution is claimed by these correction checks.

Runtime mount qualification: Docker Desktop presented the private state mount as uid 0/mode 0700. A nonroot uid-501 probe correctly refused it; the image's existing root-user contract passed without weakening ownership checks. Deployments must preserve matching ownership. The earlier broad sandbox baseline run also had loopback-permission failures; the isolated focused dependency baseline is the evidence for P1.

Correction evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri03-corrections-20260924/`, including before/after reproductions, rejected original HTTP/binding bytes, fresh-env and image tests, build log and image identity. Original evidence remains unchanged. Four upstream API defects remain external/red; no push, deployment, cloud, engine or Claude-owned edits.
