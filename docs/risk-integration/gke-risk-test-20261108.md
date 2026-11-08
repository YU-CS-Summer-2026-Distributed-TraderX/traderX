# The engine fork on GKE: results, November 8

**What ran.**
- **Engine.** TraderX's fork `a5bd7c3` (`traderx/engine-fork`), as an HTTP pod in the disposable namespace
  `pa-proof-risk` (label `traderx-proof=disposable`) on `traderx-demo`, us-east1-b.
- **Pools.**
  - `pa-risk-pool`: one c4d-standard-32, tainted `traderx-riskproof`.
  - `pa-risk-gpu-pool`: one a2-highgpu-1g with an A100 40 GB, tainted `traderx-riskgpu`, driver 580.178.
  - `system-pool` was resized 0 → 1 for the run and back to 0 at teardown (FR-PRG-05).
- **TraderX side.** It stayed local, as in the proof: three child-JVM members and the real gateway. The engine
  was reached by `kubectl port-forward`.
- **Market.** The synthetic one (`financial_validation=false`), as in [fork-pricing-20261107.md](fork-pricing-20261107.md).
- **Approval.** yaakov approved the full GKE test and the projected ≤ ~$28.
- **Spec.** FR-RP-VARES-08 in [risk-pipeline/spec.md](../../specs/YU18-risk-integration/components/risk-pipeline/spec.md).

**Images.** Built by Cloud Build from
[Dockerfile.engine-http](../../specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools/packaging/Dockerfile.engine-http).
`us-east1/traderx-demo/risk-engine-fork`:

- `fork-a5bd7c3-20261107`: `sha256:3f45185b…6e03991`.
- `fork-a5bd7c3-20261107-cuda13`: `sha256:2cf8602a…edca1193`, which adds `jax[cuda13]==0.10.2` at the fork's
  `constraints.txt` pins.

They were built in `risk-engine` and copied to `traderx-demo`, because the nodes' account cannot read
`risk-engine`. Every run reported `engine.revision` `a5bd7c3…6225` and `modified=false`.

## 1. Results

| Run | Mac (Nov 7, `b90af35`) | GKE c4d-standard-32 | GKE A100 |
|---|---|---|---|
| VaR/ES proof, 17 trades | PASSED | **PASSED** | **PASSED** |
| Genuine book, 11,360 trades, 256 scenarios | 5,949 s, 3.4 GiB | **3,926 s**, 11.2 GiB | **3,271 s**, ≥11 GiB (one mid-run sample) |
| VaR99 (book, 256) | 73,560,770.0634270 | 73,560,770.0634257 | 73,560,770.0634258 |
| Genuine book, 1,024 scenarios | — | **14,961 s** (4.2 h), 11.8 GiB, VaR99 77,759,354.61 | not run |
| Linear tile ×10, 53,270 trades, 256 scenarios | — | **113.5 s**, VaR99 700,778,914.98994 | 820 s, VaR99 700,778,914.98994 |

- **Proof.** VaR99 373,857.16 and ES97.5 381,254.68 on both pods, as on the Mac. All 4 negative controls were
  refused with a 400. ORE parity: 15 of 15 referenced trades agree, max 2.7e-5 relative, and the bounds hold.
- **Book parity.** `ore_parity.py --sample 300` on the A100's book: 285 referenced trades, all agree, the same
  maxima as on the Mac.
- **Float64 on the A100: within tolerance, not bit-for-bit.** Every trade's NPV, against the GKE CPU pod:
  - proof: 8 of 17 bit-identical, max 2e-13 relative (the FD options);
  - book: 5,447 of 11,360 bit-identical, max 4e-13 relative.

  The CPU pod against the Mac's `b90af35` book: 5,104 bit-identical, max 1.2e-12.
- **Where the time goes.** The c4d pod used about 3 of its 32 cores; the calibrated swaptions, priced trade by
  trade on the host, set the run's length. The A100 cut the book by 17% against the c4d.
- **1,024 scenarios** took 3.8× the 256-scenario time on the same pod. Every one of the 11,360 trades priced
  (`modified=false`), and ES97.5 was 78.20mm. The run outlived `large_book.py`'s 3 h deadline, so its figures
  come from the run's own reply, fetched when it finished.
