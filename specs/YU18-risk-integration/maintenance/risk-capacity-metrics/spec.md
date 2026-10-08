# Risk-capacity metrics specification

Status: implemented locally; review pending. Owner: Codex RI22. Dependencies: YU18 complete ClusterNodeMain override, existing MatchingEngine riskState and BlpRiskState accounting accessors; parent generation remains authoritative.

- **FR-RCM01:** Append availability, occupied-account count, aggregate reservation, account reservation and executed exposure gauges to the existing member `/metrics` response. Use authoritative raw engine money ticks and member/account identity; do not calculate available credit or change decisions.
- **FR-RCM02:** Unknown/uninitialized state is explicitly unavailable with monetary samples absent. Initialized empty accounting is available with a real zero aggregate. Every occupied account, including disabled accounts, retains explicit zero samples after release; replaced state has no cached labels. Refused unknown inputs create no metric labels.
- **FR-RCM03:** Current series are bounded by occupied engine account slots. Explain reservation versus gross executed/booked exposure, order multipliers and existing swap FX valuation, integer saturation and read races. Replicas are not independent capacity.
- **NFR-RCM01:** Only the cold HTTP path allocates/enumerates. Decision/write/apply, wire, snapshot bytes and hot allocation behavior remain unchanged. No lock or observability writer is added to deterministic state.

Executable acceptance: `RiskCapacityMetricsTest` tests actual production HTTP responses for cold/empty state, two accounts, partial execution/cancel, complete consume, disabled/unknown/refused input, restriction refusal, actual STP cancellation, actual sequenced EUR booking, replacement state and retained zero labels. Formatter cases cover multipliers, table bound, aggregate saturation, credit refusal and null risk. Nonzero controls precede absence/zero assertions; the parser fails on missing or duplicate required samples. Source composition and canonical generated composition must execute nonzero tests with exact owner parity. Reconstructed original handler must fail the new positive HTTP assertion at runtime.

Broader RI22 OTC refusal audit and control-account readouts remain separate work. No deployment, retained rig, financial risk model, input data, migration, or new service is included. Sampling limitations and operator semantics are in [README](README.md).
