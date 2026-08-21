# Convergence Rationale (C2)

State `009-order-management-matcher` is the C2 functional convergence state.

Rationale:

- It brings core functional capabilities (pricing awareness and order lifecycle) onto the C1 architecture baseline.
- It is the primary compose-first "feature-complete" state for developers who do not need Kubernetes/Tilt.
- It keeps inherited observability demo-ready by documenting Grafana access in generated snapshots and using domain labels for pricing, messaging, runtime, and order dashboards instead of future-state tags.
- It is the preferred base for future functional extensions unless a state-specific learning objective says otherwise.

## Pack content changes since `main`

The convergence-rationale delta gate (`pipeline/validate-convergence-rationale-deltas.sh`) requires
this file to record why the pack's content diverges from the base branch, so that a change to a
convergence state is always a decision someone wrote down rather than a diff someone noticed.

- **Grafana throughput dashboards added** (`a94fb066`, 2026-06-29):
  `traderx-lmax-throughput.json`, `traderx-lmax-benchmark-throughput.json` and
  `traderx-trades-per-second.json` under
  `generation/runtime-overrides/order-management-matcher/observability/grafana/dashboards/`.
  **Purely additive — 259 insertions, no deletions, no file rewritten.** The convergence model is
  untouched: no requirement, acceptance criterion, service boundary or dependency changed, and the
  dashboards visualise metrics this state already emitted. This is the "keeps inherited
  observability demo-ready" bullet above being carried out, not extended: the panels use the same
  domain labels that bullet commits to, rather than future-state tags.

  It therefore needs no change to C2's role. Recorded because the gate is right that content moved
  and silence is indistinguishable from an oversight — which is exactly what this was, for two
  months.
