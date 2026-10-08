# Projector heap-OOM exit

Owner: Codex RI24. Status: integrated locally, 2026-10-07; see the evidence and limits below.

YU18 trade-processor container launches terminate on a JVM heap allocation failure even when application code would catch `OutOfMemoryError`. Existing heap sizing, images, probes and application behavior are preserved. See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).

Authoritative inputs stay under the state's `generation/`: trade-processor `Dockerfile` and `Dockerfile.compose`, the full-runtime Kubernetes deployment override, and the GKE demo strategic patch. [Component rules](../../../../docs/spec-kit/state-components.md) apply.

This does not close RI24's memory bounds, liveness, restart or event-durability work. No deployment or retained-rig change is included.

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
