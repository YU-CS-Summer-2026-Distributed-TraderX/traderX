# OTC refusal audit

Status: integrated locally, 2026-10-07; see the evidence and limits below.
Owner: Codex RI22 OTC chat `01a11835-d552-73d1-a720-e7a15fa66cb3`.
Base: `a0d6da0bfbef481f301bb1172b6810cb8a4c9e3e`.

Sequenced OTC refusals from the actual booking apply path appear on the regulatory report as
`SWAP_REJECTED` or `SWAPTION_REJECTED`. The report replays the retained cluster log into a disposable
shadow service. A null-by-default tap observes the existing decision after its unchanged direct ack.
Accepted contracts retain the existing contract-growth projection; trade reconciliation and order
projection consume their existing outputs.

[Specification](spec.md), [implementation plan](plan.md), [tasks and evidence](tasks.md).
Authoritative generation remains in the [state parent](../../generation/runtime-overrides/order-matcher/).
No retained rig, deployment, financial validation or integration acceptance is claimed.

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
