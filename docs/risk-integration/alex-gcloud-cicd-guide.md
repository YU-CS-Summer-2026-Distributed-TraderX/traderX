# Running the risk engine on Google Cloud

This guide covers automatic container builds and standalone engine testing on
Google Cloud. The engine can run while TraderX is offline. Yaakov maintains the
TraderX pipeline and its shared-project cloud setup; independent engine services
and experiments can use their own resources.

Project: **`project-compsci-484414`**  
Google account: **`aneugro1@mail.yu.edu`**  
CPU build and Batch region: **`us-east1`**

The [technical overview](alex-agent-gcloud-handoff.md) describes the TraderX API
integration, container interface and tested CPU profile.

## How the services fit together

| Service | Purpose |
| --- | --- |
| Cloud Build | Checks out a pushed Git commit, runs tests and builds a container image. |
| Artifact Registry | Stores images. An image digest identifies the exact version used by a job. |
| Cloud Storage | Holds request files, results and manifests in buckets addressed as `gs://...`. |
| Batch | Runs finite CPU or GPU jobs on temporary compute. |
| Compute Engine | Provides VMs for interactive CPU, GPU or TPU experiments. |

```text
GitHub push → Cloud Build → candidate image in Artifact Registry
                                      │
                           selected image + GCS input
                                      │
                               pricing job
                                      │
                              results in GCS
```

Cloud Build's GitHub app is installed and the engine repository is connected.
Automatic image builds depend on the container interface below and trigger
activation. The completed cloud proof so far is one synthetic CPU Batch job;
a successful automatic build is a separate milestone.

## Container build interface

The prepared Cloud Build configuration uses the following interface. These are
proposed packaging names rather than files present in the inspected engine
revision, `c3d7c6ae485213dbb24abd2724f9ae8cb85acbb8`.

| Interface | What the build expects |
| --- | --- |
| Root `Dockerfile`, target `ci` | Installed test dependencies and a working directory from which pytest runs. |
| Same Dockerfile, target `runtime` | Installed engine/API, runtime dependencies, packaged precision evidence and a non-root user. |
| Build argument `ENGINE_REVISION` | The Git commit included in image/runtime metadata. |
| `/opt/engine/ci/cpu_smoke.py` in the runtime image | A finite synthetic API/worker pricing check that exits unsuccessfully on failure. |

The runtime smoke covers a nonempty request, completed pricing, per-trade checks,
device/precision reporting and a refused invalid request. It runs inside the
installed image without downloading data or dependencies. The engine's numerical
tests provide the broader calculation checks.

Yaakov's trigger configuration and this container interface need to match before
the trigger is enabled. His [platform setup document](yaakov-risk-engine-gcloud-admin-setup.md)
contains the cloud-side configuration. IAM administration, registry/bucket creation
and service-account setup are handled there, rather than through your ML Engineer
account.

## Automatic builds and releases

Once enabled, the branch-push trigger checks the pushed commit, runs the CPU tests,
builds the runtime image, executes the installed smoke and publishes a candidate
only when those steps succeed. Routine engine updates then need only your normal
GitHub push; Yaakov does not assemble a local build context.

The configured branch expression is `.*`, covering branch pushes by trusted
repository writers. A push containing several commits builds its tip. Tag-push
builds require a separate trigger.

Candidate images use this location and tag format:

```text
us-east1-docker.pkg.dev/project-compsci-484414/risk-engine-candidates/cpu:sha-COMMIT_SHA-build-BUILD_ID
```

The commit and build ID distinguish separate builds. Pricing jobs use the
resulting `sha256:...` digest. Yaakov selects a qualified digest for the TraderX
pipeline after checking integration compatibility; image publication alone does
not deploy TraderX or launch pricing jobs.

### Finding a build and its image

In the Google Cloud console, choose the project and open **Cloud Build → History**.
Select the build for your commit and inspect its steps and logs. Published images
appear under **Artifact Registry → risk-engine-candidates → cpu**.

The same information is available from the terminal. Replace `BUILD_ID` with the
actual ID shown in Cloud Build:

```bash
gcloud builds describe BUILD_ID --account=aneugro1@mail.yu.edu \
  --project=project-compsci-484414 --region=us-east1 \
  --format='yaml(status,substitutions,results.images)'
```

