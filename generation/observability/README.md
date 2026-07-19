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
