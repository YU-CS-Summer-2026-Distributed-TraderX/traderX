# GCP / GKE Deploy

This is the first-pass cloud deployment path for `YU02-lmax-kubernetes`.

Assumptions:

- GKE Standard cluster already exists and `kubectl` points at it.
- Artifact Registry auth is already configured with `gcloud auth configure-docker`.
- The generated state is current.

Recommended sequence:

1. Generate the state.
2. Build and push the 10 TraderX app images to Artifact Registry.
3. Render a GKE-safe manifest set from the generated Kubernetes base.
4. Apply the manifest set to the cluster.
5. Wait for the `edge-proxy` `LoadBalancer` endpoint.
6. Smoke the public UI and API explorer.

Commands:

```bash
bash pipeline/generate-state.sh YU02-lmax-kubernetes

bash scripts/push-state-YU02-lmax-kubernetes-gke-images.sh \
  us-east1-docker.pkg.dev/traderx-501015/traderx

bash scripts/deploy-state-YU02-lmax-kubernetes-gke.sh \
  us-east1-docker.pkg.dev/traderx-501015/traderx
```

Important first-pass decisions:

- Keep the current in-cluster Postgres deployment.
- Keep the current `order-matcher` PVC and let GKE dynamically provision storage.
- Use `LoadBalancer` on `edge-proxy` instead of the local `NodePort` shape.
- Start with wildcard CORS for cloud bring-up, then tighten once the public hostname is stable.
