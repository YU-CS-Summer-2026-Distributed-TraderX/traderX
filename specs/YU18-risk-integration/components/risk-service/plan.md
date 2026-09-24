# Risk service packaging plan

Status: planned. Owner: unassigned.

1. Inspect the actual current source and existing evidence.
2. Expand spec and agree interfaces/dependencies with the coordinator.
3. Implement in the assigned isolated checkout, preserving shared contracts.
4. Run acceptance cases and deliver exact changed files, revision and evidence.

Reference the external pack at /Users/yaakov/dev/jax_risk_engine/risk-engine-container-spec. Qualify host/container parity, both APIs, input/result mounts, bounded resources and graceful shutdown. Preserve known API limitations rather than disguising them.

No work is dispatched by this plan; no new state branch is required.

2026-09-24 RI-08 sequence: existing workflow audit → consumer composition/console gates →
actual status image build and offline reader proof → explicit-input external packaging smoke.
Completed locally; coordinator review and externally authorized CI checkout wiring remain next.
See the RI-08 report; no cloud or deployment phase is authorized.
