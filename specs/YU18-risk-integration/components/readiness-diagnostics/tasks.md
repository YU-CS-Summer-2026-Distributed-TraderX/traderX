# Member readiness diagnostics tasks

Status: implemented and locally verified; coordinator review pending. Owner: Codex RI23.

- [x] Verify accepted isolated checkout and claim exact readiness paths after RI22 source release.
- [x] Implement additive coverage/lag/observed-sequence and sample-time diagnostics while preserving legacy routing behavior.
- [x] Reproduce original HTTP ambiguity; source-composed tests and negative controls with nonzero reports.
- [x] Verify canonical generated runtime/component parity and focused compatibility checks.
- [x] Record exact evidence, limitations and scoped commit; release for coordinator review.
- [ ] Coordinator review and controlled integration; shared indexes remain coordinator-owned.

Gateway saturation, feed backoff, routing/quorum policy, partition/HA proofs and deployment measurements remain separate RI23 work. This task makes no claim to resolve them.

Local evidence: initial original handler 1/1; R1 source diagnostics 29/29; generated diagnostics and scoped accounting/snapshot regressions 82/82; disposable RI22 gauge compatibility 43/43. The initial two diagnostic mutations each failed one runtime assertion; their evidence remains in the initial report. Exact counts are preserved in the external review report and XML. No cluster, partition, HA, performance or financial validation.

## R1 diagnostic started-field validation

- [x] Reproduce all seven present invalid started values through the actual production sampler: null, two strings, two numbers, array and object. Each old-guard assertion fails; legacy readiness stays true.
- [x] Reject every present nonboolean started for observations; preserve missing/true observation, false refusal and exact legacy routing parse/decision.
- [x] Verify corrected source/generated and disposable RI22 compatibility; deliver scoped correction for coordinator review.