```bash
gcloud builds log BUILD_ID --account=aneugro1@mail.yu.edu \
  --project=project-compsci-484414 --region=us-east1
```

```bash
gcloud artifacts docker images list \
  us-east1-docker.pkg.dev/project-compsci-484414/risk-engine-candidates/cpu \
  --account=aneugro1@mail.yu.edu --project=project-compsci-484414 --include-tags
```

Build/log/image visibility needs the viewer grants from the platform setup.
A failed build's first failing step is the starting point for diagnosis. A passing
container build or an HTTP 202 response alone does not establish completed pricing.

## Account and data access

**Cloud Shell** is the terminal opened from the Google Cloud console. It includes
`gcloud` and is useful for inspecting builds and copying files. It does not provide
a GPU or TPU. On a laptop, the [Google Cloud CLI](https://docs.cloud.google.com/sdk/docs/install)
provides the same commands after signing in:

```bash
gcloud auth login aneugro1@mail.yu.edu
```

Cloud Shell uses the account signed into the console. The commands in this guide
explicitly select your account where user credentials are needed. A VM's attached
service account is a different identity, configured separately for runtime access.

| Location | Available data |
| --- | --- |
| `gs://project-compsci-484414-risk-engine-pilot` | The agreed synthetic input and CPU pilot evidence. Your input access is to the exact request object. |
| `gs://project-compsci-484414-risk-engine-alex-dev` | Development inputs and experiment outputs. User read/create grants were confirmed during setup. |
| `gs://project-compsci-484414-traderx-risk-extracts` | Retained TraderX exports. Their conversion into the regular portfolio API is part of the integration work. |
| `gs://project-compsci-484414-traderx-taq-raw`, `gs://project-compsci-484414-taq-parquet` | Private market records, not ready portfolio requests or calibrated curves. |

The build-source bucket contains build archives, not market inputs. Access to
private market datasets and their permitted use are separate from the synthetic
example here. Raw market records alone do not provide positions, instrument terms
or the calibrated market required for a pricing request.

### Downloading the synthetic request

This fixture contains four USD bill/note positions, explicit coupon schedules and
a flat synthetic USD discount curve. Its valuation date is **2026-01-15** and
scenario risk is disabled. It needs no running TraderX cluster or market feed.

```bash
mkdir -p "$HOME/risk-engine-experiments"
gcloud storage cp \
  'gs://project-compsci-484414-risk-engine-pilot/inputs/risk-cpu-20261014-182420-fix1/request.json#1792016080180530' \
  "$HOME/risk-engine-experiments/request.json" \
  --account=aneugro1@mail.yu.edu --project=project-compsci-484414
```

The `#...` suffix selects the object's exact generation. Its contents can be
checked with the standard Python library:

```bash
python3 - <<'PY'
import hashlib, json, pathlib
path = pathlib.Path.home() / 'risk-engine-experiments/request.json'
body = path.read_bytes()
if hashlib.sha256(body).hexdigest() != 'cfd7e2318909be546ffaf5296567e795ebf2933121479dd7ca0aefad0c6b4ff8':
    raise SystemExit('Request hash mismatch')
request = json.loads(body)
if len(request['trades']) != 4:
    raise SystemExit('Expected the four-position fixture')
print('Verified synthetic input:', path)
PY
```

Exact-object access does not necessarily permit listing the entire pilot bucket.
The same synthetic request is also available in the
[TraderX fixture directory](../../specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools/fixtures/usd-treasury-current.json).

## Standalone testing

A provisioned experiment VM can run the engine without TraderX. In the shared
project, Yaakov supplies the VM name, zone, SSH access and any attached runtime
identity. Independently administered projects can use their own equivalent setup.

### CPU

For a VM named `VM_NAME` in `VM_ZONE`, open an SSH session with:

```bash
gcloud compute ssh VM_NAME --zone=VM_ZONE --project=project-compsci-484414 \
  --account=aneugro1@mail.yu.edu
```

The following example expects Git and Python 3.11 with venv support on the VM.
From your VM home directory, a fresh checkout and environment are created with:

```bash
git clone https://github.com/AlexNeugroschl/JAX_Risk_Engine.git
cd JAX_Risk_Engine
python3.11 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -c 'import sys, jax; print(sys.version); print(jax.__version__, jax.devices())'
git rev-parse HEAD
```

The printed commit identifies the source used for the experiment. A private clone
uses your GitHub authentication, separately from Google Cloud login.

From Cloud Shell or your laptop, copy the downloaded request to the VM:

```bash
gcloud compute scp "$HOME/risk-engine-experiments/request.json" VM_NAME:~/request.json \
  --zone=VM_ZONE --project=project-compsci-484414 --account=aneugro1@mail.yu.edu
```

In the VM's engine checkout, start the API in the foreground:

```bash
RUN_DIR="$(mktemp -d)"
JAX_PLATFORMS=cpu JAX_RISK_JOB_QUEUE="$RUN_DIR/jobs.sqlite3" \
  .venv/bin/python -m uvicorn engine.api.app:app --host 127.0.0.1 --port 8000
```

A second SSH session on the same VM can submit the request and poll its result:

```bash
cd ~/JAX_Risk_Engine
.venv/bin/python - <<'PY'
import json, pathlib, time, urllib.request
home = pathlib.Path.home()
output = home / 'risk-engine-experiments'
output.mkdir(exist_ok=True)
base = 'http://127.0.0.1:8000'
request = urllib.request.Request(base + '/portfolio/price',
    data=(home / 'request.json').read_bytes(),
    headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(request, timeout=30) as response:
    if response.status != 202:
        raise SystemExit(f'Expected HTTP 202, received {response.status}')
    job_id = json.load(response)['job_id']
(output / 'job-id.txt').write_text(job_id + '\n')
print('Job:', job_id, flush=True)
deadline = time.monotonic() + 600
while time.monotonic() < deadline:
    with urllib.request.urlopen(base + '/jobs/' + job_id, timeout=30) as response:
        job = json.load(response)
    if job['status'] in ('done', 'failed', 'interrupted'):
        path = output / 'result.json'
        path.write_text(json.dumps(job, indent=2))
        if job['status'] != 'done' or not isinstance(job.get('result'), dict):
            raise SystemExit(f'Job ended with status {job["status"]}; details: {path}')
        print('Completed:', path)
        break
    time.sleep(1)
else:
    raise SystemExit(f'Timed out waiting for {job_id}; the existing job can still be inspected')
PY
```

This submits once and saves the job identity. If a submission response is lost,
the queue and logs help establish whether it was accepted before another submission.
A completed job demonstrates execution; numerical validation also considers trade
coverage, prices, precision and warnings. The fixture's matching long/short
positions net to zero, so per-trade values matter.

Ctrl-C stops the foreground API. Results remain under
`~/risk-engine-experiments/` on the VM. Compute and retained disks have separate
costs; shared-project VM cleanup is part of Yaakov's provisioning workflow.

### Saving results to the development bucket

From Cloud Shell or your laptop, retrieve the VM's result:

```bash
gcloud compute scp VM_NAME:~/risk-engine-experiments/result.json \
  "$HOME/risk-engine-experiments/result.json" \
  --zone=VM_ZONE --project=project-compsci-484414 --account=aneugro1@mail.yu.edu
```

A unique experiment path permits a create-only upload:

```bash
EXPERIMENT_ID="alex-$(date -u +%Y%m%dT%H%M%SZ)-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:8])')"
gcloud storage cp "$HOME/risk-engine-experiments/result.json" \
  "gs://project-compsci-484414-risk-engine-alex-dev/experiments/$EXPERIMENT_ID/result.json" \
  --account=aneugro1@mail.yu.edu --project=project-compsci-484414 --if-generation-match=0
```

The development grants add read/create access rather than overwrite/delete rights.
Runtime service-account permissions are separate from these user grants.

### Unattended CPU jobs

Batch provides finite execution for an installed container with staged input and
output files. The completed CPU pilot verified 23 artifacts from one four-position
request, with no remaining VM or disk for its Batch UID. Its container admits only
that exact fixture; it is not a general-purpose portfolio runner.

A reusable engine container and agreed input/output interface support additional
portfolios. TraderX's pipeline supplies its own job identity and result-intake
contract; independent experiments can use their own Batch definitions and output
locations with the appropriate platform access.

### GPU

An interactive GPU environment consists of a supported VM, GPU driver and matching
JAX dependencies. An L4 VM such as `g2-standard-4` provides one 24-GB GPU as a
possible development starting point; engine performance on it has not been measured
in this integration. [G2 specifications](https://docs.cloud.google.com/compute/docs/gpus/).

The inspected engine GPU extra uses CUDA 13, which requires Linux NVIDIA driver
580 or later. Provisioning includes a supported OS and driver; an ordinary CPU VM
or CPU-only container does not provide GPU execution.
[GPU setup](https://docs.cloud.google.com/compute/docs/gpus/create-gpu-vm-g-series),
[driver installation](https://docs.cloud.google.com/compute/docs/gpus/install-drivers-gpu),
[JAX installation](https://docs.jax.dev/en/latest/installation.html).

On the GPU VM, from the engine checkout:

```bash
nvidia-smi
python3.11 -m venv .venv-gpu
.venv-gpu/bin/python -m pip install -c constraints.txt -e '.[api,dev,gpu]'
.venv-gpu/bin/python - <<'PY'
import jax
print(jax.__version__, jax.devices())
if jax.default_backend() != 'gpu':
    raise SystemExit('GPU backend unavailable')
PY
```

The standalone API example then uses `.venv-gpu/bin/python`, a fresh queue and no
`JAX_PLATFORMS=cpu` environment override. The API keeps its own JAX work on CPU
while the fresh worker uses the accelerator. Consequently `/version` can report
CPU even for a GPU pricing job; the completed result's precision/device report
identifies worker execution.

CPU/GPU comparisons use the same request, seed and numerical tolerances, with
compilation time separated from execution. Finite GPU workloads can also run
through [Batch's GPU configuration](https://docs.cloud.google.com/batch/docs/create-run-job-gpus)
with a corresponding GPU image and driver/container setup.

### TPU

TPU experiments use a provisioned single-host TPU VM, compatible OS image and
supported Python environment. Quota and availability depend on TPU version and
zone; the CPU region is not automatically a valid TPU location.
[Compute Engine TPU setup](https://docs.cloud.google.com/compute/docs/tpus/create-tpu-vm-instance).

The documented engine worker model uses devices within one host. Multi-host TPU
pod execution is outside that model. The engine also has float64 requirements
for calibration, today's values, Greeks and path reductions. A TPU pricing profile
therefore needs numerical compatibility and validation beyond successful device
access. [TPU arithmetic](https://docs.cloud.google.com/tpu/docs/bfloat16).

With Python 3.11 available on the TPU VM, the following is a device check in an
isolated environment. The JAX version matches the inspected engine pin; the
JAX/libtpu/OS combination has not been qualified for engine pricing here.

```bash
python3.11 -m venv ~/tpu-device-check
~/tpu-device-check/bin/python -m pip install 'jax[tpu]==0.10.2'
~/tpu-device-check/bin/python - <<'PY'
import jax, jax.numpy as jnp
if jax.default_backend() != 'tpu':
    raise SystemExit(f'Expected TPU, received {jax.default_backend()}')
print(jax.__version__, jax.devices())
x = jnp.ones((256, 256), dtype=jnp.float32)
y = jax.jit(lambda a: a @ a)(x)
y.block_until_ready()
print(y.shape, y.dtype, y[0, 0])
PY
```

A device check confirms access, not engine accuracy. Engine tests additionally
cover the selected precision policy, output warnings and comparison with a CPU
reference. The API/worker device split also applies on TPU: the control process
uses CPU and the pricing worker owns the accelerator.
[JAX TPU installation](https://docs.jax.dev/en/latest/installation.html#google-cloud-tpu).

## Services around the engine

Standalone services can consume the same engine release without depending on
TraderX. Examples include a scheduled regression workload, an experiment dashboard
or an authenticated job-submission API.

| Service | Possible use |
| --- | --- |
| [Cloud Scheduler](https://docs.cloud.google.com/scheduler/docs/overview) | Schedule an experiment or regression run. |
| [Workflows](https://docs.cloud.google.com/workflows/docs/overview) | Coordinate input preparation, job submission and result collection. |
| [Cloud Run](https://docs.cloud.google.com/run/docs/overview/what-is-cloud-run) | Host a lightweight control API or dashboard, subject to its runtime limits. |
| Batch / Compute Engine | Execute the selected CPU/GPU job or provide an interactive machine. |

These services have their own permissions and execution limits. Their setup can
be independent of the TraderX pipeline, with separate experiment resources and
results.
