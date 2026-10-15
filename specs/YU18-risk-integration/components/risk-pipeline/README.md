# Risk pipeline

Status: review needed; local milestone implemented and tested, local one-portfolio milestone. Owner: Codex RI-03 (`codex/risk-pipeline`).

[Spec](spec.md) · [Plan](plan.md) · [Tasks](tasks.md). Backlog [RI-03](../../../../issues/risk-integration/03-risk-pipeline.md).

Consumes the accepted engine container through /eod/price, reusing TraderX export/bundle/coordinator/intake and existing Risk UI. Inputs are synthetic exporter artifacts with explicit assumed curves. No parallel workers, production risk readiness or cloud work.

Evidence and reproduction: [RI-03 local container proof](../../../../docs/risk-integration/ri03-local-container.md). Broader distributed readiness remains open.

## Current portfolio CPU mapping

A separate local candidate packages the current owned CPU service and maps sequenced synthetic Treasury bills through `/portfolio/price`. It reuses bundle validation and portfolio intake; the earlier EOD coordinator and UI are unchanged. See [CPU export platform](../../../../docs/risk-integration/cpu-export-platform.md) for dependencies, reproduction, measured evidence and pending cloud/status gates.

## Local Batch result view

The owned candidate imports verified external CPU Batch artifacts and exposes an explicitly selected retained portfolio in the existing Risk tab. It revalidates custody offline on each read. See [local import contract](../../../../docs/risk-integration/batch-result-ingestion.md). Local proof is separate from shared-branch integration and cloud deployment.
