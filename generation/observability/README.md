# YU12 live observability (Prometheus + Grafana)

Wired into the existing TraderX monitoring stack on GKE (2026-07-19).

## Metrics sources
- **Members** — each `ClusterNodeMain` health server (:8080) exposes Prometheus `/metrics`
  labelled by memberId: `traderx_cluster_role` (1 leader / 0 follower), `traderx_cluster_applied`,
  `traderx_cluster_trades`, `traderx_cluster_snapshots`, `traderx_cluster_up`.
- **Gateway** — `order-matcher-gw:18110/metrics`: `traderx_order_events_total{event="accepted"|"fill"}`.

## Prometheus (append to observability-prometheus-config / prometheus.yml)
    - job_name: yu12-gateway
      metrics_path: /metrics
      scrape_interval: 1s
      static_configs: [{ targets: ["order-matcher-gw:18110"] }]
    - job_name: yu12-cluster-members
      metrics_path: /metrics
      scrape_interval: 1s
      static_configs:
        - targets:
            - "order-matcher-cluster-0.order-matcher-cluster:8080"
            - "order-matcher-cluster-1.order-matcher-cluster:8080"
            - "order-matcher-cluster-2.order-matcher-cluster:8080"

## Driving live metrics (bench) — REQUIRED SEED STEP
`scripts/bench/load/run-gke-bench.sh` runs from the in-cluster `bench-runner` pod. For YU12 it needs
three overrides vs its pre-YU12 defaults, plus a one-time seed — otherwise every order is rejected
(silent risk reject) and `booked/s` stays 0 while `submit/s` looks fine:

1. **Target the cluster gateway, not the dead `order-matcher` Service.** The bench default
   `order-matcher` is the pre-YU12 single-BLP Service and has **no endpoints** now — orders
   black-hole (`failed=<all>`, metrics read `ERR`). Use `order-matcher-gw`.
2. **Seed control state first.** On a fresh cluster no account is enabled and no price is
   published, so risk rejects every order. Seed the account + tickers + a price via `/seed`:

       kubectl exec bench-runner -n traderx -- node -e 'fetch("http://order-matcher-gw.traderx.svc.cluster.local:18110/seed",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({accountId:42422,tickers:"JPM,GS,COF,DFS",price:150})}).then(r=>r.text()).then(console.log)'

3. **Run with `LIMIT=150` (marketable at the seeded price) and `SIDES=alternate` (Buy/Sell
   self-match → fills), at `conc<=16`** — the gateway's single owner thread collapses at conc=48
   (`failed` spikes); conc=16 sustains ~9k booked/s, ~15k submit/s, 0 failed:

       LIMIT=150 SIDES=alternate ACCOUNT=42422 \
         MATCHER_SVC=http://order-matcher-gw.traderx.svc.cluster.local:18110 \
         bash scripts/bench/load/run-gke-bench.sh live 3 30 500 16

**Failover viz:** during a bench run, crash the leader —
`kubectl exec order-matcher-cluster-<leader> -c cluster-node -n traderx -- sh -c 'kill -9 $(pidof java)'`
— and watch the leader state-timeline hand off. (Find the leader: the member whose
`traderx_cluster_role` metric is 1.)

## Grafana
`grafana-yu12-cluster.json` → add as a key in the `observability-grafana-dashboards` ConfigMap,
restart grafana. UID `yu12-cluster`, title "YU12 Aeron Cluster — live". Live at
grafana.yaakovseif.dev. Panels: leader-per-member state timeline (failover viz), throughput,
applied-per-member, replication lag, snapshots.

### GKE ingress note (root_url)
The GKE ingress (`cluster-addons/traderx-ingress.yaml`) routes `grafana.yaakovseif.dev/` at the
**root** to `grafana:3000`, not under a `/grafana/` path. The generated grafana deployment ships
with `GF_SERVER_ROOT_URL=%(protocol)s://%(domain)s/grafana/` + `SERVE_FROM_SUB_PATH=true` (correct
for the local compose/tilt setup where grafana lives behind a path). On GKE that makes grafana
301-redirect `/` → `http://localhost/grafana/` — unreachable. Override on GKE only:

    kubectl set env deploy/grafana -n traderx \
      GF_SERVER_ROOT_URL='https://grafana.yaakovseif.dev/' \
      GF_SERVER_SERVE_FROM_SUB_PATH='false' \
      GF_SERVER_DOMAIN='grafana.yaakovseif.dev'
