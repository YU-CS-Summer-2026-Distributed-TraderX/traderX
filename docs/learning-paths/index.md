---
title: Learning Paths
---

# Learning Paths

This page is generated from `catalog/state-catalog.json`.
Green nodes represent convergence checkpoints (C-level milestones such as `[C0]`, `[C1]`, `[C2]`, `[C3]`).

## Convergence-Level Graph

This high-level view shows only the canonical convergence progression from `C0` to `C3`.

```mermaid
flowchart LR
  S004_containerized_compose_runtime["004: Containerized Compose Runtime (NGINX Ingress) [C0]"]
  S007_observability_lgtm_compose["007: Observability with LGTM on Compose [C1]"]
  S009_order_management_matcher["009: Order Management and Matcher [C2]"]
  S012_platform_convergence_c3["012: Platform Convergence C3 [C3]"]
  S004_containerized_compose_runtime --> S007_observability_lgtm_compose
  S007_observability_lgtm_compose --> S009_order_management_matcher
  S009_order_management_matcher --> S012_platform_convergence_c3
  click S004_containerized_compose_runtime href "/specs/containerized-compose-runtime" "Open 004-containerized-compose-runtime"
  click S007_observability_lgtm_compose href "/specs/observability-lgtm-compose" "Open 007-observability-lgtm-compose"
  click S009_order_management_matcher href "/specs/order-management-matcher" "Open 009-order-management-matcher"
  click S012_platform_convergence_c3 href "/specs/platform-convergence-c3" "Open 012-platform-convergence-c3"
  classDef convergence fill:#d7f5dd,stroke:#2e7d32,stroke-width:2px
  class S004_containerized_compose_runtime convergence
  class S007_observability_lgtm_compose convergence
  class S009_order_management_matcher convergence
  class S012_platform_convergence_c3 convergence
```

## Official Current Graph

