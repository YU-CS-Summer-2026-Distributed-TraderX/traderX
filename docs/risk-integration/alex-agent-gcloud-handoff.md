# TraderX risk integration and Google Cloud

Technical overview · October 14, 2026

TraderX is integrating the [JAX Risk Engine](https://github.com/AlexNeugroschl/JAX_Risk_Engine)
as an external batch workload. TraderX supplies portfolios and market inputs,
Google Cloud provides execution and storage, and the engine produces results for
TraderX to ingest. Pricing runs independently of the TraderX Kubernetes cluster.

Yaakov maintains the TraderX integration pipeline and its cloud configuration.
Alex can also develop independent Google Cloud services and experiments around
the engine. Both can use the same engine images without requiring TraderX to be
running.

## Integration status

| Area | Status |
| --- | --- |
| Local CPU execution | Eight jobs completed through the regular portfolio API and worker, including synthetic pricing and recovery checks. |
| Google Cloud CPU execution | One four-position portfolio completed in Google Batch; 23 referenced artifacts were verified and no VM or disk remained for the Batch job UID. |
| GitHub connection | The Cloud Build app is installed and the engine repository is connected to Cloud Build. |
| Automatic image builds | Trigger configuration is prepared. Activation and a successful triggered build have not yet been confirmed. |
| TraderX portfolio export → engine → result intake/UI | The connected production workflow remains under development. |
| Google Cloud GPU/TPU execution | No engine profile has been qualified on either device in this integration. |

The CPU cloud test uses synthetic positions and an explicit flat USD discount
curve. Per-position dirty NPVs passed an independent cashflow calculation. The
portfolio contains offsetting long/short positions, so its net NPV is zero.
This test establishes the bounded execution and artifact path; it does not
establish general financial validation or scenario-risk accuracy.

## Architecture

```text
TraderX portfolio export + instrument terms + market inputs
                         │
                  Engine request + manifest
                         │
                   Cloud Storage input
                         │
                 External Google Batch job
                         │
             Engine API and CPU pricing worker
                         │
              Result + artifact manifest in GCS
                         │
                 TraderX result intake/UI
```

The engine job is a finite workload. It can also receive a synthetic or independently
prepared request while TraderX is offline. Cloud Build produces images; Batch
executes pricing jobs. Image publication and production image selection are
separate operations.

## API and job identity

The current CPU integration uses the engine's own run API (upstream `f9cc4c1`, unmodified):

| Operation | Interface |
| --- | --- |
| Submit a portfolio | `POST /runs` with `idempotency_key` and `keep: true` → HTTP 202 with `run_id` |
| Poll status and retrieve a result | `GET /runs/{run_id}` |
| Recover a submission by client identity | Resend the same body: the key returns the original `run_id`, or 409 if it holds another body |

The `/eod/price` adapter has a different input/output contract and is not used by
this CPU loop.

TraderX's client records a submission intent before sending the request and never
resends automatically; an explicit recovery resends the same bytes. Results are
associated with the request, its key and the run identity, and a run polled by ID is
bound to the request by the configuration it echoes. The earlier owned
submission-recovery service (`270a907a` on `c3d7c6a`) is retired; see
[portfolio-current-cpu-v2](../../specs/YU18-risk-integration/contracts/portfolio-current-cpu-v2.md).

For the full TraderX connection, the remaining mapping includes account scope,
portfolio/export identity, valuation cut, instrument terms, quantity and sign
conventions, market provenance, supported-product coverage and result lineage.
The cloud fixture bypasses that export mapping by providing a complete engine
request directly.

## Container build interface

The prepared Cloud Build configuration targets the following **proposed** interface:

| Interface | Purpose |
| --- | --- |
| Root `Dockerfile`, target `ci` | Pinned Linux CPU environment containing the test suite and its dependencies. |
| Root `Dockerfile`, target `runtime` | Installed engine and API, packaged precision evidence and a non-root runtime user. |
| `ENGINE_REVISION` build argument | Source commit embedded in image/runtime metadata. |
| `/opt/engine/ci/cpu_smoke.py` in the runtime image | Bounded installed API/worker test using synthetic input, per-trade assertions and CPU/precision checks. |

These targets and the smoke-script path are not present in the inspected
`c3d7c6a` engine revision. The Cloud Build configuration and the eventual engine
packaging need matching names and behavior before activation.

The tested pilot container uses a composite build context containing engine
source, TraderX consumer modules and prototype batch wrappers. It is not currently
reproducible by building the engine repository alone. The desired CI setup builds
directly from the connected engine checkout, without a local source archive or
manual assembly by Yaakov.

A finite file-input interface would let a cloud wrapper stage a request locally,
invoke the engine, and collect results with an exit status. That interface is
still a design item; there is no engine CLI with agreed batch flags in this brief.

## Image build and release flow

The planned branch-push trigger uses a platform-maintained inline configuration:

1. Build the `ci` image and execute the CPU fast test suite.
2. Build the installed `runtime` image.
3. Run the runtime API/worker smoke test without external network access.
4. Publish a candidate image after successful checks.

The branch expression is `.*`. Images use
`sha-$COMMIT_SHA-build-$BUILD_ID` tags, with execution pinned to the resulting
image digest. The candidate builder has registry-write and logging permissions;
job submission and production image selection belong to the TraderX pipeline.

The proposed automatic-build resources are:

| Resource | Name |
| --- | --- |
| Region | `us-east1` |
| GitHub connection | `alex-risk-github` |
| Connected repository | `jax-risk-engine` |
| Branch-push trigger | `alex-risk-cpu-candidate` |
| Candidate registry | `us-east1-docker.pkg.dev/project-compsci-484414/risk-engine-candidates` |
| Builder service account | `risk-candidate-builder@project-compsci-484414.iam.gserviceaccount.com` |

A main-branch candidate becomes a TraderX execution image after its API/profile
and bounded Batch run have been checked. The selected digest and prior qualified
digest provide release and rollback identities. GPU and TPU images have separate
dependency and numerical qualification requirements.

## Storage and access

Project: `project-compsci-484414`.
Alex's Google Cloud identity: `aneugro1@mail.yu.edu`.

| Bucket | Contents and use |
| --- | --- |
| `project-compsci-484414-risk-engine-pilot` | Synthetic request, pilot control receipts and result artifacts. |
| `project-compsci-484414-risk-engine-alex-dev` | Development inputs and experiment results for Alex. The bucket and Alex's user read/create grants were confirmed by the setup checks. |
| `project-compsci-484414-risk-engine-build-source` | Private build-context archives for the CPU pilot. |
| `project-compsci-484414-traderx-risk-extracts` | Retained TraderX export deliverable; conversion to the current engine request is still part of the connected integration. |
| `project-compsci-484414-traderx-taq-raw`, `project-compsci-484414-taq-parquet` | Private market records, rather than ready engine requests or calibrated curves. |

The development runtime identity is
`risk-engine-alex-dev@project-compsci-484414.iam.gserviceaccount.com`. Its bucket
grants are configured separately from Alex's user grants. The shared project's
ML Engineer role does not imply IAM administration or access to a particular VM;
Yaakov handles the shared-project provisioning and access setup.

The available test input is:

```text
URI: gs://project-compsci-484414-risk-engine-pilot/inputs/risk-cpu-20261014-182420-fix1/request.json#1792016080180530
SHA256: cfd7e2318909be546ffaf5296567e795ebf2933121479dd7ca0aefad0c6b4ff8
Valuation date: 2026-01-15
Positions: 4 USD bill/note long/short positions
Market: synthetic flat USD discount curve
Scenario risk: disabled
```

The pilot output manifest is
`gs://project-compsci-484414-risk-engine-pilot/runs/risk-cpu-20261014-182420-fix1/completion.json`.
It identifies the accepted attempt's artifacts by object name, generation and hash.
Experiment outputs use the development bucket, separately from pipeline results.
Private market datasets require both authorized access and permitted data use.

## CPU reference result

| Field | Value |
| --- | --- |
| Engine numerical revision | `c3d7c6ae485213dbb24abd2724f9ae8cb85acbb8` |
| Integration service revision | `270a907a313bc0184ddcc7cda2e50904d103fce3` |
| Pilot packaging revision | `25b9f43c8396bdee0ed0ff9c518b2be92ccdcf68` |
| TraderX source revision | `bb44d2c5eef64a8e8cfdd21f14dbcdfc94c4b32a`, with separate prototype batch wrappers |
| Cloud Build ID | `c171ded4-e60c-4901-9de2-7a1619f5953b` |
| Batch UID | `risk-cpu-20261014-0770f0bd-ded6-421b00` |
| Engine job ID | `5da34d6d-c6b2-479d-a183-75af0724dbe7` |
| Financial validation | `false` |

```text
us-east1-docker.pkg.dev/project-compsci-484414/risk-engine/cpu@sha256:58148c2019f1112f837d979eb7ca3d0749c15f5ef14a08ba3f6de64c1786f21e
```

This image admits the exact four-position fixture digest above. Arbitrary
portfolios are outside its admitted profile. Its private source/build receipts
and prototype wrappers are separate from the files in this TraderX branch.

## GPU and TPU considerations

The engine's inspected GPU extra uses JAX CUDA 13. A corresponding Google Cloud
GPU environment needs compatible CUDA dependencies and an NVIDIA driver of at
least 580. The API keeps its own JAX work on CPU; the pricing worker's completed
result contains the relevant device and precision report.

The documented worker model uses the devices of one host. Multi-host TPU pod
execution is not supported by that model. TPU qualification also depends on the
engine's float64 requirements for calibration, today's values, Greeks and path
reductions. Device discovery alone is not a pricing-accuracy test.

Standalone accelerator examples and service options are in the
[Google Cloud guide](alex-gcloud-cicd-guide.md#standalone-testing).

## TraderX code and contracts

| Reference | Contents |
| --- | --- |
| [CPU request contract](../../specs/YU18-risk-integration/contracts/portfolio-current-cpu-v2.md) | Supported synthetic profile and request/result checks. |
| [Portfolio CLI](../../scripts/risk-portfolio.py) | Portfolio generation, submission, polling, recovery and inspection. |
| [Portfolio tools](../../specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools/) | Client, request identity, intake, profiles, fixtures and reference calculations. |
| [Local CPU loop](local-cpu-loop.md) | Reproduction commands and local acceptance scope. |
| [Connected-pipeline acceptance](../../issues/risk-integration/live-rig/lr-03-risk-container-pipeline.md) | End-to-end TraderX acceptance status. |
