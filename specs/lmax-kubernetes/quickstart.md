# Quickstart: lmax-kubernetes

This state now generates a real overlay on top of `014`, but it is still an in-progress LMAX-on-Kubernetes port.

Generate the state:

```bash
bash pipeline/generate-state.sh lmax-kubernetes
```

Run the current inherited runtime harness:

```bash
bash generated/code/target-generated/scripts/start-state-lmax-kubernetes-generated.sh --provider kind --without-sail
```

Check the generated state contract:

```bash
bash scripts/test-state-lmax-kubernetes.sh
```

Validated closeout path:

```bash
bash pipeline/generate-state.sh lmax-kubernetes
bash generated/code/target-generated/scripts/test-state-lmax-kubernetes.sh
```

Current runtime notes:

- The runtime still reuses the `014` lifecycle harness.
- `order-matcher` now carries LMAX-specific actuator readiness and persistent journal/snapshot storage definitions.
- Postgres remains the durable persistence baseline.
- JIT warm-up replay is still deferred, so readiness is currently recovery-gated rather than warm-up-gated.

## GKE First Deploy

From Cloud Shell after the GKE cluster is running:

```bash
cd /path/to/traderX
bash pipeline/generate-state.sh lmax-kubernetes
```

Push the application images to Artifact Registry:

```bash
bash scripts/push-state-lmax-kubernetes-gke-images.sh \
  us-east1-docker.pkg.dev/traderx-501015/traderx
```

Render a GKE-safe manifest set:

```bash
bash scripts/prepare-state-lmax-kubernetes-gke-manifests.sh \
  us-east1-docker.pkg.dev/traderx-501015/traderx
```

Apply the manifests and wait for the public edge endpoint:

```bash
bash scripts/deploy-state-lmax-kubernetes-gke.sh \
  us-east1-docker.pkg.dev/traderx-501015/traderx
```

Notes:

- The GKE render swaps local image names for Artifact Registry image refs.
- `edge-proxy` is switched from `NodePort` to `LoadBalancer`.
- The first-pass cloud render defaults `CORS_ALLOWED_ORIGINS` to `*`.
- Override that later with `TRADERX_CORS_ALLOWED_ORIGINS=https://your-domain`.