```mermaid
flowchart TB
  S001_baseline_uncontainerized_parity["001: Simple App - Base Uncontainerized App"]
  S002_edge_proxy_uncontainerized["002: Edge Proxy Uncontainerized"]
  S003_agentic_harness_foundation["003: Agentic Harness Foundation"]
  S004_containerized_compose_runtime["004: Containerized Compose Runtime (NGINX Ingress) [C0]"]
  S005_postgres_database_replacement["005: PostgreSQL Database Replacement"]
  S006_messaging_nats_replacement["006: Messaging Layer Replacement with NATS"]
  S007_observability_lgtm_compose["007: Observability with LGTM on Compose [C1]"]
  S008_pricing_awareness_market_data["008: Pricing Awareness and Market Data Streaming"]
  S009_order_management_matcher["009: Order Management and Matcher [C2]"]
  S010_kubernetes_runtime["010: Kubernetes Runtime on C2"]
  S011_tilt_kubernetes_dev_loop["011: Tilt Local Dev on Kubernetes"]
  S012_platform_convergence_c3["012: Platform Convergence C3 [C3]"]
  S013_radius_kubernetes_platform["013: Radius Platform on Kubernetes (Optional)"]
  S014_fdc3_intent_interoperability["014: FDC3 Intent Interoperability on C3"]
  SYU01_lmax_sequencer["YU01-lmax-sequencer: LMAX Sequencer (Trading Hot Path)"]
  SYU02_lmax_kubernetes["YU02-lmax-kubernetes: LMAX Kubernetes"]
  SYU03_in_memory_risk_gateway["YU03-in-memory-risk-gateway: In-Memory Risk Gateway"]
  SYU04_durable_control_feeds["YU04-durable-control-feeds: Durable Control Feeds"]
  SYU05_post_trade_compliance["YU05-post-trade-compliance: Post-Trade Compliance Bundle"]
  SYU06_eod_price_production["YU06-eod-price-production: EOD Price Production + Overnight Batch Chain"]
  SYU07_historical_tick_store["YU07-historical-tick-store: Historical Tick Store"]
  SYU08_execution_algo_engine["YU08-execution-algo-engine: Execution Algo Engine"]
  SYU09_ops_hardening["YU09-ops-hardening: Ops Hardening"]
  SYU10_fix_ingress["YU10-fix-ingress: FIX Order-Entry Ingress"]
  SYU11_aeron_replication["YU11-aeron-replication: Aeron SBE BLP Replication"]
  SYU12_aeron_cluster["YU12-aeron-cluster: Aeron Cluster BLP Consensus"]
  SYU13_limit_order_book["YU13-limit-order-book: Crossing Limit-Order Book"]
  SYU14_listed_equity_options["YU14-listed-equity-options: Listed Equity Options"]
  SYU15_eod_risk_extract["YU15-eod-risk-extract: EOD Risk Extract"]
  S001_baseline_uncontainerized_parity --> S002_edge_proxy_uncontainerized
  S002_edge_proxy_uncontainerized --> S003_agentic_harness_foundation
  S003_agentic_harness_foundation --> S004_containerized_compose_runtime
  S004_containerized_compose_runtime --> S005_postgres_database_replacement
  S005_postgres_database_replacement --> S006_messaging_nats_replacement
  S006_messaging_nats_replacement --> S007_observability_lgtm_compose
  S007_observability_lgtm_compose --> S008_pricing_awareness_market_data
  S008_pricing_awareness_market_data --> S009_order_management_matcher
  S009_order_management_matcher --> S010_kubernetes_runtime
  S010_kubernetes_runtime --> S011_tilt_kubernetes_dev_loop
  S011_tilt_kubernetes_dev_loop --> S012_platform_convergence_c3
  S012_platform_convergence_c3 --> S013_radius_kubernetes_platform
  S012_platform_convergence_c3 --> S014_fdc3_intent_interoperability
  S009_order_management_matcher --> SYU01_lmax_sequencer
  S014_fdc3_intent_interoperability --> SYU02_lmax_kubernetes
  SYU02_lmax_kubernetes --> SYU03_in_memory_risk_gateway
  SYU03_in_memory_risk_gateway --> SYU04_durable_control_feeds
  SYU04_durable_control_feeds --> SYU05_post_trade_compliance
  SYU05_post_trade_compliance --> SYU06_eod_price_production
  SYU06_eod_price_production --> SYU07_historical_tick_store
  SYU07_historical_tick_store --> SYU08_execution_algo_engine
  SYU08_execution_algo_engine --> SYU09_ops_hardening
  SYU09_ops_hardening --> SYU10_fix_ingress
  SYU10_fix_ingress --> SYU11_aeron_replication
  SYU11_aeron_replication --> SYU12_aeron_cluster
  SYU12_aeron_cluster --> SYU13_limit_order_book
  SYU13_limit_order_book --> SYU14_listed_equity_options
  SYU14_listed_equity_options --> SYU15_eod_risk_extract
  S009_order_management_matcher -.-> S012_platform_convergence_c3
  click S001_baseline_uncontainerized_parity href "/specs/baseline-uncontainerized-parity" "Open State 001 Spec Pack"
  click S002_edge_proxy_uncontainerized href "/specs/edge-proxy-uncontainerized" "Open State 002 Spec Pack"
  click S003_agentic_harness_foundation href "/specs/agentic-harness-foundation" "Open State 003 Spec Pack"
  click S004_containerized_compose_runtime href "/specs/containerized-compose-runtime" "Open State 004 Spec Pack"
  click S005_postgres_database_replacement href "/specs/postgres-database-replacement" "Open State 005 Spec Pack"
  click S006_messaging_nats_replacement href "/specs/messaging-nats-replacement" "Open State 006 Spec Pack"
  click S007_observability_lgtm_compose href "/specs/observability-lgtm-compose" "Open State 007 Spec Pack"
  click S008_pricing_awareness_market_data href "/specs/pricing-awareness-market-data" "Open State 008 Spec Pack"
  click S009_order_management_matcher href "/specs/order-management-matcher" "Open State 009 Spec Pack"
  click S010_kubernetes_runtime href "/specs/kubernetes-runtime" "Open State 010 Spec Pack"
  click S011_tilt_kubernetes_dev_loop href "/specs/tilt-kubernetes-dev-loop" "Open State 011 Spec Pack"
  click S012_platform_convergence_c3 href "/specs/platform-convergence-c3" "Open State 012 Spec Pack"
  click S013_radius_kubernetes_platform href "/specs/radius-kubernetes-platform" "Open State 013 Spec Pack"
  click S014_fdc3_intent_interoperability href "/specs/fdc3-intent-interoperability" "Open State 014 Spec Pack"
  click SYU01_lmax_sequencer href "/specs/YU01-lmax-sequencer" "Open State YU01-lmax-sequencer Spec Pack"
  click SYU02_lmax_kubernetes href "/specs/YU02-lmax-kubernetes" "Open State YU02-lmax-kubernetes Spec Pack"
  click SYU03_in_memory_risk_gateway href "/specs/YU03-in-memory-risk-gateway" "Open State YU03-in-memory-risk-gateway Spec Pack"
  click SYU04_durable_control_feeds href "/specs/YU04-durable-control-feeds" "Open State YU04-durable-control-feeds Spec Pack"
  click SYU05_post_trade_compliance href "/specs/YU05-post-trade-compliance" "Open State YU05-post-trade-compliance Spec Pack"
  click SYU06_eod_price_production href "/specs/YU06-eod-price-production" "Open State YU06-eod-price-production Spec Pack"
  click SYU07_historical_tick_store href "/specs/YU07-historical-tick-store" "Open State YU07-historical-tick-store Spec Pack"
  click SYU08_execution_algo_engine href "/specs/YU08-execution-algo-engine" "Open State YU08-execution-algo-engine Spec Pack"
  click SYU09_ops_hardening href "/specs/YU09-ops-hardening" "Open State YU09-ops-hardening Spec Pack"
  click SYU10_fix_ingress href "/specs/YU10-fix-ingress" "Open State YU10-fix-ingress Spec Pack"
  click SYU11_aeron_replication href "/specs/YU11-aeron-replication" "Open State YU11-aeron-replication Spec Pack"
  click SYU12_aeron_cluster href "/specs/YU12-aeron-cluster" "Open State YU12-aeron-cluster Spec Pack"
  click SYU13_limit_order_book href "/specs/YU13-limit-order-book" "Open State YU13-limit-order-book Spec Pack"
  click SYU14_listed_equity_options href "/specs/YU14-listed-equity-options" "Open State YU14-listed-equity-options Spec Pack"
  click SYU15_eod_risk_extract href "/specs/YU15-eod-risk-extract" "Open State YU15-eod-risk-extract Spec Pack"
  classDef convergence fill:#d7f5dd,stroke:#2e7d32,stroke-width:2px
  class S004_containerized_compose_runtime convergence
  class S007_observability_lgtm_compose convergence
  class S009_order_management_matcher convergence
  class S012_platform_convergence_c3 convergence
```