- **The linear tile** ran 7× faster on the c4d than on the A100, and VaR99 agreed to 11 significant figures.
  Why the A100 is slower here was not investigated. At this size a GPU does not pay for the linear classes.

## 2. What broke on Linux, and the fix

The first two 256-scenario runs on the c4d pod failed after about 4.5 minutes. The engine's worker process
died mid-run, the API stayed up, and the run was marked `interrupted`. Bisecting by class: six classes
priced, and Americans alone killed the worker.

- **Ruled out: memory.** The cgroup recorded no OOM kill. The worker held 5 GiB of a 96 GiB limit.
- **The cause.** The worker log, kept before the supervisor's restart truncated it, read
  `LLVM compilation error: Cannot allocate memory`. That is the per-process mapping limit:
  - Linux's default `vm.max_map_count` is 65,530;
  - XLA's CPU JIT keeps mappings for every compiled program, and the calibrated swaptions compile many;
  - macOS has no such limit.
- **The fix.** With the node's limit raised to 1,048,576, the same book completed. Its worker peaked at
  116,416 mappings.

FR-RP-VARES-08 now requires the higher limit. Production would set it in the node pool's
`linuxNodeConfig.sysctls`; this test set it on its own node through a privileged node-debug pod.

The CUDA image also needed `LD_LIBRARY_PATH=/usr/local/nvidia/lib64` before JAX could find GKE's driver.

## 3. Isolation

`traderx-demo` has network policy off, so a NetworkPolicy would be inert. None was relied on.

- The pod's environment names nothing in `traderx`. Its service account token is not mounted.
- Its observed TCP/UDP peers were only the kubelet's readiness probe and the port-forward (`/proc/net/tcp`
  and `udp`, before and after the runs).
- Cluster DNS does resolve `traderx` service names from the pod, so it could reach them. The isolation shown
  is that nothing in it does.
- No TraderX workload ran on the side rig, and nothing traded there.

## 4. Cost and teardown

Wall-clock pool time at on-demand list prices, us-east1:

| Item | Time (ET) | Cost |
|---|---|---|
| `pa-risk-pool`, c4d-standard-32 | 10:53 pm – 5:30 am, 6.6 h at ~$1.62/h | ~$10.70 |
| `pa-risk-gpu-pool`, a2-highgpu-1g | ~11:25 pm – 1:19 am, 1.9 h at ~$3.70/h | ~$7.00 |
| `system-pool`, one e2-standard-2 | 10:52 pm – 5:34 am | ~$0.45 |
| Cloud Build (three builds, ~11 min) and disks | | ~$0.20 |
| **Total** | | **~$18.40**, inside the approved ~$28 |

Teardown, November 8: the namespace and both pools were deleted and `system-pool` was resized to 0 (its
managed group's target size is 0). `gcloud compute instances list` shows no running instance, only eight
TERMINATED VMs from earlier work. No node-debug pod was left in `default`. The two image tags stay in
Artifact Registry.

## Reproduce

```bash
git -c core.autocrlf=false clone --depth 1 --no-local file://$HOME/dev/lmax/jax-risk-traderx ctx/engine-src -b traderx/engine-fork
cp specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools/packaging/{requirements-linux-amd64.lock,Dockerfile.engine-http} ctx/
mv ctx/Dockerfile.engine-http ctx/Dockerfile; echo '#' > ctx/.gcloudignore
gcloud builds submit ctx --region us-east1 --tag us-east1-docker.pkg.dev/project-compsci-484414/<repo>/risk-engine-fork:<tag>
kubectl port-forward -n pa-proof-risk deploy/risk-engine 18780:8080
RISK_ENGINE_ORIGIN=http://127.0.0.1:18780 scripts/proofs/yu18-var-es-e2e.sh "$(mktemp -d /private/tmp/var-es.XXXXXX)/out"
python3 specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools/large_book.py \
  --artifacts <book> --origin http://127.0.0.1:18780 --evidence <dir> --scenarios 256
```
