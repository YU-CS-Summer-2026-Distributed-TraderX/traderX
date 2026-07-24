# 05 — OpenTelemetry + observability platform (asynchronous, off the hot path)

> The supportability half of the mandate: *"proper logging and observability for support and
> maintenance… integrate them in an asynchronous manner so you don't slow down the flow of trades."*
> **Start this in parallel with brief 01** — it touches our own layers, so an upstream rebase cannot
> invalidate it. Lane: implementation. See [[00-INDEX]].

## We are unusually well-positioned for the hard constraint

The stated requirement — *don't slow the trade flow* — is exactly the discipline we just spent the
latency campaign proving, and there's a working template in-tree: the LATENCY-01/02 instrumentation is
**env-gated, sampled, side-channel HdrHistograms that never touch replicated state**, with a verified
negligible observer effect. Reuse that pattern; don't invent a new one.

**Two rules that are non-negotiable here:**
1. **Never write telemetry into the sequenced log or the apply path's output.** Timing/tracing must be a
   side effect only — if it can influence what the state machine produces, it breaks determinism and
   turns an observability change into a core change requiring a member roll.
2. **Never let the exporter block the trade path.** Batch/async export, bounded queues, and an explicit
   drop-with-a-counter policy when the queue is full. A telemetry backpressure stall reaching the owner
   thread would be the worst possible own-goal.

## What already exists (don't rebuild)

- Prometheus-format `/metrics` on members and gateways: `traderx_cluster_role` (1 = leader),
  `traderx_cluster_applied`, **`traderx_cluster_next_order_ref`** (the ground-truth committed counter),
  `traderx_cluster_trades`, plus gateway counters.
- `/ready` (readiness incl. replay state) and an env-gated `/latency` endpoint (`LATENCY_DECOMP=1`)
  exposing per-hop p50/p99/p99.9.
- promtail shipping logs; per-thread `/proc` CPU profiling tooling from the throughput campaign.

**Missing: distributed traces, a collector, and a platform.**

## The job

1. **Traces** — OpenTelemetry spans across the real request path: client → gateway (REST / FIX / binary)
   → sequencer → consensus commit → apply → egress → read model. The interesting part is **propagating
   context across the consensus boundary** without putting trace IDs into replicated state; sampling
   decisions should be made at ingress and carried out-of-band.
2. **Metrics** — export the existing counters through OTel rather than replacing them, so the
   ground-truth semantics (`next_order_ref`, not booked counters) are preserved.
3. **Logs** — structured, correlated to trace IDs, shipped asynchronously.
4. **Platform** — an open-source stack (e.g. OTel Collector + Prometheus/Tempo/Loki behind Grafana).
   Deploy it *outside* the member node pool so it can never contend with the pinned Aeron cores.
5. **Prove the async claim.** A before/after benchmark with telemetry off vs on, showing throughput and
   latency are unchanged within run-to-run variance. **This is the deliverable that makes the claim
   credible** — asserting "it's asynchronous" is not the same as showing it.

## Traps

- **Sampling, not trace-everything.** At 190k orders/sec, per-order spans are their own performance
  problem. Head-sample at ingress, with a tail-sample path for errors if the platform supports it.
- **`kubectl top` is ~70× unreliable** under load — do not build dashboards or alerts on it; use `/proc`
  or cgroup counters.
- Members run on **tainted, dedicated, core-pinned nodes**; do not schedule collectors or agents there.
- The observability change must **not** become a member roll. Keep it gateway/side-channel where
  possible; if member code must change, it's env-gated and side-effect-only.

## Deliverable

Working traces/metrics/logs into a dashboard that tells a support story (where is the order, which
member is leader, what's the commit latency, what's dropping), **plus the before/after benchmark
proving it costs nothing on the trade path.** Both are presentation material.

## Conventions

Commit per capability; propagate to descendant branches verifying two ways (dead-layer trap — see
[[00-INDEX]]); `git push` goes to yaakov. GKE only if the platform deployment needs it.