## State To Artifact Mapping

| State | Spec Pack | Architecture | Flows / Runtime Topology | Learning Guide | Generated Code Branch |
| --- | --- | --- | --- | --- | --- |
| [`001-baseline-uncontainerized-parity`](pathname:///specs/baseline-uncontainerized-parity) | [link](pathname:///specs/baseline-uncontainerized-parity) | [link](pathname:///specs/baseline-uncontainerized-parity/system/architecture) | [link](pathname:///specs/baseline-uncontainerized-parity/system/end-to-end-flows) | [link](pathname:///docs/learning/state-001-baseline-uncontainerized-parity) | [code/generated-state-001-baseline-uncontainerized-parity](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-001-baseline-uncontainerized-parity) |
| [`002-edge-proxy-uncontainerized`](pathname:///specs/edge-proxy-uncontainerized) | [link](pathname:///specs/edge-proxy-uncontainerized) | [link](pathname:///specs/edge-proxy-uncontainerized/system/architecture) | [link](pathname:///specs/edge-proxy-uncontainerized/system/runtime-topology) | [link](pathname:///docs/learning/state-002-edge-proxy-uncontainerized) | [code/generated-state-002-edge-proxy-uncontainerized](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-002-edge-proxy-uncontainerized) |
| [`003-agentic-harness-foundation`](pathname:///specs/agentic-harness-foundation) | [link](pathname:///specs/agentic-harness-foundation) | [link](pathname:///specs/agentic-harness-foundation/system/architecture) | [link](pathname:///specs/agentic-harness-foundation/system/runtime-topology) | [link](pathname:///docs/learning/state-003-agentic-harness-foundation) | [code/generated-state-003-agentic-harness-foundation](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-003-agentic-harness-foundation) |
| **[`004-containerized-compose-runtime`](pathname:///specs/containerized-compose-runtime)** [(C0)](pathname:///docs/spec-kit/convergence-states#c0) | [link](pathname:///specs/containerized-compose-runtime) | [link](pathname:///specs/containerized-compose-runtime/system/architecture) | [link](pathname:///specs/containerized-compose-runtime/system/runtime-topology) | [link](pathname:///docs/learning/state-004-containerized-compose-runtime) | [code/generated-state-004-containerized-compose-runtime](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-004-containerized-compose-runtime) |
| [`005-postgres-database-replacement`](pathname:///specs/postgres-database-replacement) | [link](pathname:///specs/postgres-database-replacement) | [link](pathname:///specs/postgres-database-replacement/system/architecture) | [link](pathname:///specs/postgres-database-replacement/system/runtime-topology) | [link](pathname:///docs/learning/state-005-postgres-database-replacement) | [code/generated-state-005-postgres-database-replacement](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-005-postgres-database-replacement) |
| [`006-messaging-nats-replacement`](pathname:///specs/messaging-nats-replacement) | [link](pathname:///specs/messaging-nats-replacement) | [link](pathname:///specs/messaging-nats-replacement/system/architecture) | [link](pathname:///specs/messaging-nats-replacement/system/runtime-topology) | [link](pathname:///docs/learning/state-006-messaging-nats-replacement) | [code/generated-state-006-messaging-nats-replacement](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-006-messaging-nats-replacement) |
| **[`007-observability-lgtm-compose`](pathname:///specs/observability-lgtm-compose)** [(C1)](pathname:///docs/spec-kit/convergence-states#c1) | [link](pathname:///specs/observability-lgtm-compose) | [link](pathname:///specs/observability-lgtm-compose/system/architecture) | [link](pathname:///specs/observability-lgtm-compose/system/runtime-topology) | [link](pathname:///docs/learning/state-007-observability-lgtm-compose) | [code/generated-state-007-observability-lgtm-compose](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-007-observability-lgtm-compose) |
| [`008-pricing-awareness-market-data`](pathname:///specs/pricing-awareness-market-data) | [link](pathname:///specs/pricing-awareness-market-data) | [link](pathname:///specs/pricing-awareness-market-data/system/architecture) | [link](pathname:///specs/pricing-awareness-market-data/system/runtime-topology) | [link](pathname:///docs/learning/state-008-pricing-awareness-market-data) | [code/generated-state-008-pricing-awareness-market-data](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-008-pricing-awareness-market-data) |
| **[`009-order-management-matcher`](pathname:///specs/order-management-matcher)** [(C2)](pathname:///docs/spec-kit/convergence-states#c2) | [link](pathname:///specs/order-management-matcher) | [link](pathname:///specs/order-management-matcher/system/architecture) | [link](pathname:///specs/order-management-matcher/system/runtime-topology) | [link](pathname:///docs/learning/state-009-order-management-matcher) | [code/generated-state-009-order-management-matcher](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-009-order-management-matcher) |
| [`010-kubernetes-runtime`](pathname:///specs/kubernetes-runtime) | [link](pathname:///specs/kubernetes-runtime) | [link](pathname:///specs/kubernetes-runtime/system/architecture) | [link](pathname:///specs/kubernetes-runtime/system/runtime-topology) | [link](pathname:///docs/learning/state-010-kubernetes-runtime) | [code/generated-state-010-kubernetes-runtime](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-010-kubernetes-runtime) |
| [`011-tilt-kubernetes-dev-loop`](pathname:///specs/tilt-kubernetes-dev-loop) | [link](pathname:///specs/tilt-kubernetes-dev-loop) | [link](pathname:///specs/tilt-kubernetes-dev-loop/system/architecture) | [link](pathname:///specs/tilt-kubernetes-dev-loop/system/runtime-topology) | [link](pathname:///docs/learning/state-011-tilt-kubernetes-dev-loop) | [code/generated-state-011-tilt-kubernetes-dev-loop](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-011-tilt-kubernetes-dev-loop) |
| **[`012-platform-convergence-c3`](pathname:///specs/platform-convergence-c3)** [(C3)](pathname:///docs/spec-kit/convergence-states#c3) | [link](pathname:///specs/platform-convergence-c3) | [link](pathname:///specs/platform-convergence-c3/system/architecture) | [link](pathname:///specs/platform-convergence-c3/system/runtime-topology) | [link](pathname:///docs/learning/state-012-platform-convergence-c3) | [code/generated-state-012-platform-convergence-c3](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-012-platform-convergence-c3) |
| [`013-radius-kubernetes-platform`](pathname:///specs/radius-kubernetes-platform) | [link](pathname:///specs/radius-kubernetes-platform) | [link](pathname:///specs/radius-kubernetes-platform/system/architecture) | [link](pathname:///specs/radius-kubernetes-platform/system/runtime-topology) | [link](pathname:///docs/learning/state-013-radius-kubernetes-platform) | [code/generated-state-013-radius-kubernetes-platform](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-013-radius-kubernetes-platform) |
| [`014-fdc3-intent-interoperability`](pathname:///specs/fdc3-intent-interoperability) | [link](pathname:///specs/fdc3-intent-interoperability) | [link](pathname:///specs/fdc3-intent-interoperability/system/architecture) | [link](pathname:///specs/fdc3-intent-interoperability/system/runtime-topology) | [link](pathname:///docs/learning/state-014-fdc3-intent-interoperability) | [code/generated-state-014-fdc3-intent-interoperability](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-014-fdc3-intent-interoperability) |
| [`YU01-lmax-sequencer`](pathname:///specs/YU01-lmax-sequencer) | [link](pathname:///specs/YU01-lmax-sequencer) | [link](pathname:///specs/YU01-lmax-sequencer/system/architecture) | [link](pathname:///specs/YU01-lmax-sequencer/system/runtime-topology) | [link](pathname:///docs/learning/state-YU01-lmax-sequencer) | [YU01-lmax-sequencer](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU01-lmax-sequencer) |
| [`YU02-lmax-kubernetes`](pathname:///specs/YU02-lmax-kubernetes) | [link](pathname:///specs/YU02-lmax-kubernetes) | [link](pathname:///specs/YU02-lmax-kubernetes/system/architecture) | [link](pathname:///specs/YU02-lmax-kubernetes/system/runtime-topology) | [link](pathname:///docs/learning/state-YU02-lmax-kubernetes) | [YU02-lmax-kubernetes-blp-ha](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU02-lmax-kubernetes-blp-ha) |
| [`YU03-in-memory-risk-gateway`](pathname:///specs/YU03-in-memory-risk-gateway) | [link](pathname:///specs/YU03-in-memory-risk-gateway) | [link](pathname:///specs/YU03-in-memory-risk-gateway/system/architecture) | [link](pathname:///specs/YU03-in-memory-risk-gateway/system/runtime-topology) | [link](pathname:///docs/learning/state-YU03-in-memory-risk-gateway) | [YU03-in-memory-risk-gateway](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU03-in-memory-risk-gateway) |
| [`YU04-durable-control-feeds`](pathname:///specs/YU04-durable-control-feeds) | [link](pathname:///specs/YU04-durable-control-feeds) | [link](pathname:///specs/YU04-durable-control-feeds/system/architecture) | [link](pathname:///specs/YU04-durable-control-feeds/system/runtime-topology) | [link](pathname:///docs/learning/state-YU04-durable-control-feeds) | [YU04-durable-control-feeds](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU04-durable-control-feeds) |
| [`YU05-post-trade-compliance`](pathname:///specs/YU05-post-trade-compliance) | [link](pathname:///specs/YU05-post-trade-compliance) | [link](pathname:///specs/YU05-post-trade-compliance/system/architecture) | [link](pathname:///specs/YU05-post-trade-compliance/system/runtime-topology) | [link](pathname:///docs/learning/state-YU05-post-trade-compliance) | [YU05-post-trade-compliance](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU05-post-trade-compliance) |
| [`YU06-eod-price-production`](pathname:///specs/YU06-eod-price-production) | [link](pathname:///specs/YU06-eod-price-production) | [link](pathname:///specs/YU06-eod-price-production/system/architecture) | [link](pathname:///specs/YU06-eod-price-production/system/runtime-topology) | [link](pathname:///docs/learning/state-YU06-eod-price-production) | [YU06-eod-price-production](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU06-eod-price-production) |
| [`YU07-historical-tick-store`](pathname:///specs/YU07-historical-tick-store) | [link](pathname:///specs/YU07-historical-tick-store) | [link](pathname:///specs/YU07-historical-tick-store/system/architecture) | [link](pathname:///specs/YU07-historical-tick-store/system/system-context) | [link](pathname:///docs/learning/state-YU07-historical-tick-store) | [YU07-historical-tick-store](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU07-historical-tick-store) |
| [`YU08-execution-algo-engine`](pathname:///specs/YU08-execution-algo-engine) | [link](pathname:///specs/YU08-execution-algo-engine) | [link](pathname:///specs/YU08-execution-algo-engine/system/architecture) | [link](pathname:///specs/YU08-execution-algo-engine/system/system-context) | [link](pathname:///docs/learning/state-YU08-execution-algo-engine) | [YU08-execution-algo-engine](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU08-execution-algo-engine) |
| [`YU09-ops-hardening`](pathname:///specs/YU09-ops-hardening) | [link](pathname:///specs/YU09-ops-hardening) | [link](pathname:///specs/YU09-ops-hardening/system/architecture) | [link](pathname:///specs/YU09-ops-hardening/system/system-context) | [link](pathname:///docs/learning/state-YU09-ops-hardening) | [YU09-ops-hardening](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU09-ops-hardening) |
| [`YU10-fix-ingress`](pathname:///specs/YU10-fix-ingress) | [link](pathname:///specs/YU10-fix-ingress) | [link](pathname:///specs/YU10-fix-ingress/system/architecture) | [link](pathname:///specs/YU10-fix-ingress/system/system-context) | [link](pathname:///docs/learning/state-YU10-fix-ingress) | [YU10-fix-ingress](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU10-fix-ingress) |
| [`YU11-aeron-replication`](pathname:///specs/YU11-aeron-replication) | [link](pathname:///specs/YU11-aeron-replication) | [link](pathname:///specs/YU11-aeron-replication/system/architecture) | [link](pathname:///specs/YU11-aeron-replication/system/system-context) | [link](pathname:///docs/learning/state-YU11-aeron-replication) | [YU11-aeron-replication](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU11-aeron-replication) |
| [`YU12-aeron-cluster`](pathname:///specs/YU12-aeron-cluster) | [link](pathname:///specs/YU12-aeron-cluster) | [link](pathname:///specs/YU12-aeron-cluster/system/architecture) | [link](pathname:///specs/YU12-aeron-cluster/system/system-context) | [link](pathname:///docs/learning/state-YU12-aeron-cluster) | [YU12-aeron-cluster](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU12-aeron-cluster) |
| [`YU13-limit-order-book`](pathname:///specs/YU13-limit-order-book) | [link](pathname:///specs/YU13-limit-order-book) | [link](pathname:///specs/YU13-limit-order-book/system/architecture) | [link](pathname:///specs/YU13-limit-order-book/system/system-context) | [link](pathname:///docs/learning/state-YU13-limit-order-book) | [YU13-limit-order-book](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU13-limit-order-book) |
| [`YU14-listed-equity-options`](pathname:///specs/YU14-listed-equity-options) | [link](pathname:///specs/YU14-listed-equity-options) | [link](pathname:///specs/YU14-listed-equity-options/system/architecture) | [link](pathname:///specs/YU14-listed-equity-options/system/system-context) | [link](pathname:///docs/learning/state-YU14-listed-equity-options) | [YU14-listed-equity-options](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU14-listed-equity-options) |
| [`YU15-eod-risk-extract`](pathname:///specs/YU15-eod-risk-extract) | [link](pathname:///specs/YU15-eod-risk-extract) | [link](pathname:///specs/YU15-eod-risk-extract/system/architecture) | [link](pathname:///specs/YU15-eod-risk-extract/system/system-context) | [link](pathname:///docs/learning/state-YU15-eod-risk-extract) | [YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU15-eod-risk-extract) |

## Swimlane View

```mermaid
flowchart TB
  subgraph PRELUDE["Prelude Track"]
    S001_baseline_uncontainerized_parity["001: Simple App - Base Uncontainerized App"]
    S002_edge_proxy_uncontainerized["002: Edge Proxy Uncontainerized"]
    S003_agentic_harness_foundation["003: Agentic Harness Foundation"]
  end
  subgraph BASELINE["Baseline Track"]
    S004_containerized_compose_runtime["004: Containerized Compose Runtime (NGINX Ingress) [C0]"]
  end
  subgraph ARCHITECTURE["Architecture Track"]
    S005_postgres_database_replacement["005: PostgreSQL Database Replacement"]
    S006_messaging_nats_replacement["006: Messaging Layer Replacement with NATS"]
    SYU01_lmax_sequencer["YU01-lmax-sequencer: LMAX Sequencer (Trading Hot Path)"]
    SYU02_lmax_kubernetes["YU02-lmax-kubernetes: LMAX Kubernetes"]
    SYU11_aeron_replication["YU11-aeron-replication: Aeron SBE BLP Replication"]
    SYU12_aeron_cluster["YU12-aeron-cluster: Aeron Cluster BLP Consensus"]
  end
  subgraph NONFUNCTIONAL["Nonfunctional Track"]
    S007_observability_lgtm_compose["007: Observability with LGTM on Compose [C1]"]
    SYU04_durable_control_feeds["YU04-durable-control-feeds: Durable Control Feeds"]
    SYU09_ops_hardening["YU09-ops-hardening: Ops Hardening"]
  end
  subgraph FUNCTIONAL["Functional Track"]
    S008_pricing_awareness_market_data["008: Pricing Awareness and Market Data Streaming"]
    S009_order_management_matcher["009: Order Management and Matcher [C2]"]
    S014_fdc3_intent_interoperability["014: FDC3 Intent Interoperability on C3"]
    SYU03_in_memory_risk_gateway["YU03-in-memory-risk-gateway: In-Memory Risk Gateway"]
    SYU05_post_trade_compliance["YU05-post-trade-compliance: Post-Trade Compliance Bundle"]
    SYU06_eod_price_production["YU06-eod-price-production: EOD Price Production + Overnight Batch Chain"]
    SYU07_historical_tick_store["YU07-historical-tick-store: Historical Tick Store"]
    SYU08_execution_algo_engine["YU08-execution-algo-engine: Execution Algo Engine"]
    SYU10_fix_ingress["YU10-fix-ingress: FIX Order-Entry Ingress"]
    SYU13_limit_order_book["YU13-limit-order-book: Crossing Limit-Order Book"]
    SYU14_listed_equity_options["YU14-listed-equity-options: Listed Equity Options"]
    SYU15_eod_risk_extract["YU15-eod-risk-extract: EOD Risk Extract"]
  end
  subgraph DEVEX["Devex Track"]
    S010_kubernetes_runtime["010: Kubernetes Runtime on C2"]
    S011_tilt_kubernetes_dev_loop["011: Tilt Local Dev on Kubernetes"]
    S012_platform_convergence_c3["012: Platform Convergence C3 [C3]"]
    S013_radius_kubernetes_platform["013: Radius Platform on Kubernetes (Optional)"]
  end
  S001_baseline_uncontainerized_parity --> S002_edge_proxy_uncontainerized
  S002_edge_proxy_uncontainerized --> S003_agentic_harness_foundation
  S003_agentic_harness_foundation --> S004_containerized_compose_runtime
  S004_containerized_compose_runtime --> S005_postgres_database_replacement
  S005_postgres_database_replacement --> S006_messaging_nats_replacement
  S006_messaging_nats_replacement --> S007_observability_lgtm_compose
  S007_observability_lgtm_compose --> S008_pricing_awareness_market_data
  S008_pricing_awareness_market_data --> S009_order_management_matcher
  S009_order_management_matcher --> S010_kubernetes_runtime
  S010_kubernetes_runtime --> S011_tilt_kubernetes_dev_loop
  S011_tilt_kubernetes_dev_loop --> S012_platform_convergence_c3
  S012_platform_convergence_c3 --> S013_radius_kubernetes_platform
  S012_platform_convergence_c3 --> S014_fdc3_intent_interoperability
  S009_order_management_matcher --> SYU01_lmax_sequencer
  S014_fdc3_intent_interoperability --> SYU02_lmax_kubernetes
  SYU02_lmax_kubernetes --> SYU03_in_memory_risk_gateway
  SYU03_in_memory_risk_gateway --> SYU04_durable_control_feeds
  SYU04_durable_control_feeds --> SYU05_post_trade_compliance
  SYU05_post_trade_compliance --> SYU06_eod_price_production
  SYU06_eod_price_production --> SYU07_historical_tick_store
  SYU07_historical_tick_store --> SYU08_execution_algo_engine
  SYU08_execution_algo_engine --> SYU09_ops_hardening
  SYU09_ops_hardening --> SYU10_fix_ingress
  SYU10_fix_ingress --> SYU11_aeron_replication
  SYU11_aeron_replication --> SYU12_aeron_cluster
  SYU12_aeron_cluster --> SYU13_limit_order_book
  SYU13_limit_order_book --> SYU14_listed_equity_options
  SYU14_listed_equity_options --> SYU15_eod_risk_extract
  S009_order_management_matcher -.-> S012_platform_convergence_c3
  classDef convergence fill:#d7f5dd,stroke:#2e7d32,stroke-width:2px
```

Use `catalog/state-catalog.json` as the canonical state lineage record.
