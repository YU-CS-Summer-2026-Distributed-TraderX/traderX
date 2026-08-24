# YU12 — Split the Gateway Service so REST Scales Out (sessionAffinity per-port)

**Purpose:** the gateway Service has `sessionAffinity: ClientIP` (needed for FIX session stickiness),
but a k8s Service applies affinity to ALL ports. That pins REST (18110) to a single gateway pod per
client IP, silently defeating the horizontal scale-out for REST. Fix: two Services, one per protocol.
**Status:** MANIFESTS DONE 2026-07-20 (`gke/gateway.yaml`: `order-matcher-gw` REST-only no
affinity, new `order-matcher-gw-fix` FIX-only ClientIP; spec + generated mirrors in sync) —
pending `kubectl apply` on GKE + the 3-pod bench re-run. Created 2026-07-20. Untracked working note.
**Parent:** `RECAP-2026-07-20-yu12-bridge-bench-session.md`.

---

## Evidence

With load from one `bench-runner` pod through `order-matcher-gw`, per-gateway accepted counters:

| gateway pod | accepted orders |
|---|---|
| 10.8.0.133 | 0 |
| 10.8.2.137 | 0 |
| 10.8.1.6 | **5,276,250** |

**Two of three gateways were idle the entire session.** Bypassing the Service (driving each pod
directly) lifted submit from 41k → 64k — the scale-out works, the Service config hid it.

## Root cause

`order-matcher-gw` Service: `sessionAffinity: ClientIP`, `timeoutSeconds: 10800`. Affinity is
per-Service, not per-port, so REST (18110) inherits the FIX stickiness. All requests from one source
IP → one endpoint. Real deployments with few client IPs (or one ingress) get no REST scale-out.

## Fix (recommended)

Split into two Services over the same gateway pods:
- **`order-matcher-gw`** — REST only (18110), `sessionAffinity: None` → round-robins across all
  gateway replicas.
- **`order-matcher-gw-fix`** — FIX only (18130), `sessionAffinity: ClientIP` → keeps FIX sessions
  pinned (a FIX session is stateful per acceptor; it MUST stay on one pod).

Then repoint REST clients (trade-service, bench) at `order-matcher-gw:18110` and FIX clients at
`order-matcher-gw-fix:18130`. No gateway code change; manifest-only. Update
`generation/kubernetes/cluster/gke/gateway.yaml` + kustomization.

## Caveats / things to verify

- **FIX correctness depends on stickiness** — do NOT drop affinity from the FIX Service. Each gateway
  replica is its own FIX acceptor with its own session state; a FIX client bouncing between pods
  breaks sequence numbers.
- The bench harness should still drive pods **directly** (or from multiple client IPs) to measure the
  true ceiling — a single-IP client through the round-robin Service still lands on one pod per
  connection depending on kube-proxy mode. Per-pod direct load remains the cleanest measurement.
- Confirm the ingress/edge-proxy in front doesn't collapse all traffic to one source IP (SNAT) —
  if it does, REST scale-out needs client-IP preservation or the load spread won't materialize in
  production even after the split.
